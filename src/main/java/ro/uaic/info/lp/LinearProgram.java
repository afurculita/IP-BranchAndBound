package ro.uaic.info.lp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A linear program in the form
 * <pre>
 *   minimise  cᵀx
 *   subject to  aᵢᵀx (≤ | = | ≥) bᵢ   for every constraint i
 *               0 ≤ x ≤ u              (u may be +∞ componentwise)
 * </pre>
 * All variables are non-negative. Constraints are stored densely; the problems built by this project are
 * small (a few hundred rows and columns), so a dense representation keeps the code simple.
 */
public final class LinearProgram {

    /** Relation between the left-hand side and the right-hand side of a constraint. */
    public enum Relation { LEQ, EQ, GEQ }

    /** A single linear constraint {@code coefficientsᵀx relation rhs}. */
    public record Constraint(double[] coefficients, Relation relation, double rhs) {
        public Constraint {
            coefficients = coefficients.clone();
        }

        @Override
        public double[] coefficients() {
            return coefficients.clone();
        }
    }

    private final int numVariables;
    private final double[] objective;
    private final double[] upperBounds;
    private final List<Constraint> constraints = new ArrayList<>();

    /**
     * @param objective the cost vector {@code c}; its length defines the number of variables
     */
    public LinearProgram(double[] objective) {
        this.numVariables = objective.length;
        this.objective = objective.clone();
        this.upperBounds = new double[numVariables];
        java.util.Arrays.fill(upperBounds, Double.POSITIVE_INFINITY);
    }

    public int numVariables() {
        return numVariables;
    }

    public double[] objective() {
        return objective.clone();
    }

    /** Upper bound of each variable; {@link Double#POSITIVE_INFINITY} when unbounded above. */
    public double[] upperBounds() {
        return upperBounds.clone();
    }

    public List<Constraint> constraints() {
        return Collections.unmodifiableList(constraints);
    }

    /** Sets {@code x[variable] ≤ bound}. */
    public LinearProgram upperBound(int variable, double bound) {
        checkVariable(variable);
        if (bound < 0) {
            throw new IllegalArgumentException("upper bound must be non-negative, got " + bound);
        }
        upperBounds[variable] = bound;
        return this;
    }

    /** Adds the constraint {@code coefficientsᵀx relation rhs}. */
    public LinearProgram addConstraint(double[] coefficients, Relation relation, double rhs) {
        if (coefficients.length != numVariables) {
            throw new IllegalArgumentException(
                    "constraint has " + coefficients.length + " coefficients, expected " + numVariables);
        }
        constraints.add(new Constraint(coefficients, relation, rhs));
        return this;
    }

    private void checkVariable(int variable) {
        if (variable < 0 || variable >= numVariables) {
            throw new IndexOutOfBoundsException("variable " + variable + " out of range [0, " + numVariables + ")");
        }
    }
}
