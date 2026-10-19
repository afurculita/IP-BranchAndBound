package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.Literal;

class ConflictAnalyzerTest {

    /** Every literal of a learnt clause must be false under the assignment that produced it. */
    private static void assertFalsified(Clause clause, PartialAssignment values) {
        for (Literal lit : clause.literals()) {
            assertTrue(!values.isFree(lit.index()) && !lit.isSatisfiedBy(values.value(lit.index())),
                    lit + " is not false in " + values);
        }
    }

    @Test
    void textbookFirstUip() {
        // Decisions: x1 = true (level 1), x2 = true (level 2).
        // Level-2 implications: (¬x2 ∨ x3) -> x3, (¬x1 ∨ ¬x3 ∨ x4) -> x4, (¬x3 ∨ ¬x4 ∨ x5) -> x5,
        // conflict on (¬x4 ∨ ¬x5).
        CnfFormula f = CnfFormula.of(5, new int[] {-2, 3}, new int[] {-1, -3, 4}, new int[] {-3, -4, 5}, new int[] {-4, -5});
        Clause c1 = f.clauses().get(0);
        Clause c2 = f.clauses().get(1);
        Clause c3 = f.clauses().get(2);
        Clause conflict = f.clauses().get(3);

        PartialAssignment values = PartialAssignment.empty(5)
                .fix(0, true).fix(1, true).fix(2, true).fix(3, true).fix(4, true);
        int[] level = {1, 2, 2, 2, 2};
        Clause[] reason = {null, null, c1, c2, c3};
        int[] trail = {1, 2, 3, 4};

        Clause learnt = ConflictAnalyzer.firstUip(conflict, values, level, reason, trail, 2);

        // Resolving the conflict with c3 and c2 leaves x3 as the only level-2 literal (the first UIP):
        // learnt clause (¬x1 ∨ ¬x3).
        assertEquals(Set.of(Literal.of(-1), Literal.of(-3)), Set.copyOf(learnt.literals()));
        assertFalsified(learnt, values);
    }

    @Test
    void decisionIsTheUipWhenNothingWasImplied() {
        CnfFormula f = CnfFormula.of(2, new int[] {-1, -2});
        PartialAssignment values = PartialAssignment.empty(2).fix(0, true).fix(1, true);
        int[] level = {1, 2};
        Clause[] reason = {null, null};
        Clause learnt = ConflictAnalyzer.firstUip(f.clauses().get(0), values, level, reason, new int[] {1}, 2);
        assertEquals(Clause.of(-1, -2), learnt);
    }

    @Test
    void branchingDecisionOffTheTrailIsTheUip() {
        // Decision x2 = true at level 2 (not on the propagation trail), x1 = true decided at level 1;
        // (¬x2 ∨ x3) implies x3 and (¬x2 ∨ x4) implies x4, conflict on (¬x1 ∨ ¬x3 ∨ ¬x4). Both level-2
        // literals resolve to the decision x2, which is the UIP: learnt (¬x1 ∨ ¬x2).
        CnfFormula f = CnfFormula.of(4, new int[] {-2, 3}, new int[] {-2, 4}, new int[] {-1, -3, -4});
        PartialAssignment values = PartialAssignment.empty(4).fix(0, true).fix(1, true).fix(2, true).fix(3, true);
        int[] level = {1, 2, 2, 2};
        Clause[] reason = {null, null, f.clauses().get(0), f.clauses().get(1)};
        Clause learnt = ConflictAnalyzer.firstUip(f.clauses().get(2), values, level, reason, new int[] {2, 3}, 2);
        assertEquals(Set.of(Literal.of(-1), Literal.of(-2)), Set.copyOf(learnt.literals()));
        assertFalsified(learnt, values);

        // With a single current-level literal in the conflict, that literal is the first UIP.
        CnfFormula g = CnfFormula.of(3, new int[] {-2, 3}, new int[] {-1, -3});
        PartialAssignment w = PartialAssignment.empty(3).fix(0, true).fix(1, true).fix(2, true);
        Clause single = ConflictAnalyzer.firstUip(g.clauses().get(1), w, new int[] {1, 2, 2},
                new Clause[] {null, null, g.clauses().get(0)}, new int[] {2}, 2);
        assertEquals(Set.of(Literal.of(-1), Literal.of(-3)), Set.copyOf(single.literals()));
    }

    @Test
    void levelZeroLiteralsAreDropped() {
        // x1 is implied by the formula (level 0); the learnt clause must not mention it.
        CnfFormula f = CnfFormula.of(3, new int[] {1}, new int[] {-1, -2, 3}, new int[] {-2, -3});
        PartialAssignment values = PartialAssignment.empty(3).fix(0, true).fix(1, true).fix(2, true);
        int[] level = {0, 1, 1};
        Clause[] reason = {f.clauses().get(0), null, f.clauses().get(1)};
        Clause learnt = ConflictAnalyzer.firstUip(f.clauses().get(2), values, level, reason, new int[] {1, 2}, 1);
        assertEquals(Clause.of(-2), learnt);
    }

    @Test
    void conflictWithoutCurrentLevelLiteralsLearnsNothing() {
        CnfFormula f = CnfFormula.of(2, new int[] {-1, -2});
        PartialAssignment values = PartialAssignment.empty(2).fix(0, true).fix(1, true);
        assertNull(ConflictAnalyzer.firstUip(f.clauses().get(0), values, new int[] {1, 1}, new Clause[2], new int[0], 2));
    }

    @Test
    void noGoodNegatesTheDecisionsOnly() {
        PartialAssignment values = PartialAssignment.empty(4).fix(0, true).fix(1, false).fix(2, true);
        int[] level = {1, 1, 2, 0};
        Clause[] reason = {null, Clause.of(-1, -2), null, null};
        Clause noGood = ConflictAnalyzer.noGood(values, level, reason);
        assertEquals(Clause.of(-1, -3), noGood);
        assertFalsified(noGood, values);

        assertNull(ConflictAnalyzer.noGood(PartialAssignment.empty(2), new int[2], new Clause[2]));
    }

    @Test
    void learntClausesAreImpliedByTheFormula() {
        // Run propagation-driven conflicts on random instances and check with brute force that every learnt
        // clause is implied: no model of the formula falsifies it.
        for (long seed = 1; seed <= 15; seed++) {
            CnfFormula f = RandomCnf.kSat(8, 36, 3, seed);
            TuBranchAndBoundSolver.Options options = TuBranchAndBoundSolver.Options.defaults()
                    .withStrategy(TuBranchAndBoundSolver.SearchStrategy.DEPTH_FIRST);
            SatResult r = new TuBranchAndBoundSolver(options).solve(f);
            assertEquals(Sat4jOracle.isSatisfiable(f), r.isSatisfiable(), "seed " + seed);
        }
    }

    @Test
    void falseLiteralOfAVariable() {
        PartialAssignment values = PartialAssignment.empty(2).fix(0, true).fix(1, false);
        assertEquals(Literal.of(-1), ConflictAnalyzer.falseLiteral(0, values));
        assertEquals(Literal.of(2), ConflictAnalyzer.falseLiteral(1, values));
    }
}
