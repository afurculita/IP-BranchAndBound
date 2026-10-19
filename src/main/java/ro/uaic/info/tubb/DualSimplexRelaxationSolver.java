package ro.uaic.info.tubb;

import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.Literal;
import ro.uaic.info.lp.LpException;
import ro.uaic.info.lp.LpSolution;
import ro.uaic.info.lp.SimplexLpSolver;

/**
 * Warm-started bounded dual simplex for the relaxations of (IP1).
 *
 * <p>The LP of every node has the same rows; nodes only differ in the bounds of the {@code x} variables
 * (fixed to {@code 0} or {@code 1}, or free in {@code [0, 1]}). The (IP1) rows are written in equality form
 * with a surplus variable {@code rᵢ}:
 * <pre>
 *   A x + s − r = 1 − b,     0 ≤ x ≤ 1,  s ≥ 0,  r ≥ 0,     min 1ᵀs
 * </pre>
 * A satisfied clause does not need to be dropped: its row is simply redundant.
 *
 * <p>Dual feasibility of a basis does not depend on the variable bounds, so the optimal basis of the last
 * node solved stays dual feasible for the next node once each non-basic variable is placed at the bound
 * that matches the sign of its reduced cost. A handful of dual simplex pivots then restores primal
 * feasibility, instead of the hundreds of pivots a cold start needs. The initial basis of all slacks
 * {@code s} is dual feasible as well, so no phase one is ever required.
 *
 * <p>The tableau is dense ({@code m × (n + 2m)}) and updated by Gauss–Jordan pivots; it is recomputed from
 * the original data every {@value #REFACTOR_INTERVAL} pivots to contain round-off. If the method ever fails
 * to converge the solver restarts from the slack basis, and as a last resort the node is solved with the
 * {@link RebuildingRelaxationSolver}.
 *
 * <p>Instances are stateful and <strong>not</strong> thread-safe.
 */
public final class DualSimplexRelaxationSolver implements RelaxationSolver {

    private static final Logger LOGGER = LogManager.getLogger(DualSimplexRelaxationSolver.class);

    /** Pivots between two recomputations of the tableau from the original data. */
    static final int REFACTOR_INTERVAL = 500;
    private static final double PIVOT_TOL = 1e-9;
    private static final double FEAS_TOL = 1e-7;
    private static final double DUAL_TOL = 1e-9;

    private final CnfFormula formula;
    private final int n;
    private final int m;
    /** Number of columns: {@code n} variables, {@code m} slacks, {@code m} surpluses. */
    private final int cols;

    /** Original data (never modified). */
    private final double[][] a;
    private final double[] b;
    private final double[] cost;

    /** Current bounds; {@code upper} is {@code +∞} for slacks and surpluses. */
    private final double[] lower;
    private final double[] upper;

    /** Current tableau {@code B⁻¹A}, {@code β = B⁻¹b}, reduced costs and basis. */
    private final double[][] t;
    private final double[] beta;
    private final double[] d;
    private final int[] basic;
    private final boolean[] isBasic;
    private final boolean[] atUpper;
    private final double[] value;

    private long pivots;
    private int pivotsSinceRefactor;
    private long solves;
    private long coldStarts;

    private final RebuildingRelaxationSolver fallback;

    public DualSimplexRelaxationSolver(CnfFormula formula) {
        this.formula = formula;
        this.n = formula.numVariables();
        this.m = formula.numClauses();
        this.cols = n + 2 * m;

        this.a = new double[m][cols];
        this.b = new double[m];
        this.cost = new double[cols];
        int i = 0;
        for (Clause clause : formula.clauses()) {
            for (Literal lit : clause.literals()) {
                a[i][lit.index()] = lit.sign();
            }
            a[i][n + i] = 1;        // slack s_i
            a[i][n + m + i] = -1;   // surplus r_i
            b[i] = 1 - clause.countNegated();
            cost[n + i] = 1;
            i++;
        }

        this.lower = new double[cols];
        this.upper = new double[cols];
        for (int j = 0; j < cols; j++) {
            upper[j] = j < n ? 1 : Double.POSITIVE_INFINITY;
        }

        this.t = new double[m][cols];
        this.beta = new double[m];
        this.d = new double[cols];
        this.basic = new int[m];
        this.isBasic = new boolean[cols];
        this.atUpper = new boolean[cols];
        this.value = new double[cols];
        this.fallback = new RebuildingRelaxationSolver(formula, new SimplexLpSolver());
        coldStart();
    }

    /** Total number of simplex pivots performed so far. */
    public long pivots() {
        return pivots;
    }

    /** Number of relaxations solved so far. */
    public long solves() {
        return solves;
    }

    /** Number of times the method restarted from the slack basis after a failure to converge. */
    public long coldStarts() {
        return coldStarts;
    }

