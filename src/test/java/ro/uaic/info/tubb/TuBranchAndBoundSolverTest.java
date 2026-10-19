package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.DimacsReader;
import ro.uaic.info.tubb.SatResult.Status;
import ro.uaic.info.tubb.TuBranchAndBoundSolver.Options;
import ro.uaic.info.tubb.TuBranchAndBoundSolver.SearchStrategy;

class TuBranchAndBoundSolverTest {

    private static final CnfFormula PAPER = CnfFormula.of(4,
            new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4});

    private static TuBranchAndBoundSolver solver(SearchStrategy strategy) {
        return new TuBranchAndBoundSolver(Options.defaults().withStrategy(strategy));
    }

    private static void assertSatisfiable(CnfFormula f, SatResult r) {
        assertEquals(Status.SATISFIABLE, r.status(), r.toString());
        assertTrue(r.isSatisfiable());
        assertTrue(f.isSatisfiedBy(r.model().orElseThrow()), "model does not satisfy the formula");
    }

    private static void assertUnsatisfiable(SatResult r) {
        assertEquals(Status.UNSATISFIABLE, r.status(), r.toString());
        assertFalse(r.isSatisfiable());
        assertTrue(r.model().isEmpty());
        assertThrows(IllegalStateException.class, r::modelAsDimacs);
    }

    @ParameterizedTest
    @EnumSource(SearchStrategy.class)
    void paperExampleIsSatisfiable(SearchStrategy strategy) {
        SatResult r = solver(strategy).solve(PAPER);
        assertSatisfiable(PAPER, r);
        assertEquals(4, r.modelAsDimacs().length);
        assertTrue(r.statistics().nodesExplored() >= 1);
        assertTrue(r.statistics().elapsed().toNanos() > 0);
    }

    @Test
    void modelAsDimacsEncodesPolarity() {
        SatResult r = new TuBranchAndBoundSolver().solve(CnfFormula.of(2, new int[] {1}, new int[] {-2}));
        assertSatisfiable(CnfFormula.of(2, new int[] {1}, new int[] {-2}), r);
        assertArrayEquals(new int[] {1, -2}, r.modelAsDimacs());
    }

    @ParameterizedTest
    @EnumSource(SearchStrategy.class)
    void xorWithEquivalenceIsUnsatisfiable(SearchStrategy strategy) {
        CnfFormula f = CnfFormula.of(2, new int[] {1, 2}, new int[] {-1, -2}, new int[] {1, -2}, new int[] {-1, 2});
        SatResult r = solver(strategy).solve(f);
        assertUnsatisfiable(r);
        // root is fractional, both children are pruned by a positive relaxation
        assertEquals(3, r.statistics().nodesExplored());
        assertEquals(2, r.statistics().nodesPruned());
    }

    @ParameterizedTest
    @EnumSource(SearchStrategy.class)
    void allClausesOverThreeVariablesIsUnsatisfiable(SearchStrategy strategy) {
        int[][] clauses = new int[8][];
        for (int mask = 0; mask < 8; mask++) {
            clauses[mask] = new int[] {(mask & 1) == 0 ? 1 : -1, (mask & 2) == 0 ? 2 : -2, (mask & 4) == 0 ? 3 : -3};
        }
        assertUnsatisfiable(solver(strategy).solve(CnfFormula.of(3, clauses)));
    }

    @ParameterizedTest
    @EnumSource(SearchStrategy.class)
    void pigeonholeIsUnsatisfiable(SearchStrategy strategy) throws Exception {
        CnfFormula f = new DimacsReader().read(Path.of("src/test/resources/cnf/php-3-2.cnf"));
        assertFalse(Sat4jOracle.isSatisfiable(f));
        assertUnsatisfiable(solver(strategy).solve(f));
    }

    @Test
    void emptyFormulaAndUnusedVariables() {
        SatResult r = new TuBranchAndBoundSolver().solve(CnfFormula.of(3));
        assertSatisfiable(CnfFormula.of(3), r);
        assertEquals(1, r.statistics().nodesExplored());
        assertEquals(0, r.statistics().lpSolved());

        CnfFormula sparse = CnfFormula.of(5, new int[] {2, -4});
        assertSatisfiable(sparse, new TuBranchAndBoundSolver().solve(sparse));
    }

    @Test
    void unitClausesPropagateThroughTheRelaxation() {
        CnfFormula f = CnfFormula.of(3, new int[] {1}, new int[] {-1, 2}, new int[] {-2, 3});
        SatResult r = new TuBranchAndBoundSolver().solve(f);
        assertSatisfiable(f, r);
        assertArrayEquals(new boolean[] {true, true, true}, r.model().orElseThrow());
    }

    @ParameterizedTest
    @EnumSource(SearchStrategy.class)
    void datasetSamplesAreSatisfiable(SearchStrategy strategy) throws Exception {
        DimacsReader reader = new DimacsReader();
        for (String name : new String[] {"datasets/simple.cnf", "datasets/quinn.cnf"}) {
            CnfFormula f = reader.read(Path.of(name));
            assertSatisfiable(f, solver(strategy).solve(f));
        }
    }

    static Stream<Arguments> randomInstances() {
        return Stream.of(SearchStrategy.values())
                .flatMap(s -> LongStream.rangeClosed(1, 30).mapToObj(seed -> Arguments.of(s, seed)));
    }

    /**
     * Random 3-SAT at the phase transition (m/n ≈ 4.25) contains both satisfiable and unsatisfiable
     * instances; the answers are cross-checked against SAT4J.
     */
    @ParameterizedTest(name = "{0} seed {1}")
    @MethodSource("randomInstances")
    void agreesWithSat4jOnRandomThreeSat(SearchStrategy strategy, long seed) {
        CnfFormula f = RandomCnf.kSat(12, 51, 3, seed);
        SatResult r = solver(strategy).solve(f);
        if (Sat4jOracle.isSatisfiable(f)) {
            assertSatisfiable(f, r);
        } else {
            assertUnsatisfiable(r);
        }
    }

    @Test
    void randomInstancesIncludeBothAnswers() {
        long sat = LongStream.rangeClosed(1, 30).filter(seed -> Sat4jOracle.isSatisfiable(RandomCnf.kSat(12, 51, 3, seed))).count();
        assertTrue(sat > 0 && sat < 30, "expected a mix of answers, got " + sat + " satisfiable");
    }

    @Test
    void nodeLimitYieldsUnknown() {
        CnfFormula f = RandomCnf.kSat(12, 51, 3, 3L);
        SatResult r = new TuBranchAndBoundSolver(Options.defaults().withMaxNodes(1)).solve(f);
        assertEquals(Status.UNKNOWN, r.status());
        assertEquals(1, r.statistics().nodesExplored());
    }

    @Test
    void acceptsCallerSuppliedBranchingOrder() {
        BranchingOrder order = BranchingOrder.compute(PAPER);
        assertSatisfiable(PAPER, new TuBranchAndBoundSolver().solve(PAPER, order));

        CnfFormula other = CnfFormula.of(3, new int[] {1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> new TuBranchAndBoundSolver().solve(other, order));
    }

    @Test
    void optionsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> Options.defaults().withMaxNodes(0));
        assertThrows(NullPointerException.class, () -> Options.defaults().withLpSolver(null));
        assertThrows(IllegalArgumentException.class,
                () -> new Options(Options.defaults().lpSolver(), SearchStrategy.BREADTH_FIRST, 1, 0.5));
        assertThrows(NullPointerException.class, () -> new TuBranchAndBoundSolver(null));
        assertEquals(SearchStrategy.BREADTH_FIRST, new TuBranchAndBoundSolver().options().strategy());
    }
}
