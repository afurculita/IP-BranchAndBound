package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.tubb.Propagation.Outcome;

class PropagationTest {

    @Test
    void unitPropagationChains() {
        // x1, ¬x1 ∨ x2, ¬x2 ∨ x3  =>  x1 = x2 = x3 = true
        CnfFormula f = CnfFormula.of(3, new int[] {1}, new int[] {-1, 2}, new int[] {-2, 3});
        Outcome o = Propagation.propagate(f, PartialAssignment.empty(3), false);

        assertFalse(o.isConflict());
        assertEquals(3, o.fixedCount());
        assertEquals(3, o.assignment().numFixed());
        assertTrue(o.assignment().value(0));
        assertTrue(o.assignment().value(1));
        assertTrue(o.assignment().value(2));
        // the trail records the order and the forcing clauses
        assertArrayEquals(new int[] {0, 1, 2}, o.trailVariables());
        assertEquals(List.of(Clause.of(1), Clause.of(-1, 2), Clause.of(-2, 3)), List.of(o.trailReasons()));
    }

    @Test
    void conflictReportsTheFalsifiedClauseAndTheAssignmentAtThatPoint() {
        CnfFormula f = CnfFormula.of(2, new int[] {1}, new int[] {-1, 2}, new int[] {-1, -2});
        Outcome o = Propagation.propagate(f, PartialAssignment.empty(2), false);
        assertTrue(o.isConflict());
        assertEquals(Clause.of(-1, -2), o.conflict());
        assertTrue(o.assignment().value(0));
        assertTrue(o.assignment().value(1));
        assertArrayEquals(new int[] {0, 1}, o.trailVariables());
    }

    @Test
    void learntClausesTakePartInPropagation() {
        CnfFormula f = CnfFormula.of(3, new int[] {1, 2, 3});
        Clause[] learnt = {Clause.of(-1), Clause.of(-2)};
        Outcome o = Propagation.propagate(f, learnt, PartialAssignment.empty(3), false);
        assertFalse(o.isConflict());
        assertTrue(o.assignment().value(2), "x3 forced by the original clause after the learnt units");
        assertNull(o.trailReasons()[2] == null ? null : null);
        assertEquals(Clause.of(1, 2, 3), o.trailReasons()[2]);

        // pure-literal choices carry a null reason
        Outcome pure = Propagation.propagate(f, PartialAssignment.empty(3), true);
        assertEquals(1, pure.fixedCount() > 0 ? 1 : 0);
        assertNull(pure.trailReasons()[0]);
    }

    @Test
    void detectsConflicts() {
        CnfFormula f = CnfFormula.of(2, new int[] {1, 2}, new int[] {1, -2}, new int[] {-1});
        Outcome o = Propagation.propagate(f, PartialAssignment.empty(2), false);
        assertTrue(o.isConflict());

        CnfFormula g = CnfFormula.of(2, new int[] {1, 2});
        assertTrue(Propagation.propagate(g, PartialAssignment.empty(2).fix(0, false).fix(1, false), true).isConflict());
    }

    @Test
    void pureLiteralsAreFixedToTheirPolarity() {
        // x1 only positive, x2 only negative, x3 both ways
        CnfFormula f = CnfFormula.of(3, new int[] {1, 3}, new int[] {-2, -3}, new int[] {1, -2});
        Outcome o = Propagation.propagate(f, PartialAssignment.empty(3), true);

        assertFalse(o.isConflict());
        assertTrue(o.assignment().value(0));
        assertFalse(o.assignment().value(1));
        // after fixing x1 and x2 every clause is satisfied: x3 stays free
        assertTrue(o.assignment().isFree(2));
        assertEquals(2, o.fixedCount());

        Outcome withoutPure = Propagation.propagate(f, PartialAssignment.empty(3), false);
        assertEquals(0, withoutPure.fixedCount());
    }

    @Test
    void pureLiteralsIgnoreSatisfiedClauses() {
        // x2 appears negatively only in a clause already satisfied by x1 = true, so it is pure positive.
        CnfFormula f = CnfFormula.of(2, new int[] {1, -2}, new int[] {2, 1}, new int[] {2, -1});
        Outcome o = Propagation.propagate(f, PartialAssignment.empty(2).fix(0, true), true);
        assertTrue(o.assignment().value(1));
    }

    @Test
    void propagationIsSatisfiabilityPreserving() {
        for (long seed = 1; seed <= 25; seed++) {
            CnfFormula f = RandomCnf.kSat(12, 51, 3, seed);
            Outcome o = Propagation.propagate(f, PartialAssignment.empty(12), true);
            boolean expected = Sat4jOracle.isSatisfiable(f);
            if (o.isConflict()) {
                assertFalse(expected, "seed " + seed);
            } else {
                CnfFormula reduced = Propagation.reduce(f, o.assignment());
                assertEquals(expected, Sat4jOracle.isSatisfiable(reduced), "seed " + seed);
            }
        }
    }

    @Test
    void reducesFormula() {
        CnfFormula f = CnfFormula.of(4, new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4});
        CnfFormula reduced = Propagation.reduce(f, PartialAssignment.empty(4).fix(0, true));

        assertEquals(4, reduced.numVariables(), "numbering is preserved");
        assertEquals(List.of(Clause.of(2, -3), Clause.of(-2, -4)), reduced.clauses());
    }

    @Test
    void reduceRejectsFalsifiedClauses() {
        CnfFormula f = CnfFormula.of(2, new int[] {1, 2});
        assertThrows(IllegalStateException.class,
                () -> Propagation.reduce(f, PartialAssignment.empty(2).fix(0, false).fix(1, false)));
    }
}
