package ro.uaic.info.tubb;

import java.util.ArrayList;
import java.util.List;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.Literal;

/**
 * Logical simplification of a sub-problem: unit propagation and pure-literal elimination.
 *
 * <p>Neither rule changes the satisfiability of the sub-problem, so applying them after each branching
 * decision preserves the correctness of the branch-and-bound while shrinking the relaxation (fewer free
 * columns, fewer active rows) and detecting many infeasible nodes without any LP.
 * <ul>
 *   <li><b>Unit propagation</b>: a clause whose literals are all fixed false except one forces that literal
 *       true; a clause with all literals fixed false is a conflict.</li>
 *   <li><b>Pure literals</b>: a free variable occurring with a single polarity in the clauses not yet
 *       satisfied can be fixed to make those literals true.</li>
 * </ul>
 * This class also builds the {@linkplain #reduce reduced formula} of a sub-problem, used for dynamic
 * variable ranking.
 */
public final class Propagation {

    /**
     * Result of propagating a partial assignment.
     *
     * @param assignment the extended assignment, or {@code null} when a conflict was found
     * @param fixedCount number of variables fixed by propagation
     */
    public record Outcome(PartialAssignment assignment, int fixedCount) {
        public boolean isConflict() {
            return assignment == null;
        }
    }

    private Propagation() {
    }

    /**
     * Extends {@code fixed} with every variable forced by unit propagation and (optionally) pure-literal
     * elimination, to a fixed point.
     */
    public static Outcome propagate(CnfFormula formula, PartialAssignment fixed, boolean pureLiterals) {
        byte[] v = fixed.rawValues();
        int n = v.length;
        int fixedCount = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            // unit propagation to a fixed point
            boolean unitChanged = true;
            while (unitChanged) {
                unitChanged = false;
                for (Clause clause : formula.clauses()) {
                    Literal unit = null;
                    int free = 0;
                    boolean satisfied = false;
                    for (Literal lit : clause.literals()) {
                        byte val = v[lit.index()];
                        if (val < 0) {
                            free++;
                            unit = lit;
                        } else if (lit.isSatisfiedBy(val == 1)) {
                            satisfied = true;
                            break;
                        }
                    }
                    if (satisfied) {
                        continue;
                    }
                    if (free == 0) {
                        return new Outcome(null, fixedCount);
                    }
                    if (free == 1) {
                        v[unit.index()] = (byte) (unit.negated() ? 0 : 1);
                        fixedCount++;
                        unitChanged = true;
                        changed = true;
                    }
                }
            }
            if (!pureLiterals) {
                break;
            }
            // pure literals over the clauses that are still unsatisfied
            boolean[] positive = new boolean[n];
            boolean[] negative = new boolean[n];
            for (Clause clause : formula.clauses()) {
                if (isSatisfied(clause, v)) {
                    continue;
                }
                for (Literal lit : clause.literals()) {
                    if (v[lit.index()] < 0) {
                        (lit.negated() ? negative : positive)[lit.index()] = true;
                    }
                }
            }
            for (int j = 0; j < n; j++) {
                if (v[j] < 0 && positive[j] != negative[j]) {
                    v[j] = (byte) (positive[j] ? 1 : 0);
                    fixedCount++;
                    changed = true;
                }
            }
        }
        return new Outcome(PartialAssignment.fromValues(v), fixedCount);
    }

    /**
     * The reduced formula of a sub-problem: satisfied clauses are removed and fixed (false) literals are
     * dropped from the remaining ones. Variable numbering is unchanged so that indices stay comparable.
     *
     * @throws IllegalStateException if a clause is falsified (callers propagate first)
     */
    public static CnfFormula reduce(CnfFormula formula, PartialAssignment fixed) {
        List<Clause> reduced = new ArrayList<>();
        for (Clause clause : formula.clauses()) {
            List<Literal> free = new ArrayList<>(clause.size());
            boolean satisfied = false;
            for (Literal lit : clause.literals()) {
                if (fixed.isFree(lit.index())) {
                    free.add(lit);
                } else if (lit.isSatisfiedBy(fixed.value(lit.index()))) {
                    satisfied = true;
                    break;
                }
            }
            if (satisfied) {
                continue;
            }
            if (free.isEmpty()) {
                throw new IllegalStateException("clause " + clause + " is falsified by " + fixed);
            }
            reduced.add(new Clause(free));
        }
        return new CnfFormula(formula.numVariables(), reduced);
    }

    private static boolean isSatisfied(Clause clause, byte[] v) {
        for (Literal lit : clause.literals()) {
            byte val = v[lit.index()];
            if (val >= 0 && lit.isSatisfiedBy(val == 1)) {
                return true;
            }
        }
        return false;
    }
}
