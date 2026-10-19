package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.lp.SimplexLpSolver;
import ro.uaic.info.tubb.Ip1Relaxation.Result;
import ro.uaic.info.tubb.Ip1Relaxation.Status;

class Ip1RelaxationTest {

    private static final double TOL = SimplexLpSolver.DEFAULT_EPSILON;

    static Stream<RelaxationSolverFactory> solvers() {
        return Stream.of(RelaxationSolverFactory.dualSimplex(), RelaxationSolverFactory.commonsMathSimplex());
    }

    private static Ip1Relaxation relaxation(CnfFormula f, RelaxationSolverFactory factory) {
        return new Ip1Relaxation(f, factory.create(f), TOL);
    }

    @ParameterizedTest
    @MethodSource("solvers")
    void falsifiedClauseIsDetectedWithoutSolvingAnLp(RelaxationSolverFactory factory) {
        CnfFormula f = CnfFormula.of(3, new int[] {1, 2}, new int[] {3});
        AtomicInteger calls = new AtomicInteger();
        RelaxationSolver inner = factory.create(f);
        RelaxationSolver counting = fixed -> {
            calls.incrementAndGet();
            return inner.solve(fixed);
        };
        Result r = new Ip1Relaxation(f, counting, TOL)
                .solve(PartialAssignment.empty(3).fix(0, false).fix(1, false));

        assertEquals(Status.FALSIFIED, r.status());
        assertEquals(1, r.value());
        assertNull(r.assignment());
        assertFalse(r.lpSolved());
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @MethodSource("solvers")
    void allClausesSatisfiedByFixingsIsIntegralWithoutAnLp(RelaxationSolverFactory factory) {
        CnfFormula f = CnfFormula.of(3, new int[] {1, 2}, new int[] {-3, 2});
        Result r = relaxation(f, factory).solve(PartialAssignment.empty(3).fix(1, true));

        assertEquals(Status.INTEGRAL, r.status());
        assertEquals(0, r.numRows());
        assertFalse(r.lpSolved());
        assertArrayEquals(new boolean[] {false, true, false}, r.assignment());
    }

    @ParameterizedTest
    @MethodSource("solvers")
    void contradictoryUnitClausesGiveAPositiveBound(RelaxationSolverFactory factory) {
        // x1 + s1 >= 1 and -x1 + s2 >= 0: min s1 + s2 = 1 for every x1 in [0, 1]
        CnfFormula f = CnfFormula.of(1, new int[] {1}, new int[] {-1});
        Result r = relaxation(f, factory).solve(PartialAssignment.empty(1));

        assertEquals(Status.POSITIVE, r.status());
        assertEquals(1, r.value(), 1e-9);
        assertEquals(2, r.numRows());
        assertTrue(r.lpSolved());
    }

    @ParameterizedTest
    @MethodSource("solvers")
    void xorAndEquivalenceGiveAFractionalZeroRelaxation(RelaxationSolverFactory factory) {
        // (x1 ∨ x2)(¬x1 ∨ ¬x2)(x1 ∨ ¬x2)(¬x1 ∨ x2) is unsatisfiable, yet x = (1/2, 1/2), s = 0 is the unique
        // optimal (fractional) vertex of the relaxation.
        CnfFormula f = CnfFormula.of(2, new int[] {1, 2}, new int[] {-1, -2}, new int[] {1, -2}, new int[] {-1, 2});
        Result r = relaxation(f, factory).solve(PartialAssignment.empty(2));

        assertEquals(Status.FRACTIONAL, r.status());
        assertEquals(0, r.value(), TOL);
        assertNull(r.assignment());
    }

    @ParameterizedTest
    @MethodSource("solvers")
    void integralRelaxationYieldsASatisfyingAssignment(RelaxationSolverFactory factory) {
        CnfFormula f = CnfFormula.of(4, new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4});
        Result r = relaxation(f, factory).solve(PartialAssignment.empty(4).fix(0, true));

        assertEquals(Status.INTEGRAL, r.status());
        assertTrue(f.isSatisfiedBy(r.assignment()));
        assertTrue(r.assignment()[0], "fixed variables keep their value");
    }

    @ParameterizedTest
    @MethodSource("solvers")
    void satisfiedClausesAreDroppedFromTheRelaxation(RelaxationSolverFactory factory) {
        CnfFormula f = CnfFormula.of(3, new int[] {1, 2}, new int[] {-1, 3}, new int[] {2, 3});
        Result r = relaxation(f, factory).solve(PartialAssignment.empty(3).fix(0, true));

        // (x1 ∨ x2) is satisfied; (¬x1 ∨ x3) and (x2 ∨ x3) remain
        assertEquals(2, r.numRows());
        assertEquals(Status.INTEGRAL, r.status());
    }
}
