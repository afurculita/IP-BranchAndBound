package ro.uaic.info.cnf;

/**
 * A propositional literal: a variable or its complement.
 *
 * <p>Variables are numbered from 1, as in the DIMACS CNF format. The {@linkplain #index() zero-based
 * index} is used wherever the variable addresses a column of a constraint matrix or an array slot.
 *
 * @param variable the (1-based) variable number, always positive
 * @param negated  {@code true} for the complement literal {@code ¬x}, {@code false} for {@code x}
 */
public record Literal(int variable, boolean negated) {

    public Literal {
        if (variable <= 0) {
            throw new IllegalArgumentException("variable must be positive, got " + variable);
        }
    }

    /**
     * Creates a literal from its DIMACS encoding: {@code v} for {@code x_v} and {@code -v} for {@code ¬x_v}.
     *
     * @throws IllegalArgumentException if {@code dimacs} is zero
     */
    public static Literal of(int dimacs) {
        if (dimacs == 0) {
            throw new IllegalArgumentException("0 is the clause terminator, not a literal");
        }
        return new Literal(Math.abs(dimacs), dimacs < 0);
    }

    /** Positive literal {@code x_v}. */
    public static Literal pos(int variable) {
        return new Literal(variable, false);
    }

    /** Negative literal {@code ¬x_v}. */
    public static Literal neg(int variable) {
        return new Literal(variable, true);
    }

    /** Zero-based index of the variable, i.e. {@code variable - 1}. */
    public int index() {
        return variable - 1;
    }

    /** The complement of this literal. */
    public Literal negate() {
        return new Literal(variable, !negated);
    }

    /**
     * The coefficient of this literal's variable in the (IP1) constraint matrix of the paper:
     * {@code +1} for a positive literal and {@code -1} for a complement.
     */
    public int sign() {
        return negated ? -1 : 1;
    }

    /** DIMACS encoding of this literal ({@code -v} when negated, {@code v} otherwise). */
    public int toDimacs() {
        return negated ? -variable : variable;
    }

    /** Whether this literal is true under the given value of its variable. */
    public boolean isSatisfiedBy(boolean value) {
        return value != negated;
    }

    @Override
    public String toString() {
        return (negated ? "¬x" : "x") + variable;
    }
}
