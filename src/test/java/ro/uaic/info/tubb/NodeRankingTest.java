package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.tubb.TuBranchAndBoundSolver.BranchingRule;

class NodeRankingTest {

    @Test
    void dynamicRuleFollowsThePairDegreeOnTheRoot() {
        // Paper example: e_12 = 1 is the only non-TU pair; w1 = w2 = 1, tie broken by Jeroslow–Wang
        // (x2 occurs in three clauses, x1 in two).
        CnfFormula f = CnfFormula.of(4, new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4});
        assertEquals(1, NodeRanking.choose(f, PartialAssignment.empty(4), BranchingRule.DYNAMIC, Long.MAX_VALUE));
    }

    @Test
    void dynamicRuleUsesTheReducedProblem() {
        // With x1 = true the first clause is satisfied and x1 leaves the second one: no non-TU pair is left,
        // and x2 (two short clauses) beats x3 and x4 on the Jeroslow–Wang score.
        CnfFormula f = CnfFormula.of(4, new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4});
        assertEquals(1, NodeRanking.choose(f, PartialAssignment.empty(4).fix(0, true), BranchingRule.DYNAMIC, Long.MAX_VALUE));
    }

    @Test
    void dynamicRuleFallsBackToTripleDegrees() {
        // No non-TU pair, but x1, x2, x3 span a non-TU triple in (IP2); x4 only occurs in a unit clause.
        CnfFormula f = CnfFormula.of(4, new int[] {2, 3}, new int[] {1, 3}, new int[] {1, 2}, new int[] {4});
        int chosen = NodeRanking.choose(f, PartialAssignment.empty(4), BranchingRule.DYNAMIC, Long.MAX_VALUE);
        assertTrue(chosen >= 0 && chosen <= 2, "chose x" + (chosen + 1));
    }

    @Test
    void jeroslowWangWeightsOccurrencesByClauseLength() {
        // J(x1) = 1/8 + 1/8, J(x2) = 1/4 + 1/8, J(x3) = 3 * 1/8, J(x4) = 1/8 + 1/8 + 1/4 + 1/8 = 5/8: x4 wins.
        CnfFormula f = CnfFormula.of(4, new int[] {1, 3, 4}, new int[] {1, -3, -4}, new int[] {2, 4}, new int[] {2, -3, 4});
        assertEquals(3, NodeRanking.choose(f, PartialAssignment.empty(4), BranchingRule.JEROSLOW_WANG, Long.MAX_VALUE));

        // one occurrence in a binary clause (1/4) beats two occurrences in 4-clauses (2/16)
        CnfFormula g = CnfFormula.of(5, new int[] {1, 2}, new int[] {3, 4, 5, -1}, new int[] {3, -4, 5, 2}, new int[] {-3, 4, -5, 1});
        assertEquals(0, NodeRanking.choose(g, PartialAssignment.empty(5), BranchingRule.JEROSLOW_WANG, Long.MAX_VALUE));
    }

    @Test
    void returnsMinusOneWhenEveryClauseIsSatisfied() {
        CnfFormula f = CnfFormula.of(2, new int[] {1, 2});
        assertEquals(-1, NodeRanking.choose(f, PartialAssignment.empty(2).fix(0, true), BranchingRule.DYNAMIC, Long.MAX_VALUE));
    }

    @Test
    void neverChoosesAFixedVariable() {
        for (long seed = 1; seed <= 10; seed++) {
            CnfFormula f = RandomCnf.kSat(10, 42, 3, seed);
            PartialAssignment fixed = PartialAssignment.empty(10).fix(0, true).fix(3, false).fix(7, true);
            for (BranchingRule rule : new BranchingRule[] {BranchingRule.DYNAMIC, BranchingRule.JEROSLOW_WANG}) {
                int v = NodeRanking.choose(f, fixed, rule, Long.MAX_VALUE);
                assertTrue(v < 0 || fixed.isFree(v), rule + " chose fixed x" + (v + 1));
            }
        }
    }
}
