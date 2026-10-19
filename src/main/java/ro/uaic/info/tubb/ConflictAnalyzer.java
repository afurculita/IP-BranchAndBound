package ro.uaic.info.tubb;

import java.util.ArrayList;
import java.util.List;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.Literal;

/**
 * Derives learnt clauses from infeasible nodes, in the style of conflict-driven clause learning.
 *
 * <p>Every fixed variable of a node has a <em>decision level</em> and a <em>reason</em>: the branching
 * decision of a node opens a new level, unit-propagated variables inherit the level of the latest decision
 * and point to the clause that forced them, and pure-literal fixings are treated as decisions of their own
 * (they are choices, not implications, so each opens a level). Variables forced at level 0 are implied by
 * the formula itself and never appear in learnt clauses.
 *
 * <ul>
 *   <li>{@link #firstUip}: when propagation falsifies a clause, the conflict clause is resolved backwards
 *       along the trail of the current level until a single literal of that level remains (the first unique
 *       implication point). The result is implied by the formula, since it is obtained by resolution from
 *       formula (or previously learnt) clauses, and is asserting.</li>
 *   <li>{@link #noGood}: when the relaxation proves a node infeasible, the negation of its decisions is an
 *       implied clause.</li>
 * </ul>
 */
public final class ConflictAnalyzer {

    private ConflictAnalyzer() {
    }

    /** The literal of variable {@code v} that is false under the assignment. */
    static Literal falseLiteral(int v, PartialAssignment values) {
        return new Literal(v + 1, values.value(v));
    }

    /**
     * First-UIP conflict analysis.
     *
     * @param conflict     the falsified clause
     * @param values       the assignment at the conflict
     * @param level        decision level of every fixed variable
     * @param reason       forcing clause of every fixed variable ({@code null} for decisions)
     * @param trail        variables fixed by propagation at the node, in order (the current level is a suffix
     *                     of it); the node's branching decision itself need not be on it
     * @param currentLevel the level at which the conflict occurred
     * @return the learnt clause, or {@code null} when the conflict involves no literal of the current level
     *         (the conflict clause itself is then already known)
     */
    public static Clause firstUip(Clause conflict, PartialAssignment values, int[] level, Clause[] reason,
                                  int[] trail, int currentLevel) {
        int n = values.numVariables();
        boolean[] seen = new boolean[n];
        boolean[] resolved = new boolean[n];
        List<Literal> learnt = new ArrayList<>();
        int pending = collect(conflict, -1, seen, level, currentLevel, learnt);
        if (pending == 0) {
            return null;
        }
        for (int t = trail.length - 1; t >= 0; t--) {
            int v = trail[t];
            if (!seen[v] || level[v] != currentLevel) {
                continue;
            }
            pending--;
            if (pending == 0 || reason[v] == null) {
                learnt.add(falseLiteral(v, values));
                return new Clause(learnt);
            }
            resolved[v] = true;
            pending += collect(reason[v], v, seen, level, currentLevel, learnt);
        }
        // What remains pending is a decision of the current level that is not on the propagation trail
        // (the node's branching decision): it is the unique implication point.
        for (int v = 0; v < n; v++) {
            if (seen[v] && !resolved[v] && level[v] == currentLevel && !values.isFree(v)) {
                if (reason[v] != null) {
                    throw new IllegalStateException("implied variable x" + (v + 1) + " missing from the trail");
                }
                learnt.add(falseLiteral(v, values));
            }
        }
        return new Clause(learnt);
    }

    /** Marks the literals of {@code clause}; returns how many new current-level literals it contributed. */
    private static int collect(Clause clause, int skip, boolean[] seen, int[] level, int currentLevel, List<Literal> learnt) {
        int added = 0;
        for (Literal lit : clause.literals()) {
            int v = lit.index();
            if (v == skip || seen[v]) {
                continue;
            }
            seen[v] = true;
            if (level[v] == currentLevel) {
                added++;
            } else if (level[v] > 0) {
                learnt.add(lit);
            }
        }
        return added;
    }

    /**
     * The negation of the decisions of a node (variables with a positive level and no reason), as a clause.
     * Sound whenever the node has been proven infeasible.
     *
     * @return the clause, or {@code null} if the node has no decision (the formula is then unsatisfiable)
     */
    public static Clause noGood(PartialAssignment values, int[] level, Clause[] reason) {
        List<Literal> lits = new ArrayList<>();
        for (int v = 0; v < values.numVariables(); v++) {
            if (!values.isFree(v) && level[v] > 0 && reason[v] == null) {
                lits.add(falseLiteral(v, values));
            }
        }
        return lits.isEmpty() ? null : new Clause(lits);
    }
}
