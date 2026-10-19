package ro.uaic.info.tubb;

import org.sat4j.core.VecInt;
import org.sat4j.minisat.SolverFactory;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.ISolver;
import org.sat4j.specs.TimeoutException;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.CnfFormula;

/** Independent satisfiability oracle (SAT4J) used to cross-check the branch-and-bound solver in tests. */
final class Sat4jOracle {

    private Sat4jOracle() {
    }

    static boolean isSatisfiable(CnfFormula formula) {
        ISolver solver = SolverFactory.newDefault();
        solver.newVar(formula.numVariables());
        solver.setExpectedNumberOfClauses(formula.numClauses());
        try {
            for (Clause clause : formula.clauses()) {
                solver.addClause(new VecInt(clause.toDimacs()));
            }
            return solver.isSatisfiable();
        } catch (ContradictionException e) {
            return false;
        } catch (TimeoutException e) {
            throw new AssertionError("SAT4J timed out", e);
        }
    }
}
