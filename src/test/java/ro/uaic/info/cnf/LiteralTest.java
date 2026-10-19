package ro.uaic.info.cnf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LiteralTest {

    @Test
    void parsesDimacsEncoding() {
        Literal pos = Literal.of(7);
        Literal neg = Literal.of(-7);

        assertEquals(7, pos.variable());
        assertFalse(pos.negated());
        assertEquals(7, neg.variable());
        assertTrue(neg.negated());
        assertEquals(7, pos.toDimacs());
        assertEquals(-7, neg.toDimacs());
    }

    @Test
    void rejectsZeroAndNonPositiveVariables() {
        assertThrows(IllegalArgumentException.class, () -> Literal.of(0));
        assertThrows(IllegalArgumentException.class, () -> new Literal(0, false));
        assertThrows(IllegalArgumentException.class, () -> new Literal(-3, true));
    }

    @Test
    void indexIsZeroBased() {
        assertEquals(0, Literal.pos(1).index());
        assertEquals(4, Literal.neg(5).index());
    }

    @Test
    void signMatchesIp1Coefficient() {
        assertEquals(1, Literal.pos(2).sign());
        assertEquals(-1, Literal.neg(2).sign());
    }

    @Test
    void negationFlipsPolarityOnly() {
        assertEquals(Literal.neg(3), Literal.pos(3).negate());
        assertEquals(Literal.pos(3), Literal.neg(3).negate());
    }

    @Test
    void satisfactionDependsOnPolarity() {
        assertTrue(Literal.pos(1).isSatisfiedBy(true));
        assertFalse(Literal.pos(1).isSatisfiedBy(false));
        assertTrue(Literal.neg(1).isSatisfiedBy(false));
        assertFalse(Literal.neg(1).isSatisfiedBy(true));
    }

    @Test
    void equalityIncludesPolarity() {
        assertEquals(Literal.of(4), Literal.pos(4));
        assertFalse(Literal.of(4).equals(Literal.of(-4)));
        assertEquals("x4", Literal.pos(4).toString());
        assertEquals("¬x4", Literal.neg(4).toString());
    }
}
