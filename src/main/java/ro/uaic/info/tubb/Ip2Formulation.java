package ro.uaic.info.tubb;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.Literal;

/**
 * The integer programming model (IP2) of a (reduced) CNF formula (Section II of the paper):
 * <pre>
 *   (IP2)  min 1ᵀs
 *          s.t. A (x, x̄) + I s ≥ 1,   x + x̄ = 1,   x, x̄ ∈ {0,1}ⁿ, s ∈ {0,1}ᵐ
 * </pre>
 * where {@code A = (A⁺, A⁻)} is an {@code m × 2k} 0-1 matrix: {@code A⁺} marks the positive occurrences of
 * the {@code k} free variables and {@code A⁻} their negative occurrences. Column {@code p < k} is the literal
 * {@code x} of the {@code p}-th free variable and column {@code k + p} its complement.
 *
 * <p>The paper builds (IP2) for the variables that the (IP1) phase left unranked, and ranks them by the
 * triple non-TU degrees {@code e_ijk} of Section III-B. This class computes those degrees.
 */
public final class Ip2Formulation {

    private final CnfFormula formula;
    private final int[] freeVariables;
    private final int k;
    private final int m;
    /** Rows (clauses) in which each of the {@code 2k} columns has a 1. */
    private final BitSet[] rows;

    /**
     * @param formula       the formula
     * @param freeVariables zero-based indices of the variables kept in the reduced problem, strictly increasing
     */
    public Ip2Formulation(CnfFormula formula, int[] freeVariables) {
        this.formula = formula;
        this.freeVariables = freeVariables.clone();
        this.k = freeVariables.length;
        this.m = formula.numClauses();

        int[] position = new int[formula.numVariables()];
        java.util.Arrays.fill(position, -1);
        for (int p = 0; p < k; p++) {
            if (p > 0 && freeVariables[p] <= freeVariables[p - 1]) {
                throw new IllegalArgumentException("freeVariables must be strictly increasing");
            }
            position[freeVariables[p]] = p;
        }

        this.rows = new BitSet[2 * k];
        for (int c = 0; c < 2 * k; c++) {
            rows[c] = new BitSet(m);
        }
        int i = 0;
        for (Clause clause : formula.clauses()) {
            for (Literal lit : clause.literals()) {
                int p = position[lit.index()];
                if (p >= 0) {
                    rows[column(p, lit.negated())].set(i);
                }
            }
            i++;
        }
    }

    /** Builds the (IP2) model over all variables of the formula. */
    public Ip2Formulation(CnfFormula formula) {
        this(formula, allVariables(formula.numVariables()));
    }

    private static int[] allVariables(int n) {
        int[] all = new int[n];
        for (int j = 0; j < n; j++) {
            all[j] = j;
        }
        return all;
    }

    private int column(int position, boolean negated) {
        return negated ? k + position : position;
    }

    public CnfFormula formula() {
        return formula;
    }

    /** Number of free variables {@code k}; the matrix has {@code 2k} columns. */
    public int numFreeVariables() {
        return k;
    }

    public int numColumns() {
        return 2 * k;
    }

    public int numClauses() {
        return m;
    }

    /** The zero-based variable index that column {@code c} belongs to (both literals of a variable map to it). */
    public int variableOfColumn(int c) {
        return freeVariables[c % k];
    }

    /** Whether column {@code c} is a complement literal column. */
    public boolean isNegatedColumn(int c) {
        return c >= k;
    }

    /** The 0-1 matrix {@code A = (A⁺, A⁻)} ({@code m × 2k}). */
    public int[][] matrix() {
        int[][] a = new int[m][2 * k];
        for (int c = 0; c < 2 * k; c++) {
            for (int i = rows[c].nextSetBit(0); i >= 0; i = rows[c].nextSetBit(i + 1)) {
                a[i][c] = 1;
            }
        }
        return a;
    }

    /**
     * The non-TU degree {@code e_ijk = e¹_ijk × e²_ijk × e³_ijk} of three distinct columns, where
     * {@code e¹} counts rows equal to {@code (0,1,1)} on the three columns, {@code e²} rows equal to
     * {@code (1,0,1)} and {@code e³} rows equal to {@code (1,1,0)}.
     *
     * <p>By Observation 3, a 3×3 0-1 matrix is not TU iff every row and column has exactly one zero, so
     * {@code e_ijk} counts the non-TU 3×3 submatrices formed by the three columns.
     */
    public int tripleDegree(int i, int j, int k) {
        if (i == j || i == k || j == k) {
            return 0;
        }
        int e1 = countRows(rows[j], rows[k], rows[i]);
        if (e1 == 0) {
            return 0;
        }
        int e2 = countRows(rows[i], rows[k], rows[j]);
        if (e2 == 0) {
            return 0;
        }
        int e3 = countRows(rows[i], rows[j], rows[k]);
        return e1 * e2 * e3;
    }

    /** Number of rows with a 1 in both {@code a} and {@code b} and a 0 in {@code excluded}. */
    private static int countRows(BitSet a, BitSet b, BitSet excluded) {
        BitSet copy = (BitSet) a.clone();
        copy.and(b);
        copy.andNot(excluded);
        return copy.cardinality();
    }

    /**
     * All triples of columns with a positive non-TU degree.
     *
     * <p>{@code e_ijk > 0} requires each of the three pairs of columns to share a row, so the candidate
     * triples are the triangles of the column co-occurrence graph. For 3-SAT instances this is far smaller
     * than the {@code (2k)³} triples of the brute-force enumeration.
     */
    public List<Coupling> tripleCouplings() {
        int cols = 2 * k;
        BitSet[] neighbours = new BitSet[cols];
        for (int c = 0; c < cols; c++) {
            neighbours[c] = new BitSet(cols);
        }
        for (Clause clause : formula.clauses()) {
            List<Integer> columns = new ArrayList<>(clause.size());
            for (Literal lit : clause.literals()) {
                int c = columnOf(lit);
                if (c >= 0) {
                    columns.add(c);
                }
            }
            for (int p = 0; p < columns.size(); p++) {
                for (int q = p + 1; q < columns.size(); q++) {
                    neighbours[columns.get(p)].set(columns.get(q));
                    neighbours[columns.get(q)].set(columns.get(p));
                }
            }
        }

        List<Coupling> couplings = new ArrayList<>();
        for (int i = 0; i < cols; i++) {
            for (int j = neighbours[i].nextSetBit(i + 1); j >= 0; j = neighbours[i].nextSetBit(j + 1)) {
                BitSet common = (BitSet) neighbours[i].clone();
                common.and(neighbours[j]);
                for (int l = common.nextSetBit(j + 1); l >= 0; l = common.nextSetBit(l + 1)) {
                    int e = tripleDegree(i, j, l);
                    if (e > 0) {
                        couplings.add(new Coupling(new int[] {i, j, l}, e));
                    }
                }
            }
        }
        return couplings;
    }

    /** Column of a literal, or {@code -1} if its variable is not free. */
    private int columnOf(Literal lit) {
        int v = lit.index();
        int p = java.util.Arrays.binarySearch(freeVariables, v);
        return p < 0 ? -1 : column(p, lit.negated());
    }
}
