package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.lp.LinearProgramSolver;
import ro.uaic.info.lp.SimplexLpSolver;
import ro.uaic.info.tubb.Ip1Relaxation.Result;
import ro.uaic.info.tubb.Ip1Relaxation.Status;

class Ip1RelaxationTest {

    private static final double TOL = SimplexLpSolver.DEFAULT_EPSILON;

    private static Ip1Relaxation relaxation(CnfFormula f) {
        return new Ip1Relaxation(f, new SimplexLpSolver(), TOL);
    }

    @Test
    void falsifiedClauseIsDetectedWithoutSolvingAnLp() {
        CnfFormula f = CnfFormula.of(3, new int[] {1, 2}, new int[] {3});
        AtomicInteger calls = new AtomicInteger();
        LinearProgramSolver counting = lp -> {
            calls.incrementAndGet();
            return new SimplexLpSolver().minimize(lp);
        };
        Result r = new Ip1Relaxation(f, counting, TOL)
                .solve(PartialAssignment.empty(3).fix(0, false).fix(1, false));

        assertEquals(Status.FALSIFIED, r.status());
        assertEquals(1, r.value());
        assertNull(r.assignment());
        assertEquals(0, calls.get());
    }

    @Test
    void allClausesSatisfiedByFixingsIsIntegralWithoutAnLp() {
        CnfFormula f = CnfFormula.of(3, new int[] {1, 2}, new int[] {-3, 2});
        Result r = relaxation(f).solve(PartialAssignment.empty(3).fix(1, true));

        assertEquals(Status.INTEGRAL, r.status());
        assertEquals(0, r.numRows());
        assertArrayEquals(new boolean[] {false, true, false}, r.assignment());
    }

    @Test
    void contradictoryUnitClausesGiveAPositiveBound() {
        // x1 + s1 >= 1 and -x1 + s2 >= 0: min s1 + s2 = 1 for every x1 in [0, 1]
        CnfFormula f = CnfFormula.of(1, new int[] {1}, new int[] {-1});
        Result r = relaxation(f).solve(PartialAssignment.empty(1));

        assertEquals(Status.POSITIVE, r.status());
        assertEquals(1, r.value(), 1e-9);
        assertEquals(2, r.numRows());
    }

    @Test
    void xorAndEquivalenceGiveAFractionalZeroRelaxation() {
        // (x1 ∨ x2)(¬x1 ∨ ¬x2)(x1 ∨ ¬x2)(¬x1 ∨ x2) is unsatisfiable, yet x = (1/2, 1/2), s = 0 is the unique
        // optimal (fractional) vertex of the relaxation.
        CnfFormula f = CnfFormula.of(2, new int[] {1, 2}, new int[] {-1, -2}, new int[] {1, -2}, new int[] {-1, 2});
        Result r = relaxation(f).solve(PartialAssignment.empty(2));

        assertEquals(Status.FRACTIONAL, r.status());
        assertEquals(0, r.value(), TOL);
        assertNull(r.assignment());
    }

    @Test
    void integralRelaxationYieldsASatisfyingAssignment() {
        CnfFormula f = CnfFormula.of(4, new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4});
        Result r = relaxation(f).solve(PartialAssignment.empty(4).fix(0, true));

        assertEquals(Status.INTEGRAL, r.status());
        assertTrue(f.isSatisfiedBy(r.assignment()));
        assertTrue(r.assignment()[0], "fixed variables keep their value");
    }

    @Test
    void satisfiedClausesAreDroppedFromTheRelaxation() {
        CnfFormula f = CnfFormula.of(3, new int[] {1, 2}, new int[] {-1, 3}, new int[] {2, 3});
        Result r = relaxation(f).solve(PartialAssignment.empty(3).fix(0, true));

        // (x1 ∨ x2) is satisfied; (¬x1 ∨ x3) and (x2 ∨ x3) remain
        assertEquals(2, r.numRows());
        assertEquals(Status.INTEGRAL, r.status());
    }
}
