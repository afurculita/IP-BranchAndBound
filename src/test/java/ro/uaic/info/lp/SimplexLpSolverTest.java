package ro.uaic.info.lp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ro.uaic.info.lp.LinearProgram.Relation;

class SimplexLpSolverTest {

    private static final double EPS = 1e-9;

    private final SimplexLpSolver solver = new SimplexLpSolver();

    @Test
    void solvesSmallProgram() {
        // min x + y  s.t. x + 2y >= 2, 3x + y >= 3, x, y >= 0   -> optimum at (4/5, 3/5), value 7/5
        LinearProgram lp = new LinearProgram(new double[] {1, 1})
                .addConstraint(new double[] {1, 2}, Relation.GEQ, 2)
                .addConstraint(new double[] {3, 1}, Relation.GEQ, 3);

        LpSolution s = solver.minimize(lp);

        assertEquals(1.4, s.value(), EPS);
        assertEquals(0.8, s.point()[0], EPS);
        assertEquals(0.6, s.point()[1], EPS);
    }

    @Test
    void honoursUpperBounds() {
        // min -x - y  s.t. x <= 1, y <= 0.5   -> (1, 0.5), value -1.5
        LinearProgram lp = new LinearProgram(new double[] {-1, -1}).upperBound(0, 1).upperBound(1, 0.5);

        LpSolution s = solver.minimize(lp);

        assertEquals(-1.5, s.value(), EPS);
        assertEquals(1, s.point()[0], EPS);
        assertEquals(0.5, s.point()[1], EPS);
    }

    @Test
    void returnsVertexSolutions() {
        // The optimal face of  min 0  s.t. x + y >= 1, x, y <= 1  is a polygon; a simplex solver must return
        // one of its vertices, never an interior point such as (0.5, 0.5) or (0.75, 0.75).
        LinearProgram lp = new LinearProgram(new double[] {0, 0})
                .addConstraint(new double[] {1, 1}, Relation.GEQ, 1)
                .upperBound(0, 1)
                .upperBound(1, 1);

        LpSolution s = solver.minimize(lp);

        double[] p = s.point();
        boolean vertex = (isInt(p[0]) && isInt(p[1])) // (1,0), (0,1), (1,1)
                || (isInt(p[0]) && Math.abs(p[0] + p[1] - 1) < EPS)
                || (isInt(p[1]) && Math.abs(p[0] + p[1] - 1) < EPS);
        assertTrue(vertex, "not a vertex: " + p[0] + ", " + p[1]);
    }

    @Test
    void integralityCheck() {
        assertTrue(new LpSolution(0, new double[] {1, 0, 1 + 1e-9}).isIntegral(1e-6));
        assertFalse(new LpSolution(0, new double[] {1, 0.5}).isIntegral(1e-6));
    }

    @Test
    void handlesEqualityConstraints() {
        // min x  s.t. x + y = 1, y <= 0.25   -> x = 0.75
        LinearProgram lp = new LinearProgram(new double[] {1, 0})
                .addConstraint(new double[] {1, 1}, Relation.EQ, 1)
                .upperBound(1, 0.25);

        assertEquals(0.75, solver.minimize(lp).value(), EPS);
    }

    @Test
    void reportsInfeasibleAndUnboundedPrograms() {
        LinearProgram infeasible = new LinearProgram(new double[] {1})
                .addConstraint(new double[] {1}, Relation.GEQ, 2)
                .upperBound(0, 1);
        assertThrows(LpException.class, () -> solver.minimize(infeasible));

        LinearProgram unbounded = new LinearProgram(new double[] {-1});
        assertThrows(LpException.class, () -> solver.minimize(unbounded));
    }

    @Test
    void validatesProgramConstruction() {
        LinearProgram lp = new LinearProgram(new double[] {1, 1});
        assertThrows(IllegalArgumentException.class, () -> lp.addConstraint(new double[] {1}, Relation.GEQ, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> lp.upperBound(2, 1));
        assertThrows(IllegalArgumentException.class, () -> lp.upperBound(0, -1));
    }

    private static boolean isInt(double v) {
        return Math.abs(v - Math.rint(v)) < EPS;
    }
}
