package ro.uaic.info.tubb;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.Literal;
import ro.uaic.info.lp.LpSolution;

/**
 * Evaluates a node of the branch-and-bound tree through the linear relaxation of (IP1).
 *
 * <p>With the variables of a {@link PartialAssignment} substituted, every clause falls in one of three cases:
 * <ul>
 *   <li>a fixed literal is true: the clause is satisfied and drops out;</li>
 *   <li>all literals are fixed and false: the clause is falsified, {@code sᵢ = 1} is forced and the
 *       sub-problem has optimal value at least 1, hence contains no satisfying assignment;</li>
 *   <li>otherwise the row stays in the relaxation.</li>
 * </ul>
 * The relaxation itself is solved by a {@link RelaxationSolver}; this class classifies its outcome
 * ({@link Status}) and turns an integral zero-valued solution into a verified satisfying assignment.
 *
 * <p>Instances are as thread-safe as their {@link RelaxationSolver}; the search creates one per worker.
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
     * @param lpSolved   whether an LP was actually solved
     */
    public record Result(Status status, double value, boolean[] assignment, int numRows, boolean lpSolved) {
        public Result {
            assignment = assignment == null ? null : assignment.clone();
        }

        @Override
        public boolean[] assignment() {
            return assignment == null ? null : assignment.clone();
        }
    }

    private final CnfFormula formula;
    private final RelaxationSolver solver;
    private final double tolerance;

    /**
     * @param formula   the formula
     * @param solver    the relaxation solver; it must return vertex solutions
     * @param tolerance values within this distance of zero (or of an integer) are considered zero (integral)
     */
    public Ip1Relaxation(CnfFormula formula, RelaxationSolver solver, double tolerance) {
        this.formula = formula;
        this.solver = solver;
        this.tolerance = tolerance;
    }

    public Result solve(PartialAssignment fixed) {
        int rows = 0;
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
                return new Result(Status.FALSIFIED, 1, null, 0, false);
            }
            rows++;
        }
        if (rows == 0) {
            // Every clause is already satisfied; any completion works.
            return new Result(Status.INTEGRAL, 0, fixed.complete(new boolean[fixed.numFree()]), 0, false);
        }

        LpSolution solution = solver.solve(fixed);
        if (solution.value() > tolerance) {
            return new Result(Status.POSITIVE, solution.value(), null, rows, true);
        }

        double[] x = solution.point();
        boolean[] assignment = new boolean[formula.numVariables()];
        for (int j = 0; j < x.length; j++) {
            if (!fixed.isFree(j)) {
                assignment[j] = fixed.value(j);
                continue;
            }
            if (Math.abs(x[j] - Math.rint(x[j])) > tolerance) {
                return new Result(Status.FRACTIONAL, solution.value(), null, rows, true);
            }
            assignment[j] = Math.rint(x[j]) == 1;
        }
        if (!formula.isSatisfiedBy(assignment)) {
            // Should not happen with a sound LP solve; treat it as fractional and keep branching.
            return new Result(Status.FRACTIONAL, solution.value(), null, rows, true);
        }
        return new Result(Status.INTEGRAL, solution.value(), assignment, rows, true);
    }
}
