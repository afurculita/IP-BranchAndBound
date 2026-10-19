package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ro.uaic.info.cnf.Clause;
import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.Literal;
import ro.uaic.info.lp.LpSolution;
import ro.uaic.info.lp.SimplexLpSolver;

class DualSimplexRelaxationSolverTest {

    private static final double EPS = 1e-6;

    /** Both warm-started implementations, with accessors to their counters. */
    record Impl(String name, Function<CnfFormula, RelaxationSolver> create, ToLongFunction<RelaxationSolver> pivots,
                ToLongFunction<RelaxationSolver> solves, ToLongFunction<RelaxationSolver> coldStarts, int refactorInterval) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final Impl TABLEAU = new Impl("tableau", DualSimplexRelaxationSolver::new,
            s -> ((DualSimplexRelaxationSolver) s).pivots(), s -> ((DualSimplexRelaxationSolver) s).solves(),
            s -> ((DualSimplexRelaxationSolver) s).coldStarts(), DualSimplexRelaxationSolver.REFACTOR_INTERVAL);
    static final Impl REVISED = new Impl("revised", RevisedDualSimplexRelaxationSolver::new,
            s -> ((RevisedDualSimplexRelaxationSolver) s).pivots(), s -> ((RevisedDualSimplexRelaxationSolver) s).solves(),
            s -> ((RevisedDualSimplexRelaxationSolver) s).coldStarts(), RevisedDualSimplexRelaxationSolver.REINVERT_INTERVAL);

    static Stream<Impl> impls() {
        return Stream.of(TABLEAU, REVISED);
    }

    static Stream<Arguments> implsAndSeeds() {
        return impls().flatMap(impl -> Stream.of(1L, 2L, 3L, 4L, 5L).map(seed -> Arguments.of(impl, seed)));
    }

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

    @ParameterizedTest(name = "{0} seed {1}")
    @MethodSource("implsAndSeeds")
    void agreesWithCommonsMathOnRandomNodes(Impl impl, long seed) {
        CnfFormula f = RandomCnf.kSat(20, 85, 3, seed);
        RelaxationSolver dual = impl.create().apply(f);
        RebuildingRelaxationSolver reference = new RebuildingRelaxationSolver(f, new SimplexLpSolver());
        Random random = new Random(seed * 31);

        for (int node = 0; node < 60; node++) {
            PartialAssignment fixed = randomFixings(20, random, random.nextDouble() * 0.8);
            LpSolution expected = reference.solve(fixed);
            LpSolution actual = dual.solve(fixed);

            assertEquals(expected.value(), actual.value(), EPS, "node " + node + " fixings " + fixed);
            assertFeasible(f, fixed, actual);
        }
        assertEquals(60, impl.solves().applyAsLong(dual));
        assertTrue(impl.pivots().applyAsLong(dual) > 0);
        assertEquals(0, impl.coldStarts().applyAsLong(dual), "warm starts should never fail on these instances");
    }

    @ParameterizedTest
    @MethodSource("impls")
    void warmStartsAreCheaperThanColdStarts(Impl impl) {
        CnfFormula f = RandomCnf.kSat(30, 128, 3, 11L);
        RelaxationSolver dual = impl.create().apply(f);
        dual.solve(PartialAssignment.empty(30));
        long rootPivots = impl.pivots().applyAsLong(dual);

        // Children of the root differ by one bound: a few pivots must suffice.
        long before = rootPivots;
        dual.solve(PartialAssignment.empty(30).fix(0, true));
        dual.solve(PartialAssignment.empty(30).fix(0, false));
        long childPivots = impl.pivots().applyAsLong(dual) - before;

        assertTrue(rootPivots > 0);
        assertTrue(childPivots < rootPivots, "children took " + childPivots + " pivots, root " + rootPivots);
    }

    @ParameterizedTest
    @MethodSource("impls")
    void refactoringKeepsResultsExact(Impl impl) {
        // Enough solves to cross the refactoring threshold several times.
        CnfFormula f = RandomCnf.kSat(25, 106, 3, 99L);
        RelaxationSolver dual = impl.create().apply(f);
        RebuildingRelaxationSolver reference = new RebuildingRelaxationSolver(f, new SimplexLpSolver());
        Random random = new Random(7);
        int checks = 0;
        long target = 3L * impl.refactorInterval();
        while (impl.pivots().applyAsLong(dual) < target) {
            PartialAssignment fixed = randomFixings(25, random, 0.4);
            LpSolution actual = dual.solve(fixed);
            if (checks++ % 10 == 0) {
                assertEquals(reference.solve(fixed).value(), actual.value(), EPS);
            }
            assertFeasible(f, fixed, actual);
        }
        assertTrue(impl.pivots().applyAsLong(dual) >= target);
    }

    @ParameterizedTest
    @MethodSource("impls")
    void handlesTrivialNodes(Impl impl) {
        CnfFormula f = CnfFormula.of(2, new int[] {1, 2}, new int[] {-1, -2});
        RelaxationSolver dual = impl.create().apply(f);

        // all variables fixed: value is the number of falsified clauses
        assertEquals(0, dual.solve(PartialAssignment.empty(2).fix(0, true).fix(1, false)).value(), EPS);
        assertEquals(1, dual.solve(PartialAssignment.empty(2).fix(0, true).fix(1, true)).value(), EPS);
        // unit contradiction
        CnfFormula unsat = CnfFormula.of(1, new int[] {1}, new int[] {-1});
        assertEquals(1, impl.create().apply(unsat).solve(PartialAssignment.empty(1)).value(), EPS);
        // no clauses
        assertEquals(0, impl.create().apply(CnfFormula.of(3)).solve(PartialAssignment.empty(3)).value(), EPS);
    }

    @ParameterizedTest
    @MethodSource("impls")
    void rejectsAssignmentsOfTheWrongSize(Impl impl) {
        RelaxationSolver dual = impl.create().apply(CnfFormula.of(2, new int[] {1, 2}));
        assertThrows(IllegalArgumentException.class, () -> dual.solve(PartialAssignment.empty(3)));
    }
}
