package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.Literal;
import ro.uaic.info.lp.LpSolution;
import ro.uaic.info.lp.SimplexLpSolver;

class DualSimplexRelaxationSolverTest {

    private static final double EPS = 1e-6;

    /** {@code Σᵢ max(0, 1 − bᵢ − Aᵢx)}: the objective of the relaxation for a given {@code x}. */
    private static double objective(CnfFormula f, double[] x) {
        double total = 0;
        for (Clause c : f.clauses()) {
            double lhs = 0;
            for (Literal lit : c.literals()) {
                lhs += lit.sign() * x[lit.index()];
            }
            total += Math.max(0, 1 - c.countNegated() - lhs);
        }
        return total;
    }

    private static void assertFeasible(CnfFormula f, PartialAssignment fixed, LpSolution s) {
        double[] x = s.point();
        assertEquals(f.numVariables(), x.length);
        for (int j = 0; j < x.length; j++) {
            if (fixed.isFree(j)) {
                assertTrue(x[j] >= -EPS && x[j] <= 1 + EPS, "x" + (j + 1) + " = " + x[j] + " out of [0,1]");
            } else {
                assertEquals(fixed.value(j) ? 1 : 0, x[j], EPS, "fixed x" + (j + 1));
            }
        }
        assertEquals(objective(f, x), s.value(), 1e-5, "reported value must match the returned point");
    }

    private static PartialAssignment randomFixings(int n, Random random, double probability) {
        PartialAssignment a = PartialAssignment.empty(n);
        for (int j = 0; j < n; j++) {
            if (random.nextDouble() < probability) {
                a = a.fix(j, random.nextBoolean());
            }
        }
        return a;
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void agreesWithCommonsMathOnRandomNodes(long seed) {
        CnfFormula f = RandomCnf.kSat(20, 85, 3, seed);
        DualSimplexRelaxationSolver dual = new DualSimplexRelaxationSolver(f);
        RebuildingRelaxationSolver reference = new RebuildingRelaxationSolver(f, new SimplexLpSolver());
        Random random = new Random(seed * 31);

        for (int node = 0; node < 60; node++) {
            PartialAssignment fixed = randomFixings(20, random, random.nextDouble() * 0.8);
            LpSolution expected = reference.solve(fixed);
            LpSolution actual = dual.solve(fixed);

            assertEquals(expected.value(), actual.value(), EPS, "node " + node + " fixings " + fixed);
            assertFeasible(f, fixed, actual);
        }
        assertEquals(60, dual.solves());
        assertTrue(dual.pivots() > 0);
        assertEquals(0, dual.coldStarts(), "warm starts should never fail on these instances");
    }

    @Test
    void warmStartsAreCheaperThanColdStarts() {
        CnfFormula f = RandomCnf.kSat(30, 128, 3, 11L);
        DualSimplexRelaxationSolver dual = new DualSimplexRelaxationSolver(f);
        dual.solve(PartialAssignment.empty(30));
        long rootPivots = dual.pivots();

        // Children of the root differ by one bound: a few pivots must suffice.
        long before = dual.pivots();
        dual.solve(PartialAssignment.empty(30).fix(0, true));
        dual.solve(PartialAssignment.empty(30).fix(0, false));
        long childPivots = dual.pivots() - before;

        assertTrue(rootPivots > 0);
        assertTrue(childPivots < rootPivots, "children took " + childPivots + " pivots, root " + rootPivots);
    }

    @Test
    void refactoringKeepsResultsExact() {
        // Enough solves to cross the refactoring threshold several times.
        CnfFormula f = RandomCnf.kSat(25, 106, 3, 99L);
        DualSimplexRelaxationSolver dual = new DualSimplexRelaxationSolver(f);
        RebuildingRelaxationSolver reference = new RebuildingRelaxationSolver(f, new SimplexLpSolver());
        Random random = new Random(7);
        int checks = 0;
        while (dual.pivots() < 3L * DualSimplexRelaxationSolver.REFACTOR_INTERVAL) {
            PartialAssignment fixed = randomFixings(25, random, 0.4);
            LpSolution actual = dual.solve(fixed);
            if (checks++ % 10 == 0) {
                assertEquals(reference.solve(fixed).value(), actual.value(), EPS);
            }
            assertFeasible(f, fixed, actual);
        }
        assertTrue(dual.pivots() >= 3L * DualSimplexRelaxationSolver.REFACTOR_INTERVAL);
    }

    @Test
    void handlesTrivialNodes() {
        CnfFormula f = CnfFormula.of(2, new int[] {1, 2}, new int[] {-1, -2});
        DualSimplexRelaxationSolver dual = new DualSimplexRelaxationSolver(f);

        // all variables fixed: value is the number of falsified clauses
        assertEquals(0, dual.solve(PartialAssignment.empty(2).fix(0, true).fix(1, false)).value(), EPS);
        assertEquals(1, dual.solve(PartialAssignment.empty(2).fix(0, true).fix(1, true)).value(), EPS);
        // unit contradiction
        CnfFormula unsat = CnfFormula.of(1, new int[] {1}, new int[] {-1});
        assertEquals(1, new DualSimplexRelaxationSolver(unsat).solve(PartialAssignment.empty(1)).value(), EPS);
        // no clauses
        assertEquals(0, new DualSimplexRelaxationSolver(CnfFormula.of(3)).solve(PartialAssignment.empty(3)).value(), EPS);
    }

    @Test
    void rejectsAssignmentsOfTheWrongSize() {
        DualSimplexRelaxationSolver dual = new DualSimplexRelaxationSolver(CnfFormula.of(2, new int[] {1, 2}));
        assertThrows(IllegalArgumentException.class, () -> dual.solve(PartialAssignment.empty(3)));
    }
}
