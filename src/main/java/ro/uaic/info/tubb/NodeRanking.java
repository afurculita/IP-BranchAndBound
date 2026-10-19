package ro.uaic.info.tubb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.Literal;

/**
 * Per-node choice of the branching variable.
 *
 * <p>Only the <em>top-ranked</em> variable of a node is needed, so the greedy ranking loop of Procedure 1 is
 * unnecessary: the non-TU degrees are computed on the reduced problem (active clauses, free literals) and
 * their argmax taken directly. Degrees are read off the clauses: the pair degree {@code e_ij} needs the
 * numbers of active rows where {@code i, j} appear with equal and with opposite signs, which a single pass
 * over the active clauses provides. Ties, and the case where every degree is zero, are decided by the
 * two-sided Jeroslow–Wang score {@code J(v) = Σ_{active C ∋ v} 2^{−|C|}}, a classic SAT branching heuristic
 * that favours variables in many short clauses.
 */
final class NodeRanking {

    private NodeRanking() {
    }

    /** An active clause reduced to its free literals: variables and signs. */
    private record Row(int[] vars, int[] signs) {
    }

    /**
     * @param rule         {@link TuBranchAndBoundSolver.BranchingRule#DYNAMIC} or
     *                     {@link TuBranchAndBoundSolver.BranchingRule#JEROSLOW_WANG}
     * @param maxTriangles bound on the (IP2) triangle enumeration of the dynamic rule
     * @return the branching variable, or {@code -1} if no clause is active
     */
    static int choose(CnfFormula formula, PartialAssignment fixed, TuBranchAndBoundSolver.BranchingRule rule, long maxTriangles) {
        int n = formula.numVariables();
        List<Row> rows = new ArrayList<>();
        double[] jw = new double[n];
        for (Clause clause : formula.clauses()) {
            int[] vars = new int[clause.size()];
            int[] signs = new int[clause.size()];
            int free = 0;
            boolean satisfied = false;
            for (Literal lit : clause.literals()) {
                int v = lit.index();
                if (fixed.isFree(v)) {
                    vars[free] = v;
                    signs[free++] = lit.sign();
                } else if (lit.isSatisfiedBy(fixed.value(v))) {
                    satisfied = true;
                    break;
                }
            }
            if (satisfied || free == 0) {
                continue;
            }
            double weight = Math.scalb(1.0, -free);
            for (int k = 0; k < free; k++) {
                jw[vars[k]] += weight;
            }
            rows.add(new Row(java.util.Arrays.copyOf(vars, free), java.util.Arrays.copyOf(signs, free)));
        }
        if (rows.isEmpty()) {
            return -1;
        }
        if (rule == TuBranchAndBoundSolver.BranchingRule.DYNAMIC) {
            int byPairs = argmaxPairDegree(n, rows, jw);
            if (byPairs >= 0) {
                return byPairs;
            }
            int byTriples = argmaxTripleDegree(formula, fixed, jw, maxTriangles);
            if (byTriples >= 0) {
                return byTriples;
            }
        }
        return argmax(jw, null, fixed);
    }

    /** Variable with the largest (IP1) pair degree {@code w_i = Σ_j e¹_ij e²_ij}, or {@code -1} if all are zero. */
    private static int argmaxPairDegree(int n, List<Row> rows, double[] jw) {
        Map<Long, int[]> pairs = new HashMap<>();
        for (Row row : rows) {
            for (int p = 0; p < row.vars().length; p++) {
                for (int q = p + 1; q < row.vars().length; q++) {
                    int a = Math.min(row.vars()[p], row.vars()[q]);
                    int b = Math.max(row.vars()[p], row.vars()[q]);
                    int[] counts = pairs.computeIfAbsent(((long) a << 32) | b, k -> new int[2]);
                    counts[row.signs()[p] == row.signs()[q] ? 0 : 1]++;
                }
            }
        }
        long[] w = new long[n];
        boolean any = false;
        for (Map.Entry<Long, int[]> e : pairs.entrySet()) {
            long degree = (long) e.getValue()[0] * e.getValue()[1];
            if (degree > 0) {
                any = true;
                w[(int) (e.getKey() >>> 32)] += degree;
                w[(int) (e.getKey() & 0xFFFFFFFFL)] += degree;
            }
        }
        return any ? argmax(toDouble(w), jw, null) : -1;
    }

    /** Variable whose literal column has the largest (IP2) triple degree, or {@code -1} if all are zero. */
    private static int argmaxTripleDegree(CnfFormula formula, PartialAssignment fixed, double[] jw, long maxTriangles) {
        int n = formula.numVariables();
        int[] free = new int[fixed.numFree()];
        int p = 0;
        for (int v = 0; v < n; v++) {
            if (fixed.isFree(v)) {
                free[p++] = v;
            }
        }
        Ip2Formulation ip2 = new Ip2Formulation(Propagation.reduce(formula, fixed), free);
        List<Coupling> triples = ip2.tripleCouplings(maxTriangles);
        if (triples.isEmpty()) {
            return -1;
        }
        double[] w = new double[n];
        for (Coupling c : triples) {
            for (int col : c.columns()) {
                w[ip2.variableOfColumn(col)] += c.degree();
            }
        }
        return argmax(w, jw, null);
    }

    /** Index of the largest positive score; ties broken by {@code tieBreak} (if given), then by index. */
    private static int argmax(double[] score, double[] tieBreak, PartialAssignment onlyFree) {
        int best = -1;
        for (int v = 0; v < score.length; v++) {
            if (score[v] <= 0 || (onlyFree != null && !onlyFree.isFree(v))) {
                continue;
            }
            if (best < 0 || score[v] > score[best]
                    || (score[v] == score[best] && tieBreak != null && tieBreak[v] > tieBreak[best])) {
                best = v;
            }
        }
        return best;
    }

    private static double[] toDouble(long[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i];
        }
        return out;
    }
}
