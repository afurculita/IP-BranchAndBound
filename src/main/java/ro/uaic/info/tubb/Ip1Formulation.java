package ro.uaic.info.tubb;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.Literal;

/**
 * The integer programming model (IP1) of a CNF formula (Section II of the paper):
 * <pre>
 *   (IP1)  min 1ᵀs
 *          s.t. A x + I s ≥ 1 − b,   x ∈ {0,1}ⁿ, s ∈ {0,1}ᵐ
 * </pre>
 * where {@code A} is the {@code m × n} 0-±1 matrix with {@code a_ij = +1} if clause {@code i} contains
 * {@code x_j}, {@code −1} if it contains {@code ¬x_j} and {@code 0} otherwise, and {@code b_i} is the number
 * of complement literals in clause {@code i}. The formula is satisfiable iff the optimal value is zero
 * (Theorem 1).
 *
 * <p>This class exposes the matrix for inspection and computes the pairwise non-TU degrees
 * {@code e_ij} of Section III-A, which drive the first phase of the branching order.
 */
public final class Ip1Formulation {

    private final CnfFormula formula;
    private final int n;
    private final int m;
    /** Rows in which variable j occurs positively / negatively. */
    private final BitSet[] positiveRows;
    private final BitSet[] negativeRows;

    public Ip1Formulation(CnfFormula formula) {
        this.formula = formula;
        this.n = formula.numVariables();
        this.m = formula.numClauses();
        this.positiveRows = new BitSet[n];
        this.negativeRows = new BitSet[n];
        for (int j = 0; j < n; j++) {
            positiveRows[j] = new BitSet(m);
            negativeRows[j] = new BitSet(m);
        }
        int i = 0;
        for (Clause clause : formula.clauses()) {
            for (Literal lit : clause.literals()) {
                (lit.negated() ? negativeRows : positiveRows)[lit.index()].set(i);
            }
            i++;
        }
    }

    public CnfFormula formula() {
        return formula;
    }

    /** Number of variables (columns of {@code A}). */
    public int numVariables() {
        return n;
    }

    /** Number of clauses (rows of {@code A}). */
    public int numClauses() {
        return m;
    }

    /** The constraint matrix {@code A} ({@code m × n}, entries in {0, −1, 1}). */
    public int[][] matrix() {
        int[][] a = new int[m][n];
        for (int j = 0; j < n; j++) {
            for (int i = positiveRows[j].nextSetBit(0); i >= 0; i = positiveRows[j].nextSetBit(i + 1)) {
                a[i][j] = 1;
            }
            for (int i = negativeRows[j].nextSetBit(0); i >= 0; i = negativeRows[j].nextSetBit(i + 1)) {
                a[i][j] = -1;
            }
        }
        return a;
    }

    /** The vector {@code b}: {@code b_i} is the number of complement literals of clause {@code i}. */
    public int[] complementCounts() {
        int[] b = new int[m];
        int i = 0;
        for (Clause clause : formula.clauses()) {
            b[i++] = clause.countNegated();
        }
        return b;
    }

    /**
     * Number of rows in which columns {@code i} and {@code j} have the same non-zero entries,
     * {@code (1, 1)} or {@code (−1, −1)}: {@code e¹_ij} in the paper.
     */
    public int countSameSignRows(int i, int j) {
        return intersectionSize(positiveRows[i], positiveRows[j]) + intersectionSize(negativeRows[i], negativeRows[j]);
    }

    /**
     * Number of rows in which columns {@code i} and {@code j} have opposite non-zero entries,
     * {@code (1, −1)} or {@code (−1, 1)}: {@code e²_ij} in the paper.
     */
    public int countOppositeSignRows(int i, int j) {
        return intersectionSize(positiveRows[i], negativeRows[j]) + intersectionSize(negativeRows[i], positiveRows[j]);
    }

    /**
     * The non-TU degree {@code e_ij = e¹_ij × e²_ij} of the pair of columns {@code (i, j)}.
     *
     * <p>By Observation 2, a 2×2 0-±1 submatrix is not TU iff one row has equal entries and the other
     * opposite entries, so {@code e_ij} counts the non-TU 2×2 submatrices formed by the two columns.
     */
    public int pairDegree(int i, int j) {
        return i == j ? 0 : countSameSignRows(i, j) * countOppositeSignRows(i, j);
    }

    /**
     * All pairs of columns with a positive non-TU degree.
     *
     * <p>Only pairs of variables that share at least one clause can have a positive degree, so the
     * enumeration is driven by the clauses rather than by all {@code n²} pairs.
     */
    public List<Coupling> pairCouplings() {
        BitSet[] neighbours = new BitSet[n];
        for (int j = 0; j < n; j++) {
            neighbours[j] = new BitSet(n);
        }
        for (Clause clause : formula.clauses()) {
            List<Literal> lits = clause.literals();
            for (int p = 0; p < lits.size(); p++) {
                for (int q = p + 1; q < lits.size(); q++) {
                    int a = lits.get(p).index();
                    int b = lits.get(q).index();
                    neighbours[a].set(b);
                    neighbours[b].set(a);
                }
            }
        }
        List<Coupling> couplings = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = neighbours[i].nextSetBit(i + 1); j >= 0; j = neighbours[i].nextSetBit(j + 1)) {
                int e = pairDegree(i, j);
                if (e > 0) {
                    couplings.add(new Coupling(new int[] {i, j}, e));
                }
            }
        }
        return couplings;
    }

    private static int intersectionSize(BitSet a, BitSet b) {
        BitSet copy = (BitSet) a.clone();
        copy.and(b);
        return copy.cardinality();
    }
}
