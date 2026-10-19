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
 * Warm-started bounded dual simplex in <em>revised</em> form for the relaxations of (IP1).
 *
 * <p>Same method as {@link DualSimplexRelaxationSolver} (equality form {@code A x + s − r = 1 − b},
 * warm start from the previous basis, no phase one), but instead of the full {@code m × (n + 2m)} tableau
 * only the basis inverse {@code B⁻¹} ({@code m × m}) is kept and the constraint matrix is stored by sparse
 * columns. Each pivot updates {@code B⁻¹} by a rank-one (product-form) update in {@code O(m²)}, the pivot
 * row {@code αᵣ = eᵣᵀB⁻¹A} is formed in {@code O(nnz(A))} and the entering column {@code B⁻¹Aq} in
 * {@code O(m · nnz(Aq))}. The inverse is recomputed from the original data every {@value #REINVERT_INTERVAL}
 * pivots to contain round-off.
 *
 * <p>Memory is {@code O(m²)} rather than {@code O(m(n + 2m))} and a pivot costs about half of a tableau
 * pivot. A sparse LU factorisation of the basis would be the next step for much larger instances.
 *
 * <p>Instances are stateful and <strong>not</strong> thread-safe.
 */
public final class RevisedDualSimplexRelaxationSolver implements RelaxationSolver {

    private static final Logger LOGGER = LogManager.getLogger(RevisedDualSimplexRelaxationSolver.class);

    /** Pivots between two recomputations of the basis inverse from the original data. */
    static final int REINVERT_INTERVAL = 500;
    private static final double PIVOT_TOL = 1e-9;
    private static final double FEAS_TOL = 1e-7;
    private static final double DUAL_TOL = 1e-9;

    private final int n;
    private final int m;
    private final int cols;

    /** Sparse columns of {@code A = [A_x | I | −I]}: rows and ±1 values. */
    private final int[][] colRows;
    private final double[][] colVals;
    private final double[] b;
    private final double[] cost;

    private final double[] lower;
    private final double[] upper;

    private final double[][] binv;
    private final double[] beta;
    private final double[] d;
    private final int[] basic;
    private final boolean[] isBasic;
    private final boolean[] atUpper;
    private final double[] value;

    /** Scratch: pivot row and entering column. */
    private final double[] alphaRow;
    private final double[] alphaCol;
    private final double[] scratchM;

    private long pivots;
    private int pivotsSinceReinvert;
    private long solves;
    private long coldStarts;

    private final RebuildingRelaxationSolver fallback;

    public RevisedDualSimplexRelaxationSolver(CnfFormula formula) {
        this.n = formula.numVariables();
        this.m = formula.numClauses();
        this.cols = n + 2 * m;

        int[] count = new int[n];
        for (Clause clause : formula.clauses()) {
            for (Literal lit : clause.literals()) {
                count[lit.index()]++;
            }
        }
        this.colRows = new int[cols][];
        this.colVals = new double[cols][];
        for (int j = 0; j < n; j++) {
            colRows[j] = new int[count[j]];
            colVals[j] = new double[count[j]];
        }
        int[] fill = new int[n];
        this.b = new double[m];
        this.cost = new double[cols];
        int i = 0;
        for (Clause clause : formula.clauses()) {
            for (Literal lit : clause.literals()) {
                int j = lit.index();
                colRows[j][fill[j]] = i;
                colVals[j][fill[j]++] = lit.sign();
            }
            colRows[n + i] = new int[] {i};
            colVals[n + i] = new double[] {1};
            colRows[n + m + i] = new int[] {i};
            colVals[n + m + i] = new double[] {-1};
            b[i] = 1 - clause.countNegated();
            cost[n + i] = 1;
            i++;
        }

        this.lower = new double[cols];
        this.upper = new double[cols];
        for (int j = 0; j < cols; j++) {
            upper[j] = j < n ? 1 : Double.POSITIVE_INFINITY;
        }

        this.binv = new double[m][m];
        this.beta = new double[m];
        this.d = new double[cols];
        this.basic = new int[m];
        this.isBasic = new boolean[cols];
        this.atUpper = new boolean[cols];
        this.value = new double[cols];
        this.alphaRow = new double[cols];
        this.alphaCol = new double[m];
        this.scratchM = new double[m];
        this.fallback = new RebuildingRelaxationSolver(formula, new SimplexLpSolver());
        coldStart();
    }

    public long pivots() {
        return pivots;
    }

    public long solves() {
        return solves;
    }

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
                LOGGER.warn("revised dual simplex failed ({}), falling back to the rebuilding solver", cold.getMessage());
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
            computePivotRow(r);
            int q = ratioTest(below);
            if (q < 0) {
                throw new LpException("relaxation reported infeasible (cannot happen for (IP1))", null);
            }
            pivot(r, q, below);
            if (++pivotsSinceReinvert >= REINVERT_INTERVAL) {
                reinvert();
            }
        }

        double objective = 0;
        for (int i = 0; i < m; i++) {
            objective += value[n + i];
        }
        return new LpSolution(objective, Arrays.copyOf(value, n));
    }

    /** Slack basis: {@code B = I}, dual feasible for (IP1). */
    private void coldStart() {
        for (int i = 0; i < m; i++) {
            Arrays.fill(binv[i], 0);
            binv[i][i] = 1;
            beta[i] = b[i];
            basic[i] = n + i;
        }
        Arrays.fill(isBasic, false);
        Arrays.fill(atUpper, false);
        for (int i = 0; i < m; i++) {
            isBasic[n + i] = true;
        }
        computeReducedCosts();
        pivotsSinceReinvert = 0;
    }

    /** {@code d = c − yᵀA} with {@code yᵀ = c_Bᵀ B⁻¹}. */
    private void computeReducedCosts() {
        double[] y = scratchM;
        Arrays.fill(y, 0);
        for (int i = 0; i < m; i++) {
            double cb = cost[basic[i]];
            if (cb != 0) {
                double[] row = binv[i];
                for (int k = 0; k < m; k++) {
                    y[k] += cb * row[k];
                }
            }
        }
        for (int j = 0; j < cols; j++) {
            double dj = cost[j];
            int[] rows = colRows[j];
            double[] vals = colVals[j];
            for (int k = 0; k < rows.length; k++) {
                dj -= y[rows[k]] * vals[k];
            }
            d[j] = dj;
        }
        for (int i = 0; i < m; i++) {
            d[basic[i]] = 0;
        }
    }

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

    /** {@code x_B = β − B⁻¹ (Σ_{j non-basic} Aⱼ xⱼ)}. */
    private void computeBasicValues() {
        double[] y = scratchM;
        Arrays.fill(y, 0);
        for (int j = 0; j < cols; j++) {
            if (isBasic[j] || value[j] == 0) {
                continue;
            }
            int[] rows = colRows[j];
            double[] vals = colVals[j];
            double xj = value[j];
            for (int k = 0; k < rows.length; k++) {
                y[rows[k]] += vals[k] * xj;
            }
        }
        for (int i = 0; i < m; i++) {
            double v = beta[i];
            double[] row = binv[i];
            for (int k = 0; k < m; k++) {
                v -= row[k] * y[k];
            }
            value[basic[i]] = v;
        }
    }

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

    /** {@code αᵣⱼ = (eᵣᵀB⁻¹) Aⱼ} for every non-basic {@code j}; zero for basic columns. */
    private void computePivotRow(int r) {
        double[] rho = binv[r];
        for (int j = 0; j < cols; j++) {
            if (isBasic[j]) {
                alphaRow[j] = 0;
                continue;
            }
            int[] rows = colRows[j];
            double[] vals = colVals[j];
            double a = 0;
            for (int k = 0; k < rows.length; k++) {
                a += rho[rows[k]] * vals[k];
            }
            alphaRow[j] = a;
        }
    }

    private int ratioTest(boolean below) {
        int best = -1;
        double bestRatio = Double.POSITIVE_INFINITY;
        double bestPivot = 0;
        for (int j = 0; j < cols; j++) {
            if (isBasic[j] || lower[j] == upper[j]) {
                continue;
            }
            double alpha = alphaRow[j];
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

    /**
     * {@code q} enters the basis in row {@code r}; the leaving variable goes to the bound it violated.
     * Updates the primal values along the ray, {@code B⁻¹} and {@code β} by a product-form update and the
     * reduced costs by the pivot row.
     */
    private void pivot(int r, int q, boolean below) {
        int leaving = basic[r];

        // entering column B⁻¹ A_q
        int[] rows = colRows[q];
        double[] vals = colVals[q];
        for (int i = 0; i < m; i++) {
            double[] row = binv[i];
            double a = 0;
            for (int k = 0; k < rows.length; k++) {
                a += row[rows[k]] * vals[k];
            }
            alphaCol[i] = a;
        }
        double piv = alphaCol[r];

        // primal step: move the leaving variable to its bound along the entering direction
        double target = below ? lower[leaving] : upper[leaving];
        double theta = (value[leaving] - target) / piv;
        for (int i = 0; i < m; i++) {
            value[basic[i]] -= alphaCol[i] * theta;
        }
        value[q] += theta;
        value[leaving] = target;

        // B⁻¹ and β
        double[] pivotRow = binv[r];
        for (int k = 0; k < m; k++) {
            pivotRow[k] /= piv;
        }
        beta[r] /= piv;
        for (int i = 0; i < m; i++) {
            if (i == r) {
                continue;
            }
            double f = alphaCol[i];
            if (f != 0) {
                double[] row = binv[i];
                for (int k = 0; k < m; k++) {
                    row[k] -= f * pivotRow[k];
                }
                beta[i] -= f * beta[r];
            }
        }

        // reduced costs: d ← d − (d_q / α_rq) α_r ; the leaving column has α_r = 1
        double f = d[q] / alphaRow[q];
        if (f != 0) {
            for (int j = 0; j < cols; j++) {
                d[j] -= f * alphaRow[j];
            }
        }
        d[q] = 0;
        d[leaving] = -f;

        basic[r] = q;
        isBasic[q] = true;
        isBasic[leaving] = false;
        atUpper[leaving] = !below;
        pivots++;
    }

    /** Recomputes {@code B⁻¹}, {@code β}, {@code d} and the primal values from the original data. */
    private void reinvert() {
        int width = 2 * m;
        double[][] w = new double[m][width];
        for (int k = 0; k < m; k++) {
            int[] rows = colRows[basic[k]];
            double[] vals = colVals[basic[k]];
            for (int e = 0; e < rows.length; e++) {
                w[rows[e]][k] = vals[e];
            }
        }
        for (int i = 0; i < m; i++) {
            w[i][m + i] = 1;
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
            System.arraycopy(w[i], m, binv[i], 0, m);
            double v = 0;
            for (int k = 0; k < m; k++) {
                v += binv[i][k] * b[k];
            }
            beta[i] = v;
        }
        computeReducedCosts();
        computeBasicValues();
        pivotsSinceReinvert = 0;
        LOGGER.trace("basis reinverted after {} pivots", pivots);
    }
}
