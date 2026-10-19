package ro.uaic.info.tubb;

import java.util.Arrays;

/**
 * A partial truth assignment: every variable is either fixed to {@code false}, fixed to {@code true} or free.
 *
 * <p>Instances are immutable; {@link #fix(int, boolean)} returns a new assignment. Variables are addressed
 * by zero-based index.
 */
public final class PartialAssignment {

    private static final byte FREE = -1;
    private static final byte FALSE = 0;
    private static final byte TRUE = 1;

    private final byte[] values;
    private final int numFixed;

    private PartialAssignment(byte[] values, int numFixed) {
        this.values = values;
        this.numFixed = numFixed;
    }

    /** All variables free. */
    public static PartialAssignment empty(int numVariables) {
        byte[] values = new byte[numVariables];
        Arrays.fill(values, FREE);
        return new PartialAssignment(values, 0);
    }

    public int numVariables() {
        return values.length;
    }

    public int numFixed() {
        return numFixed;
    }

    public int numFree() {
        return values.length - numFixed;
    }

    public boolean isFree(int variable) {
        return values[variable] == FREE;
    }

    /** Value of a fixed variable. */
    public boolean value(int variable) {
        if (values[variable] == FREE) {
            throw new IllegalStateException("variable " + variable + " is free");
        }
        return values[variable] == TRUE;
    }

    /** Returns a copy with {@code variable} fixed to {@code value}. */
    public PartialAssignment fix(int variable, boolean value) {
        if (!isFree(variable)) {
            throw new IllegalStateException("variable " + variable + " is already fixed");
        }
        byte[] copy = values.clone();
        copy[variable] = value ? TRUE : FALSE;
        return new PartialAssignment(copy, numFixed + 1);
    }

    /**
     * Completes the assignment: fixed variables keep their value, free ones take the given values.
     *
     * @param freeValues values of the free variables, in increasing variable order
     */
    public boolean[] complete(boolean[] freeValues) {
        if (freeValues.length != numFree()) {
            throw new IllegalArgumentException(freeValues.length + " values given for " + numFree() + " free variables");
        }
        boolean[] full = new boolean[values.length];
        int p = 0;
        for (int v = 0; v < values.length; v++) {
            full[v] = values[v] == FREE ? freeValues[p++] : values[v] == TRUE;
        }
        return full;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        for (int v = 0; v < values.length; v++) {
            if (values[v] != FREE) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append('x').append(v + 1).append('=').append(values[v]);
            }
        }
        return sb.append('}').toString();
    }
}
