package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ro.uaic.info.cnf.CnfFormula;

class Ip1FormulationTest {

    private static final CnfFormula PAPER = CnfFormula.of(4,
            new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4});

    @Test
    void buildsMatrixAndRightHandSideOfPaperExample() {
        // Inequalities (3) of the paper:  x1 + x2 >= 1,  -x1 + x2 - x3 >= 1 - 2,  -x2 - x4 >= 1 - 2
        Ip1Formulation ip1 = new Ip1Formulation(PAPER);

        assertEquals(4, ip1.numVariables());
        assertEquals(3, ip1.numClauses());
        assertArrayEquals(new int[][] {
                {1, 1, 0, 0},
                {-1, 1, -1, 0},
                {0, -1, 0, -1}}, ip1.matrix());
        assertArrayEquals(new int[] {0, 2, 2}, ip1.complementCounts());
    }

    @Test
    void countsSameAndOppositeSignRows() {
        Ip1Formulation ip1 = new Ip1Formulation(PAPER);

        // columns x1, x2: row 1 is (1, 1), row 2 is (-1, 1)
        assertEquals(1, ip1.countSameSignRows(0, 1));
        assertEquals(1, ip1.countOppositeSignRows(0, 1));
        assertEquals(1, ip1.pairDegree(0, 1));
        assertEquals(ip1.pairDegree(0, 1), ip1.pairDegree(1, 0));

        // columns x2, x4: row 3 is (-1, -1) only
        assertEquals(1, ip1.countSameSignRows(1, 3));
        assertEquals(0, ip1.countOppositeSignRows(1, 3));
        assertEquals(0, ip1.pairDegree(1, 3));

        assertEquals(0, ip1.pairDegree(2, 2));
    }

    @Test
    void everyNonTu2x2PatternHasDegreeOne() {
        // The 8 non-TU 2x2 0-±1 matrices of Section III-A: one row with equal signs, one with opposite signs.
        int[][][] patterns = {
                {{1, 1}, {1, -1}}, {{1, 1}, {-1, 1}}, {{-1, -1}, {1, -1}}, {{-1, -1}, {-1, 1}},
                {{1, -1}, {1, 1}}, {{-1, 1}, {1, 1}}, {{1, -1}, {-1, -1}}, {{-1, 1}, {-1, -1}}};
        for (int[][] rows : patterns) {
            Ip1Formulation ip1 = new Ip1Formulation(fromRows(rows));
            assertEquals(1, ip1.pairDegree(0, 1), "pattern " + java.util.Arrays.deepToString(rows));
        }
    }

    @Test
    void tu2x2PatternsHaveDegreeZero() {
        int[][][] patterns = {
                {{1, 1}, {1, 1}}, {{1, -1}, {1, -1}}, {{1, 0}, {1, 1}}, {{1, 1}, {0, -1}}, {{-1, -1}, {1, 1}}};
        for (int[][] rows : patterns) {
            Ip1Formulation ip1 = new Ip1Formulation(fromRows(rows));
            assertEquals(0, ip1.pairDegree(0, 1), "pattern " + java.util.Arrays.deepToString(rows));
        }
    }

    @Test
    void enumeratesOnlyPositiveCouplings() {
        List<Coupling> couplings = new Ip1Formulation(PAPER).pairCouplings();
        assertEquals(List.of(new Coupling(new int[] {0, 1}, 1)), couplings);
    }

    @Test
    void degreeMultipliesCounts() {
        // x1,x2 appear with equal signs in 2 rows and opposite signs in 3 rows: e = 2 * 3 = 6
        CnfFormula f = CnfFormula.of(2,
                new int[] {1, 2}, new int[] {-1, -2},
                new int[] {1, -2}, new int[] {-1, 2}, new int[] {1, -2});
        Ip1Formulation ip1 = new Ip1Formulation(f);
        assertEquals(6, ip1.pairDegree(0, 1));
        assertEquals(List.of(new Coupling(new int[] {0, 1}, 6)), ip1.pairCouplings());
    }

    @Test
    void couplingsAreSymmetricAndIgnoreUnrelatedVariables() {
        // x3 never shares a clause with x1 or x2
        CnfFormula f = CnfFormula.of(3, new int[] {1, 2}, new int[] {1, -2}, new int[] {3});
        List<Coupling> couplings = new Ip1Formulation(f).pairCouplings();
        assertEquals(1, couplings.size());
        assertArrayEquals(new int[] {0, 1}, couplings.get(0).columns());
        assertTrue(couplings.get(0).degree() > 0);
    }

    /** Formula with two variables whose (IP1) matrix has the given rows (entries in {0, ±1}). */
    private static CnfFormula fromRows(int[][] rows) {
        int[][] clauses = new int[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            int[] row = rows[i];
            clauses[i] = java.util.stream.IntStream.range(0, row.length)
                    .filter(j -> row[j] != 0)
                    .map(j -> row[j] * (j + 1))
                    .toArray();
        }
        return CnfFormula.of(rows[0].length, clauses);
    }
}
