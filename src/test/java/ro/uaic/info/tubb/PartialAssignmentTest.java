package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PartialAssignmentTest {

    @Test
    void startsFreeAndFixesImmutably() {
        PartialAssignment empty = PartialAssignment.empty(3);
        assertEquals(3, empty.numVariables());
        assertEquals(0, empty.numFixed());
        assertEquals(3, empty.numFree());
        assertTrue(empty.isFree(1));

        PartialAssignment fixed = empty.fix(1, true);
        assertTrue(empty.isFree(1), "original must be unchanged");
        assertFalse(fixed.isFree(1));
        assertTrue(fixed.value(1));
        assertEquals(1, fixed.numFixed());
        assertEquals("{x2=1}", fixed.toString());
    }

    @Test
    void rejectsDoubleFixAndReadingFreeValues() {
        PartialAssignment a = PartialAssignment.empty(2).fix(0, false);
        assertThrows(IllegalStateException.class, () -> a.fix(0, true));
        assertThrows(IllegalStateException.class, () -> a.value(1));
    }

    @Test
    void completesWithValuesForFreeVariables() {
        PartialAssignment a = PartialAssignment.empty(4).fix(1, true).fix(3, false);
        assertArrayEquals(new boolean[] {true, true, false, false}, a.complete(new boolean[] {true, false}));
        assertThrows(IllegalArgumentException.class, () -> a.complete(new boolean[] {true}));
    }
}
