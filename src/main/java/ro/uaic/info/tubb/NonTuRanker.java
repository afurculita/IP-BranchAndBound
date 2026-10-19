package ro.uaic.info.tubb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Greedy ranking of variables by non-TU degree (Steps 2–5 and 7–10 of Procedure 1 in the paper).
 *
 * <p>Given the positive {@linkplain Coupling couplings} of a constraint matrix, each column {@code i} gets
 * the degree {@code w_i = Σ e} over the couplings it belongs to. The column with the largest positive degree
 * is ranked next; all couplings involving it (and, for (IP2), involving the other column of the same
 * variable) are zeroed, the degrees are recomputed and the process repeats until every remaining degree is
 * zero.
 */
final class NonTuRanker {

    private NonTuRanker() {
    }

    /**
     * @param numColumns   number of columns of the matrix
     * @param couplings    the positive couplings between columns
     * @param columnToItem maps a column to the variable (item) it belongs to; the identity for (IP1), and
     *                     the literal-to-variable map for (IP2)
     * @return the ranked variables in ranking order (highest degree first); variables whose degree is zero
     *         from the start, or becomes zero, are not included
     */
    static List<Integer> rank(int numColumns, List<Coupling> couplings, IntUnaryOperator columnToItem) {
        long[] degree = new long[numColumns];
        List<List<Integer>> incident = new ArrayList<>(numColumns);
        Map<Integer, List<Integer>> itemColumns = new HashMap<>();
        for (int c = 0; c < numColumns; c++) {
            incident.add(new ArrayList<>());
            itemColumns.computeIfAbsent(columnToItem.applyAsInt(c), x -> new ArrayList<>()).add(c);
        }
        for (int idx = 0; idx < couplings.size(); idx++) {
            Coupling coupling = couplings.get(idx);
            for (int c : coupling.columns()) {
                degree[c] += coupling.degree();
                incident.get(c).add(idx);
            }
        }

        boolean[] removed = new boolean[couplings.size()];
        List<Integer> ranked = new ArrayList<>();
        while (true) {
            int best = -1;
            long bestDegree = 0;
            for (int c = 0; c < numColumns; c++) {
                if (degree[c] > bestDegree) {
                    bestDegree = degree[c];
                    best = c;
                }
            }
            if (best < 0) {
                return ranked;
            }
            int item = columnToItem.applyAsInt(best);
            ranked.add(item);
            for (int c : itemColumns.get(item)) {
                for (int idx : incident.get(c)) {
                    if (!removed[idx]) {
                        removed[idx] = true;
                        Coupling coupling = couplings.get(idx);
                        for (int c2 : coupling.columns()) {
                            degree[c2] -= coupling.degree();
                        }
                    }
                }
            }
        }
    }
}
