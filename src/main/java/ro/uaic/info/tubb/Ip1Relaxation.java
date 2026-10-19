package ro.uaic.info.tubb;

import java.util.ArrayList;
import java.util.List;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.Literal;
import ro.uaic.info.lp.LinearProgram;
import ro.uaic.info.lp.LinearProgramSolver;
import ro.uaic.info.lp.LpSolution;

/**
 * Builds and solves the linear relaxation of (IP1) at a node of the branch-and-bound tree, i.e. for the
 * sub-problem obtained by fixing some variables.
 *
 * <p>With the variables of a {@link PartialAssignment} substituted, every clause falls in one of three cases:
 * <ul>
 *   <li>a fixed literal is true: the clause is satisfied, its row is dropped;</li>
 *   <li>all literals are fixed and false: the clause is falsified, {@code s_i = 1} is forced and the
 *       sub-problem has optimal value at least 1, hence contains no satisfying assignment;</li>
 *   <li>otherwise the row {@code Σ_{free x_j ∈ C} x_j − Σ_{free ¬x_j ∈ C} x_j + s_i ≥ 1 − b'_i} is kept, where
 *       {@code b'_i} is the number of free complement literals.</li>
 * </ul>
 * The relaxation {@code min 1ᵀs, 0 ≤ x ≤ 1, s ≥ 0} is then solved by the configured
 * {@link LinearProgramSolver}. The bound {@code s ≤ 1} is implied by {@code 0 ≤ x ≤ 1} and is omitted.
 */
public final class Ip1Relaxation {

    /** Outcome of the relaxation of a node. */
    public enum Status {
        /** A clause has all of its literals fixed to false: no LP was solved, the node is infeasible. */
        FALSIFIED,
        /** The LP optimum is positive, so the integer optimum is at least 1: the node is pruned. */
        POSITIVE,
        /** The LP optimum is zero but the solution is fractional: the node must be branched on. */
        FRACTIONAL,
        /** The LP optimum is zero and integral: it is a satisfying assignment. */
        INTEGRAL
    }

    /**
     * Result of relaxing a node.
     *
     * @param status     the outcome
     * @param value      the LP optimal value ({@code 1} for {@link Status#FALSIFIED}, by convention)
     * @param assignment the satisfying assignment when {@link Status#INTEGRAL}, otherwise {@code null}
     * @param numRows    number of active (unsatisfied) clauses in the relaxation
     */
    public record Result(Status status, double value, boolean[] assignment, int numRows) {
        public Result {
            assignment = assignment == null ? null : assignment.clone();
        }

        @Override
        public boolean[] assignment() {
            return assignment == null ? null : assignment.clone();
        }
    }

    private final CnfFormula formula;
    private final LinearProgramSolver solver;
    private final double tolerance;

    /**
     * @param formula   the formula
     * @param solver    the LP solver; it must return vertex solutions (see {@link LinearProgramSolver})
     * @param tolerance values within this distance of zero (or of an integer) are considered zero (integral)
     */
    public Ip1Relaxation(CnfFormula formula, LinearProgramSolver solver, double tolerance) {
        this.formula = formula;
        this.solver = solver;
        this.tolerance = tolerance;
    }

    public Result solve(PartialAssignment fixed) {
        int n = formula.numVariables();

        // Column of each free variable in the LP, or -1 when fixed.
        int[] column = new int[n];
        int numFree = 0;
        for (int v = 0; v < n; v++) {
            column[v] = fixed.isFree(v) ? numFree++ : -1;
        }

        // Keep the clauses that are not yet satisfied; detect falsified ones.
        List<Clause> active = new ArrayList<>();
        for (Clause clause : formula.clauses()) {
            boolean satisfied = false;
            boolean hasFree = false;
            for (Literal lit : clause.literals()) {
                if (fixed.isFree(lit.index())) {
                    hasFree = true;
                } else if (lit.isSatisfiedBy(fixed.value(lit.index()))) {
                    satisfied = true;
                    break;
                }
            }
            if (satisfied) {
                continue;
            }
            if (!hasFree) {
                return new Result(Status.FALSIFIED, 1, null, 0);
            }
            active.add(clause);
        }

        if (active.isEmpty()) {
            // Every clause is already satisfied; any completion works.
            return new Result(Status.INTEGRAL, 0, fixed.complete(new boolean[numFree]), 0);
        }

        LpSolution solution = solver.minimize(buildProgram(active, column, numFree));
        int rows = active.size();
        if (solution.value() > tolerance) {
            return new Result(Status.POSITIVE, solution.value(), null, rows);
        }

        double[] point = solution.point();
        boolean[] freeValues = new boolean[numFree];
        for (int j = 0; j < numFree; j++) {
            double x = point[j];
            if (Math.abs(x - Math.rint(x)) > tolerance) {
                return new Result(Status.FRACTIONAL, solution.value(), null, rows);
            }
            freeValues[j] = Math.rint(x) == 1;
        }
        boolean[] assignment = fixed.complete(freeValues);
        if (!formula.isSatisfiedBy(assignment)) {
            // Should not happen with a sound LP solve; treat it as fractional and keep branching.
            return new Result(Status.FRACTIONAL, solution.value(), null, rows);
        }
        return new Result(Status.INTEGRAL, solution.value(), assignment, rows);
    }

    /**
     * Variables of the LP: the {@code numFree} free {@code x_j} (columns {@code 0 .. numFree−1}) followed by
     * one slack {@code s_i} per active clause.
     */
    private static LinearProgram buildProgram(List<Clause> active, int[] column, int numFree) {
        int rows = active.size();
        int vars = numFree + rows;

        double[] cost = new double[vars];
        for (int i = 0; i < rows; i++) {
            cost[numFree + i] = 1;
        }
        LinearProgram lp = new LinearProgram(cost);
        for (int j = 0; j < numFree; j++) {
            lp.upperBound(j, 1);
        }
        for (int i = 0; i < rows; i++) {
            double[] row = new double[vars];
            int negatedFree = 0;
            for (Literal lit : active.get(i).literals()) {
                int c = column[lit.index()];
                if (c >= 0) {
                    row[c] = lit.sign();
                    if (lit.negated()) {
                        negatedFree++;
                    }
                }
            }
            row[numFree + i] = 1;
            lp.addConstraint(row, LinearProgram.Relation.GEQ, 1 - negatedFree);
        }
        return lp;
    }
}