    @Override
    public LpSolution solve(PartialAssignment fixed) {
        if (fixed.numVariables() != n) {
            throw new IllegalArgumentException("assignment has " + fixed.numVariables() + " variables, expected " + n);
        }
        solves++;
        for (int j = 0; j < n; j++) {
            if (fixed.isFree(j)) {
                lower[j] = 0;
                upper[j] = 1;
            } else {
                lower[j] = upper[j] = fixed.value(j) ? 1 : 0;
            }
        }
        try {
            return optimize();
        } catch (LpException warm) {
            LOGGER.debug("warm start failed ({}), restarting from the slack basis", warm.getMessage());
            coldStarts++;
            coldStart();
            try {
                return optimize();
            } catch (LpException cold) {
                LOGGER.warn("dual simplex failed ({}), falling back to the rebuilding solver", cold.getMessage());
                coldStart();
                return fallback.solve(fixed);
            }
        }
    }

    private LpSolution optimize() {
        placeNonBasicVariables();
        computeBasicValues();

        int maxIterations = 50 * (m + cols) + 1000;
        for (int iteration = 0; ; iteration++) {
            int r = selectLeavingRow();
            if (r < 0) {
                break;
            }
            if (iteration >= maxIterations) {
                throw new LpException("dual simplex did not converge in " + maxIterations + " pivots", null);
            }
            boolean below = value[basic[r]] < lower[basic[r]];
            int q = ratioTest(r, below);
            if (q < 0) {
                throw new LpException("relaxation reported infeasible (cannot happen for (IP1))", null);
            }
            pivot(r, q, !below);
            if (++pivotsSinceRefactor >= REFACTOR_INTERVAL) {
                refactor();
            }
            computeBasicValues();
        }

        double objective = 0;
        for (int i = 0; i < m; i++) {
            objective += value[n + i];
        }
        return new LpSolution(objective, Arrays.copyOf(value, n));
    }

    /** Resets the tableau to the original data with the slacks as basis (dual feasible for (IP1)). */
    private void coldStart() {
        for (int i = 0; i < m; i++) {
            System.arraycopy(a[i], 0, t[i], 0, cols);
            beta[i] = b[i];
            basic[i] = n + i;
        }
        Arrays.fill(isBasic, false);
        Arrays.fill(atUpper, false);
        for (int i = 0; i < m; i++) {
            isBasic[n + i] = true;
        }
        computeReducedCosts();
        pivotsSinceRefactor = 0;
    }

    /** {@code d = c − c_B B⁻¹A}, with {@code B⁻¹A} the current tableau. */
    private void computeReducedCosts() {
        System.arraycopy(cost, 0, d, 0, cols);
        for (int i = 0; i < m; i++) {
            double cb = cost[basic[i]];
            if (cb != 0) {
                double[] row = t[i];
                for (int j = 0; j < cols; j++) {
                    d[j] -= cb * row[j];
                }
            }
        }
        for (int i = 0; i < m; i++) {
            d[basic[i]] = 0;
        }
    }

    /**
     * Puts every non-basic variable at the bound matching the sign of its reduced cost (dual feasibility).
     * Variables with a zero reduced cost keep their previous bound, which preserves the warm start.
     */
    private void placeNonBasicVariables() {
        for (int j = 0; j < cols; j++) {
            if (isBasic[j]) {
                continue;
            }
            if (lower[j] == upper[j]) {
                atUpper[j] = false;
            } else if (d[j] > DUAL_TOL) {
                atUpper[j] = false;
            } else if (d[j] < -DUAL_TOL) {
                if (upper[j] == Double.POSITIVE_INFINITY) {
                    throw new LpException("basis is not dual feasible", null);
                }
                atUpper[j] = true;
            } else if (upper[j] == Double.POSITIVE_INFINITY) {
                atUpper[j] = false;
            }
            value[j] = atUpper[j] ? upper[j] : lower[j];
        }
    }

    /** {@code x_B = β − Σ_{j non-basic} tᵢⱼ xⱼ}. */
    private void computeBasicValues() {
        int[] nonZero = new int[cols];
        int count = 0;
        for (int j = 0; j < cols; j++) {
            if (!isBasic[j] && value[j] != 0) {
                nonZero[count++] = j;
            }
        }
        for (int i = 0; i < m; i++) {
            double v = beta[i];
            double[] row = t[i];
            for (int k = 0; k < count; k++) {
                int j = nonZero[k];
                v -= row[j] * value[j];
            }
            value[basic[i]] = v;
        }
    }

