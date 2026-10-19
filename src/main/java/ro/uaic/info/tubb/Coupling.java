package ro.uaic.info.tubb;

import java.util.Arrays;

/**
 * A positive non-TU degree contributed by a small set of columns of a constraint matrix.
 *
 * <p>In the paper this is {@code e_ij = e¹_ij × e²_ij} for a pair of columns of the (IP1) matrix (Section
 * III-A) and {@code e_ijk = e¹_ijk × e²_ijk × e³_ijk} for a triple of columns of the (IP2) matrix (Section
 * III-B). Couplings with a zero degree are never materialised.
 *
 * @param columns the distinct column indices involved, in increasing order
 * @param degree  the (positive) non-TU degree
 */
public record Coupling(int[] columns, int degree) {

    public Coupling {
        if (degree <= 0) {
            throw new IllegalArgumentException("degree must be positive, got " + degree);
        }
        columns = columns.clone();
    }

    @Override
    public int[] columns() {
        return columns.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Coupling c && degree == c.degree && Arrays.equals(columns, c.columns);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(columns) + degree;
    }

    @Override
    public String toString() {
        return "e" + Arrays.toString(columns) + "=" + degree;
    }
}
