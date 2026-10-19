package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class NonTuRankerTest {

    @Test
    void ranksByDecreasingDegreeAndZeroesCouplingsOfRankedColumns() {
        // w0 = 5 + 1 = 6, w1 = 5 + 2 = 7, w2 = 1 + 2 = 3, w3 = 0
        List<Coupling> couplings = List.of(
                new Coupling(new int[] {0, 1}, 5),
                new Coupling(new int[] {0, 2}, 1),
                new Coupling(new int[] {1, 2}, 2));

        // 1 first (w=7); then couplings with 1 vanish: w0 = 1, w2 = 1 -> tie broken by index: 0; then all zero.
        assertEquals(List.of(1, 0), NonTuRanker.rank(4, couplings, c -> c));
    }

    @Test
    void stopsWhenAllDegreesAreZero() {
        assertEquals(List.of(), NonTuRanker.rank(3, List.of(), c -> c));
    }

    @Test
    void mapsColumnsToVariablesAndRemovesAllColumnsOfTheRankedVariable() {
        // 2 variables, 4 columns: 0 = x1, 1 = x2, 2 = ¬x1, 3 = ¬x2.
        // Column 2 (¬x1) has the largest degree, so variable x1 is ranked. Fixing x1 removes both of its
        // columns, hence the coupling of column 0 (x1) must vanish too and nothing else gets ranked.
        List<Coupling> couplings = List.of(
                new Coupling(new int[] {2, 3}, 4),
                new Coupling(new int[] {0, 1}, 1));

        assertEquals(List.of(0), NonTuRanker.rank(4, couplings, c -> c % 2));
        // Without the column-to-variable map, column 0 or 1 would be ranked next.
        assertEquals(List.of(2, 0), NonTuRanker.rank(4, couplings, c -> c));
    }

    @Test
    void handlesTriplesLikePairs() {
        List<Coupling> couplings = List.of(
                new Coupling(new int[] {0, 1, 2}, 1),
                new Coupling(new int[] {2, 3, 4}, 3));
        // w2 = 4 -> rank 2, everything else becomes zero.
        assertEquals(List.of(2), NonTuRanker.rank(5, couplings, c -> c));
    }
}
