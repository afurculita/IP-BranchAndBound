package ro.uaic.info.cnf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ClauseTest {

    @Test
    void keepsDistinctLiteralsInOrder() {
        Clause c = Clause.of(3, -1, 3, 2, -1);

        assertEquals(List.of(Literal.of(3), Literal.of(-1), Literal.of(2)), c.literals());
        assertEquals(3, c.size());
        assertArrayEquals(new int[] {3, -1, 2}, c.toDimacs());
    }

    @Test
    void rejectsEmptyClause() {
        assertThrows(IllegalArgumentException.class, () -> new Clause(List.of()));
    }

    @Test
    void countsComplementLiterals() {
        assertEquals(0, Clause.of(1, 2).countNegated());
        assertEquals(2, Clause.of(-1, 2, -3).countNegated());
        assertEquals(2, Clause.of(-2, -4).countNegated());
    }

    @Test
    void detectsTautologies() {
        assertTrue(Clause.of(1, -2, -1).isTautology());
        assertFalse(Clause.of(1, -2, 3).isTautology());
    }

    @Test
    void evaluatesUnderAssignment() {
        Clause c = Clause.of(-1, 2, -3);
        assertTrue(c.isSatisfiedBy(new boolean[] {false, false, true}));
        assertTrue(c.isSatisfiedBy(new boolean[] {true, true, true}));
        assertFalse(c.isSatisfiedBy(new boolean[] {true, false, true}));
    }

    @Test
    void maxVariable() {
        assertEquals(9, Clause.of(1, -9, 4).maxVariable());
    }

    @Test
    void equalityAndToString() {
        assertEquals(Clause.of(1, -2), Clause.of(1, -2, 1));
        assertEquals(Clause.of(1, -2).hashCode(), Clause.of(1, -2, 1).hashCode());
        assertEquals("(x1 ∨ ¬x2)", Clause.of(1, -2).toString());
    }
}
