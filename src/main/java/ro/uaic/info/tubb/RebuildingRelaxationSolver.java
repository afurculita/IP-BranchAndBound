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
 * {@link RelaxationSolver} that builds the LP of the sub-problem from scratch at every node and solves it
 * with a general-purpose {@link LinearProgramSolver}.
 *
 * <p>Fixed variables are substituted: clauses satisfied by a fixed literal are dropped, and the remaining
 * rows read {@code Σ_free ±xⱼ + sᵢ ≥ 1 − (#free complement literals)}. The variables of the LP are the free
 * {@code xⱼ} followed by one slack per active clause. The bound {@code s ≤ 1} is implied by {@code 0 ≤ x ≤ 1}
 * and omitted.
 */
public final class RebuildingRelaxationSolver implements RelaxationSolver {

    private final CnfFormula formula;
    private final LinearProgramSolver solver;

    public RebuildingRelaxationSolver(CnfFormula formula, LinearProgramSolver solver) {
        this.formula = formula;
        this.solver = solver;
    }

    @Override
    public LpSolution solve(PartialAssignment fixed) {
        int n = formula.numVariables();
        int[] column = new int[n];
        int numFree = 0;
        for (int v = 0; v < n; v++) {
            column[v] = fixed.isFree(v) ? numFree++ : -1;
        }

        List<Clause> active = new ArrayList<>();
        for (Clause clause : formula.clauses()) {
            boolean satisfied = false;
            for (Literal lit : clause.literals()) {
                if (!fixed.isFree(lit.index()) && lit.isSatisfiedBy(fixed.value(lit.index()))) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                active.add(clause);
            }
        }

        double[] x = new double[n];
        for (int v = 0; v < n; v++) {
            if (!fixed.isFree(v)) {
                x[v] = fixed.value(v) ? 1 : 0;
            }
        }
        if (active.isEmpty()) {
            return new LpSolution(0, x);
        }

        LpSolution lp = solver.minimize(buildProgram(active, column, numFree));
        double[] point = lp.point();
        for (int v = 0; v < n; v++) {
            if (column[v] >= 0) {
                x[v] = point[column[v]];
            }
        }
        return new LpSolution(lp.value(), x);
    }

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
