package ro.uaic.info.tubb;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import ro.uaic.info.cnf.CnfFormula;

/**
 * The TU-based branching order {@code S} of Procedure 1 in the paper.
 *
 * <p>Variables are ranked in three groups, in this order:
 * <ol>
 *   <li>{@linkplain #ip1Ranked() by pairwise non-TU degree} of the (IP1) matrix (Steps 1–5);</li>
 *   <li>{@linkplain #ip2Ranked() by triple non-TU degree} of the (IP2) matrix of the <em>reduced</em>
 *       problem over the variables left unranked by the first phase (Steps 6–10);</li>
 *   <li>{@linkplain #unranked() the remaining variables}, for which both degrees are zero, in increasing
 *       index order ("an arbitrary branching rule", Step 5 of Algorithm 1).</li>
 * </ol>
 * {@link #order()} is the resulting permutation of {@code {0, …, n−1}}.
 */
public final class BranchingOrder {

    private static final Logger LOGGER = LogManager.getLogger(BranchingOrder.class);

    private final List<Integer> ip1Ranked;
    private final List<Integer> ip2Ranked;
    private final List<Integer> unranked;
    private final int[] order;

    private BranchingOrder(List<Integer> ip1Ranked, List<Integer> ip2Ranked, List<Integer> unranked) {
        this.ip1Ranked = List.copyOf(ip1Ranked);
        this.ip2Ranked = List.copyOf(ip2Ranked);
        this.unranked = List.copyOf(unranked);
        this.order = new int[ip1Ranked.size() + ip2Ranked.size() + unranked.size()];
        int p = 0;
        for (int v : ip1Ranked) {
            order[p++] = v;
        }
        for (int v : ip2Ranked) {
            order[p++] = v;
        }
        for (int v : unranked) {
            order[p++] = v;
        }
    }

    /** Runs Procedure 1 on the formula. */
    public static BranchingOrder compute(CnfFormula formula) {
        return compute(formula, Long.MAX_VALUE);
    }

    /**
     * Runs Procedure 1 on the formula, examining at most {@code maxTriangles} triangles in the (IP2) phase
     * (see {@link Ip2Formulation#tripleCouplings(long)}).
     */
    public static BranchingOrder compute(CnfFormula formula, long maxTriangles) {
        int n = formula.numVariables();
        boolean[] ranked = new boolean[n];

        Ip1Formulation ip1 = new Ip1Formulation(formula);
        List<Coupling> pairs = ip1.pairCouplings();
        List<Integer> ip1Ranked = NonTuRanker.rank(n, pairs, c -> c);
        ip1Ranked.forEach(v -> ranked[v] = true);
        LOGGER.debug("(IP1): {} non-TU pairs, {} variables ranked: {}", pairs.size(), ip1Ranked.size(), ip1Ranked);

        List<Integer> ip2Ranked = List.of();
        int[] free = unrankedVariables(ranked);
        if (free.length > 0) {
            Ip2Formulation ip2 = new Ip2Formulation(formula, free);
            List<Coupling> triples = ip2.tripleCouplings(maxTriangles);
            ip2Ranked = NonTuRanker.rank(ip2.numColumns(), triples, ip2::variableOfColumn);
            ip2Ranked.forEach(v -> ranked[v] = true);
            LOGGER.debug("(IP2): {} non-TU triples, {} variables ranked: {}", triples.size(), ip2Ranked.size(), ip2Ranked);
        }

        List<Integer> unranked = new ArrayList<>();
        for (int v : unrankedVariables(ranked)) {
            unranked.add(v);
        }
        LOGGER.debug("{} variables left unranked", unranked.size());
        return new BranchingOrder(ip1Ranked, ip2Ranked, unranked);
    }

    private static int[] unrankedVariables(boolean[] ranked) {
        int count = 0;
        for (boolean r : ranked) {
            if (!r) {
                count++;
            }
        }
        int[] free = new int[count];
        int p = 0;
        for (int v = 0; v < ranked.length; v++) {
            if (!ranked[v]) {
                free[p++] = v;
            }
        }
        return free;
    }

    /** Zero-based variables ranked by the (IP1) pairwise degrees, best first. */
    public List<Integer> ip1Ranked() {
        return ip1Ranked;
    }

    /** Zero-based variables ranked by the (IP2) triple degrees, best first. */
    public List<Integer> ip2Ranked() {
        return ip2Ranked;
    }

    /** Zero-based variables with no non-TU degree, in increasing order. */
    public List<Integer> unranked() {
        return unranked;
    }

    /** Number of variables ranked by a non-TU degree (first two groups). */
    public int numRanked() {
        return ip1Ranked.size() + ip2Ranked.size();
    }

    /** The complete branching order: a permutation of {@code 0 .. n−1}. */
    public int[] order() {
        return order.clone();
    }

    /** The variable to branch on at the given depth of the search tree. */
    public int variableAt(int depth) {
        return order[depth];
    }

    public int size() {
        return order.length;
    }

    @Override
    public String toString() {
        return "BranchingOrder{ip1=" + ip1Ranked + ", ip2=" + ip2Ranked + ", unranked=" + unranked + "}";
    }
}
