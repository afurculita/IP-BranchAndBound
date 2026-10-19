package ro.uaic.info.lp;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.math3.exception.MathIllegalStateException;
import org.apache.commons.math3.optim.MaxIter;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.linear.LinearConstraint;
import org.apache.commons.math3.optim.linear.LinearConstraintSet;
import org.apache.commons.math3.optim.linear.LinearObjectiveFunction;
import org.apache.commons.math3.optim.linear.NonNegativeConstraint;
import org.apache.commons.math3.optim.linear.Relationship;
import org.apache.commons.math3.optim.linear.SimplexSolver;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;

/**
 * {@link LinearProgramSolver} backed by the two-phase simplex method of Apache Commons Math
 * ({@link SimplexSolver}, Bland's rule).
 *
 * <p>The simplex method returns basic feasible solutions, i.e. vertices of the feasible polyhedron, which is
 * exactly what the total-unimodularity argument of the paper needs. The implementation is dense and intended
 * for the modest problem sizes of this project (hundreds of rows and columns).
 */
public final class SimplexLpSolver implements LinearProgramSolver {

    /** Default feasibility/optimality tolerance, matching {@link SimplexSolver}'s default epsilon. */
    public static final double DEFAULT_EPSILON = 1.0e-6;

    private final double epsilon;
    private final int maxIterations;

    public SimplexLpSolver() {
        this(DEFAULT_EPSILON, Integer.MAX_VALUE);
    }

    /**
     * @param epsilon       tolerance used when comparing tableau entries to zero
     * @param maxIterations maximum number of simplex pivots per solve
     */
    public SimplexLpSolver(double epsilon, int maxIterations) {
        this.epsilon = epsilon;
        this.maxIterations = maxIterations;
    }

    public double epsilon() {
        return epsilon;
    }

    @Override
    public LpSolution minimize(LinearProgram program) {
        int n = program.numVariables();
        LinearObjectiveFunction objective = new LinearObjectiveFunction(program.objective(), 0);

        List<LinearConstraint> constraints = new ArrayList<>(program.constraints().size() + n);
        for (LinearProgram.Constraint c : program.constraints()) {
            constraints.add(new LinearConstraint(c.coefficients(), relationship(c.relation()), c.rhs()));
        }
        double[] ub = program.upperBounds();
        for (int j = 0; j < n; j++) {
            if (ub[j] != Double.POSITIVE_INFINITY) {
                double[] row = new double[n];
                row[j] = 1;
                constraints.add(new LinearConstraint(row, Relationship.LEQ, ub[j]));
            }
        }

        SimplexSolver solver = new SimplexSolver(epsilon);
        try {
            PointValuePair result = solver.optimize(
                    new MaxIter(maxIterations),
                    objective,
                    new LinearConstraintSet(constraints),
                    GoalType.MINIMIZE,
                    new NonNegativeConstraint(true));
            return new LpSolution(result.getValue(), result.getPoint());
        } catch (MathIllegalStateException e) {
            throw new LpException("simplex failed: " + e.getMessage(), e);
        }
    }

    private static Relationship relationship(LinearProgram.Relation relation) {
        return switch (relation) {
            case LEQ -> Relationship.LEQ;
            case EQ -> Relationship.EQ;
            case GEQ -> Relationship.GEQ;
        };
    }
}
