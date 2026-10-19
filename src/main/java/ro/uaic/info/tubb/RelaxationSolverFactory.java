package ro.uaic.info.tubb;

import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.lp.LinearProgramSolver;
import ro.uaic.info.lp.SimplexLpSolver;

/** Creates {@link RelaxationSolver}s for a formula; one is created per worker thread. */
@FunctionalInterface
public interface RelaxationSolverFactory {

    RelaxationSolver create(CnfFormula formula);

    /**
     * Warm-started bounded dual simplex ({@link DualSimplexRelaxationSolver}): the default, one to two
     * orders of magnitude faster than rebuilding the LP at every node.
     */
    static RelaxationSolverFactory dualSimplex() {
        return DualSimplexRelaxationSolver::new;
    }

    /**
     * Rebuilds the LP of the active clauses at every node and hands it to a general-purpose
     * {@link LinearProgramSolver}. Slow, but any vertex-returning LP solver can be plugged in.
     */
    static RelaxationSolverFactory rebuilding(LinearProgramSolver lpSolver) {
        return formula -> new RebuildingRelaxationSolver(formula, lpSolver);
    }

    /** {@link #rebuilding} with the Apache Commons Math simplex. */
    static RelaxationSolverFactory commonsMathSimplex() {
        return rebuilding(new SimplexLpSolver());
    }
}
