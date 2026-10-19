package ro.uaic.info.cnf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CnfFormulaTest {

    /** Example (2) of the paper. */
    static CnfFormula paperExample() {
        return CnfFormula.of(4, new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4});
    }

    @Test
    void countsVariablesAndClauses() {
        CnfFormula f = paperExample();
        assertEquals(4, f.numVariables());
        assertEquals(3, f.numClauses());
    }

    @Test
    void dropsTautologicalClauses() {
        CnfFormula f = CnfFormula.of(2, new int[] {1, -1}, new int[] {1, 2});
        assertEquals(1, f.numClauses());
        assertEquals(Clause.of(1, 2), f.clauses().get(0));
    }

    @Test
    void rejectsVariablesBeyondDeclaredCount() {
        assertThrows(IllegalArgumentException.class, () -> CnfFormula.of(2, new int[] {1, 3}));
        assertThrows(IllegalArgumentException.class, () -> new CnfFormula(-1, java.util.List.of()));
    }

    @Test
    void evaluatesAssignments() {
        CnfFormula f = paperExample();
        // The paper's witness: x1 = x2 = x3 = true, x4 = false.
        assertTrue(f.isSatisfiedBy(new boolean[] {true, true, true, false}));
        assertFalse(f.isSatisfiedBy(new boolean[] {true, true, true, true}));
        assertThrows(IllegalArgumentException.class, () -> f.isSatisfiedBy(new boolean[] {true}));
    }

    @Test
    void emptyFormulaIsTrue() {
        CnfFormula f = CnfFormula.of(3);
        assertTrue(f.isSatisfiedBy(new boolean[3]));
        assertEquals("⊤", f.toString());
    }

    @Test
    void roundTripsThroughDimacs() throws DimacsFormatException {
        CnfFormula f = paperExample();
        String dimacs = f.toDimacs();
        assertEquals("p cnf 4 3\n1 2 0\n-1 2 -3 0\n-2 -4 0\n", dimacs);
        CnfFormula parsed = new DimacsReader().read(dimacs);
        assertEquals(f.clauses(), parsed.clauses());
        assertEquals(f.numVariables(), parsed.numVariables());
    }

    @Test
    void toStringIsReadable() {
        assertEquals("(x1 ∨ x2) ∧ (¬x1 ∨ x2 ∨ ¬x3) ∧ (¬x2 ∨ ¬x4)", paperExample().toString());
    }
}