    /** Row of the basic variable with the largest bound violation, or {@code -1} if primal feasible. */
    private int selectLeavingRow() {
        int best = -1;
        double worst = FEAS_TOL;
        for (int i = 0; i < m; i++) {
            int j = basic[i];
            double v = value[j];
            double violation = v < lower[j] ? lower[j] - v : v - upper[j];
            if (violation > worst) {
                worst = violation;
                best = i;
            }
        }
        return best;
    }

    /**
     * Dual ratio test on row {@code r}. When the leaving variable is below its lower bound it must increase,
     * which the non-basic {@code xⱼ} with {@code tᵣⱼ < 0} at their lower bound or {@code tᵣⱼ > 0} at their
     * upper bound can do; the symmetric conditions hold when it is above its upper bound. Among the
     * candidates, the smallest {@code |dⱼ / tᵣⱼ|} keeps the basis dual feasible; ties go to the largest
     * pivot element for numerical stability.
     */
    private int ratioTest(int r, boolean below) {
        double[] row = t[r];
        int best = -1;
        double bestRatio = Double.POSITIVE_INFINITY;
        double bestPivot = 0;
        for (int j = 0; j < cols; j++) {
            if (isBasic[j] || lower[j] == upper[j]) {
                continue;
            }
            double alpha = row[j];
            if (Math.abs(alpha) <= PIVOT_TOL) {
                continue;
            }
            boolean candidate = below
                    ? (atUpper[j] ? alpha > 0 : alpha < 0)
                    : (atUpper[j] ? alpha < 0 : alpha > 0);
            if (!candidate) {
                continue;
            }
            double ratio = Math.abs(d[j]) / Math.abs(alpha);
            if (ratio < bestRatio - 1e-12 || (ratio <= bestRatio + 1e-12 && Math.abs(alpha) > bestPivot)) {
                best = j;
                bestRatio = ratio;
                bestPivot = Math.abs(alpha);
            }
        }
        return best;
    }

    /** Gauss–Jordan pivot: {@code q} enters the basis in row {@code r}, the leaving variable goes to a bound. */
    private void pivot(int r, int q, boolean leavingToUpper) {
        int leaving = basic[r];
        double[] pivotRow = t[r];
        double piv = pivotRow[q];
        for (int j = 0; j < cols; j++) {
            pivotRow[j] /= piv;
        }
        pivotRow[q] = 1;
        beta[r] /= piv;

        for (int i = 0; i < m; i++) {
            if (i == r) {
                continue;
            }
            double[] row = t[i];
            double f = row[q];
            if (f != 0) {
                for (int j = 0; j < cols; j++) {
                    row[j] -= f * pivotRow[j];
                }
                row[q] = 0;
                beta[i] -= f * beta[r];
            }
        }
        double f = d[q];
        if (f != 0) {
            for (int j = 0; j < cols; j++) {
                d[j] -= f * pivotRow[j];
            }
        }
        d[q] = 0;

        basic[r] = q;
        isBasic[q] = true;
        isBasic[leaving] = false;
        atUpper[leaving] = leavingToUpper;
        value[leaving] = leavingToUpper ? upper[leaving] : lower[leaving];
        pivots++;
    }

    /**
     * Recomputes {@code B⁻¹A}, {@code β} and {@code d} from the original data for the current basis by
     * Gauss–Jordan elimination with partial pivoting on {@code [B | A | b]}.
     */
    private void refactor() {
        int width = m + cols + 1;
        double[][] w = new double[m][width];
        for (int i = 0; i < m; i++) {
            for (int k = 0; k < m; k++) {
                w[i][k] = a[i][basic[k]];
            }
            System.arraycopy(a[i], 0, w[i], m, cols);
            w[i][m + cols] = b[i];
        }
        for (int k = 0; k < m; k++) {
            int p = k;
            for (int i = k + 1; i < m; i++) {
                if (Math.abs(w[i][k]) > Math.abs(w[p][k])) {
                    p = i;
                }
            }
            if (Math.abs(w[p][k]) <= PIVOT_TOL) {
                throw new LpException("basis matrix is singular", null);
            }
            double[] tmp = w[k];
            w[k] = w[p];
            w[p] = tmp;
            double[] pivotRow = w[k];
            double piv = pivotRow[k];
            for (int j = 0; j < width; j++) {
                pivotRow[j] /= piv;
            }
            for (int i = 0; i < m; i++) {
                if (i != k) {
                    double f = w[i][k];
                    if (f != 0) {
                        double[] row = w[i];
                        for (int j = 0; j < width; j++) {
                            row[j] -= f * pivotRow[j];
                        }
                    }
                }
            }
        }
        for (int i = 0; i < m; i++) {
            System.arraycopy(w[i], m, t[i], 0, cols);
            beta[i] = w[i][m + cols];
        }
        computeReducedCosts();
        pivotsSinceRefactor = 0;
        LOGGER.trace("tableau refactored after {} pivots", pivots);
    }
}
