package ro.uaic.info.lp;

/**
 * The optimal solution of a {@link LinearProgram}.
 *
 * @param value the optimal objective value
 * @param point the optimal point, indexed like the variables of the program
 */
public record LpSolution(double value, double[] point) {

    public LpSolution {
        point = point.clone();
    }

    @Override
    public double[] point() {
        return point.clone();
    }

    /** Whether every coordinate of the optimal point is within {@code tolerance} of an integer. */
    public boolean isIntegral(double tolerance) {
        for (double v : point) {
            if (Math.abs(v - Math.rint(v)) > tolerance) {
                return false;
            }
        }
        return true;
    }
}
