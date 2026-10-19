package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ro.uaic.info.cnf.CnfFormula;

class BranchingOrderTest {

    @Test
    void paperExample() {
        // e_12 = 1 is the only non-TU pair; x1 wins the tie on index. The reduced (IP2) over x2, x3, x4 has no
        // non-TU triple, so the remaining variables keep their natural order.
        CnfFormula f = CnfFormula.of(4, new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4});
        BranchingOrder order = BranchingOrder.compute(f);

        assertEquals(List.of(0), order.ip1Ranked());
        assertEquals(List.of(), order.ip2Ranked());
        assertEquals(List.of(1, 2, 3), order.unranked());
        assertArrayEquals(new int[] {0, 1, 2, 3}, order.order());
        assertEquals(1, order.numRanked());
        assertEquals(4, order.size());
        assertEquals(0, order.variableAt(0));
    }

    @Test
    void ip2PhaseRanksVariablesLeftByIp1() {
        // No non-TU pair (every pair of variables shares exactly one clause), but x1, x2, x3 form a non-TU
        // triple in (IP2); x4 is unrelated.
        CnfFormula f = CnfFormula.of(4, new int[] {2, 3}, new int[] {1, 3}, new int[] {1, 2}, new int[] {4});
        BranchingOrder order = BranchingOrder.compute(f);

        assertEquals(List.of(), order.ip1Ranked());
        assertEquals(List.of(0), order.ip2Ranked());
        assertEquals(List.of(1, 2, 3), order.unranked());
    }

    @Test
    void orderIsAlwaysAPermutation() {
        for (long seed = 1; seed <= 20; seed++) {
            CnfFormula f = RandomCnf.kSat(15, 64, 3, seed);
            BranchingOrder order = BranchingOrder.compute(f);
            int[] sorted = order.order().clone();
            java.util.Arrays.sort(sorted);
            assertArrayEquals(IntStream.range(0, 15).toArray(), sorted, "seed " + seed);
            assertEquals(15, order.ip1Ranked().size() + order.ip2Ranked().size() + order.unranked().size());
        }
    }

    @Test
    void randomThreeSatRanksMostVariablesByNonTuDegree() {
        CnfFormula f = RandomCnf.kSat(30, 128, 3, 7L);
        BranchingOrder order = BranchingOrder.compute(f);
        assertTrue(order.numRanked() > 0, order.toString());
    }

    @Test
    void boundedTriangleEnumerationStillYieldsAPermutation() {
        CnfFormula f = RandomCnf.kSat(15, 64, 3, 3L);
        BranchingOrder order = BranchingOrder.compute(f, 2);
        int[] sorted = order.order().clone();
        java.util.Arrays.sort(sorted);
        assertArrayEquals(IntStream.range(0, 15).toArray(), sorted);
    }

    @Test
    void emptyFormula() {
        BranchingOrder order = BranchingOrder.compute(CnfFormula.of(3));
        assertArrayEquals(new int[] {0, 1, 2}, order.order());
        assertEquals(0, order.numRanked());
    }
}
