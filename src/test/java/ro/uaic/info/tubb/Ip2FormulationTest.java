package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ro.uaic.info.cnf.CnfFormula;

class Ip2FormulationTest {

    @Test
    void buildsLiteralMatrixWithPositiveThenNegativeColumns() {
        // Paper example (2) in the form (5): columns x1 x2 x3 x4 | ¬x1 ¬x2 ¬x3 ¬x4
        CnfFormula f = CnfFormula.of(4, new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4});
        Ip2Formulation ip2 = new Ip2Formulation(f);

        assertEquals(4, ip2.numFreeVariables());
        assertEquals(8, ip2.numColumns());
        assertArrayEquals(new int[][] {
                {1, 1, 0, 0, 0, 0, 0, 0},
                {0, 1, 0, 0, 1, 0, 1, 0},
                {0, 0, 0, 0, 0, 1, 0, 1}}, ip2.matrix());
        assertEquals(0, ip2.variableOfColumn(0));
        assertEquals(0, ip2.variableOfColumn(4));
        assertEquals(3, ip2.variableOfColumn(7));
        assertFalse(ip2.isNegatedColumn(3));
        assertTrue(ip2.isNegatedColumn(4));
    }

    @Test
    void reducedProblemKeepsOnlyFreeVariables() {
        CnfFormula f = CnfFormula.of(4, new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4});
        Ip2Formulation ip2 = new Ip2Formulation(f, new int[] {1, 3}); // x2, x4

        assertEquals(4, ip2.numColumns());
        assertArrayEquals(new int[][] {
                {1, 0, 0, 0},
                {1, 0, 0, 0},
                {0, 0, 1, 1}}, ip2.matrix());
        assertEquals(1, ip2.variableOfColumn(0));
        assertEquals(3, ip2.variableOfColumn(3));
        assertThrows(IllegalArgumentException.class, () -> new Ip2Formulation(f, new int[] {3, 1}));
    }

    @Test
    void everyNonTu3x3PatternHasDegreeOne() {
        // The 6 non-TU 3x3 0-1 matrices of Section III-B: exactly one zero per row and per column.
        int[][][] patterns = {
                {{0, 1, 1}, {1, 0, 1}, {1, 1, 0}}, {{0, 1, 1}, {1, 1, 0}, {1, 0, 1}},
                {{1, 0, 1}, {0, 1, 1}, {1, 1, 0}}, {{1, 0, 1}, {1, 1, 0}, {0, 1, 1}},
                {{1, 1, 0}, {1, 0, 1}, {0, 1, 1}}, {{1, 1, 0}, {0, 1, 1}, {1, 0, 1}}};
        for (int[][] rows : patterns) {
            Ip2Formulation ip2 = new Ip2Formulation(positiveFormula(rows));
            assertEquals(1, ip2.tripleDegree(0, 1, 2), java.util.Arrays.deepToString(rows));
            assertEquals(1, ip2.tripleDegree(2, 0, 1), "degree is symmetric");
        }
    }

    @Test
    void tu3x3PatternsHaveDegreeZero() {
        int[][][] patterns = {
                {{1, 1, 1}, {1, 1, 0}, {1, 0, 1}},
                {{1, 1, 0}, {1, 1, 0}, {0, 1, 1}},
                {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}},
                {{0, 1, 1}, {1, 0, 1}, {1, 1, 1}}};
        for (int[][] rows : patterns) {
            Ip2Formulation ip2 = new Ip2Formulation(positiveFormula(rows));
            assertEquals(0, ip2.tripleDegree(0, 1, 2), java.util.Arrays.deepToString(rows));
        }
        assertEquals(0, new Ip2Formulation(positiveFormula(new int[][] {{1, 1, 1}})).tripleDegree(0, 0, 1));
    }

    @Test
    void degreeMultipliesPatternCounts() {
        // (0,1,1) twice, (1,0,1) once, (1,1,0) three times -> 2 * 1 * 3 = 6
        int[][] rows = {{0, 1, 1}, {0, 1, 1}, {1, 0, 1}, {1, 1, 0}, {1, 1, 0}, {1, 1, 0}};
        assertEquals(6, new Ip2Formulation(positiveFormula(rows)).tripleDegree(0, 1, 2));
    }

    @Test
    void enumeratesTrianglesOfTheCoOccurrenceGraphIncludingNegatedColumns() {
        // (¬x1 ∨ x2), (¬x1 ∨ x3), (x2 ∨ x3): columns ¬x1 = 3, x2 = 1, x3 = 2 form a non-TU triple.
        CnfFormula f = CnfFormula.of(3, new int[] {-1, 2}, new int[] {-1, 3}, new int[] {2, 3});
        List<Coupling> couplings = new Ip2Formulation(f).tripleCouplings();

        assertEquals(List.of(new Coupling(new int[] {1, 2, 3}, 1)), couplings);
    }

    @Test
    void noCouplingsWithoutTriangles() {
        CnfFormula f = CnfFormula.of(4, new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4});
        assertTrue(new Ip2Formulation(f).tripleCouplings().isEmpty());
    }

    @Test
    void bruteForceAgreesWithTriangleEnumeration() {
        CnfFormula f = RandomCnf.kSat(8, 30, 3, 42L);
        Ip2Formulation ip2 = new Ip2Formulation(f);
        int cols = ip2.numColumns();
        java.util.Set<Coupling> expected = new java.util.HashSet<>();
        for (int i = 0; i < cols; i++) {
            for (int j = i + 1; j < cols; j++) {
                for (int k = j + 1; k < cols; k++) {
                    int e = ip2.tripleDegree(i, j, k);
                    if (e > 0) {
                        expected.add(new Coupling(new int[] {i, j, k}, e));
                    }
                }
            }
        }
        assertFalse(expected.isEmpty(), "test instance should contain non-TU triples");
        assertEquals(expected, new java.util.HashSet<>(ip2.tripleCouplings()));
    }

    /** Formula over three variables whose clauses contain x_j positively iff rows[i][j] == 1. */
    private static CnfFormula positiveFormula(int[][] rows) {
        int[][] clauses = new int[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            int[] row = rows[i];
            clauses[i] = java.util.stream.IntStream.range(0, row.length)
                    .filter(j -> row[j] == 1)
                    .map(j -> j + 1)
                    .toArray();
        }
        return CnfFormula.of(rows[0].length, clauses);
    }
}
