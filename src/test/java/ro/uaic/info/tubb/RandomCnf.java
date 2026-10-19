package ro.uaic.info.tubb;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.Literal;

/** Generator of uniform random k-SAT instances for tests. */
final class RandomCnf {

    private RandomCnf() {
    }

    /**
     * Uniform random k-SAT: every clause has {@code k} distinct variables, each negated with probability 1/2.
     */
    static CnfFormula kSat(int numVariables, int numClauses, int k, long seed) {
        Random random = new Random(seed);
        List<Clause> clauses = new ArrayList<>(numClauses);
        for (int c = 0; c < numClauses; c++) {
            List<Literal> lits = new ArrayList<>(k);
            while (lits.size() < k) {
                int v = 1 + random.nextInt(numVariables);
                if (lits.stream().noneMatch(l -> l.variable() == v)) {
                    lits.add(new Literal(v, random.nextBoolean()));
                }
            }
            clauses.add(new Clause(lits));
        }
        return new CnfFormula(numVariables, clauses);
    }
}
