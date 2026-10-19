package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.DimacsReader;
import ro.uaic.info.tubb.SatResult.Status;
import ro.uaic.info.tubb.TuBranchAndBoundSolver.BranchingRule;
import ro.uaic.info.tubb.TuBranchAndBoundSolver.Options;
import ro.uaic.info.tubb.TuBranchAndBoundSolver.SearchStrategy;

class TuBranchAndBoundSolverTest {

    private static final CnfFormula PAPER = CnfFormula.of(4,
            new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4});

    /** Named configurations covering every option. */
    static Map<String, Options> configurations() {
        Map<String, Options> configs = new LinkedHashMap<>();
        configs.put("paper", Options.paper());
        configs.put("paper, commons-math simplex", Options.paper().withRelaxationSolvers(RelaxationSolverFactory.commonsMathSimplex()));
        configs.put("default (bfs, propagation)", Options.defaults());
        configs.put("dfs, propagation", Options.defaults().withStrategy(SearchStrategy.DEPTH_FIRST));
        configs.put("bfs, dynamic, propagation", Options.defaults().withBranchingRule(BranchingRule.DYNAMIC));
        configs.put("dfs, dynamic, propagation", Options.defaults().withStrategy(SearchStrategy.DEPTH_FIRST).withBranchingRule(BranchingRule.DYNAMIC));
        configs.put("dfs, dynamic, no propagation", Options.paper().withStrategy(SearchStrategy.DEPTH_FIRST).withBranchingRule(BranchingRule.DYNAMIC));
        configs.put("dfs, propagation, 3 threads", Options.defaults().withStrategy(SearchStrategy.DEPTH_FIRST).withThreads(3));
        configs.put("bfs, dynamic, no propagation, 2 threads", Options.paper().withBranchingRule(BranchingRule.DYNAMIC).withThreads(2));
        return configs;
    }

    static Stream<Arguments> configurationArguments() {
        return configurations().entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    static Stream<Arguments> randomInstances() {
        return configurations().entrySet().stream()
                .flatMap(e -> LongStream.rangeClosed(1, 30).mapToObj(seed -> Arguments.of(e.getKey(), e.getValue(), seed)));
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurationArguments")
    void paperExampleIsSatisfiable(String name, Options options) {
        SatResult r = new TuBranchAndBoundSolver(options).solve(PAPER);
        assertSatisfiable(PAPER, r);
        assertEquals(4, r.modelAsDimacs().length);
        assertTrue(r.statistics().nodesExplored() >= 1);
        assertTrue(r.statistics().elapsed().toNanos() > 0);
    }

    @Test
    void modelAsDimacsEncodesPolarity() {
        CnfFormula f = CnfFormula.of(2, new int[] {1}, new int[] {-2});
        SatResult r = new TuBranchAndBoundSolver().solve(f);
        assertSatisfiable(f, r);
        assertArrayEquals(new int[] {1, -2}, r.modelAsDimacs());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurationArguments")
    void xorWithEquivalenceIsUnsatisfiable(String name, Options options) {
        CnfFormula f = CnfFormula.of(2, new int[] {1, 2}, new int[] {-1, -2}, new int[] {1, -2}, new int[] {-1, 2});
        SatResult r = new TuBranchAndBoundSolver(options).solve(f);
        assertUnsatisfiable(r);
        // root is fractional; both children are infeasible (pruned by the LP, or by propagation)
        assertEquals(3, r.statistics().nodesExplored());
        assertEquals(2, r.statistics().nodesPruned() + r.statistics().nodesFalsified());
    }

    @Test
    void paperConfigurationPrunesByTheRelaxationOnly() {
        CnfFormula f = CnfFormula.of(2, new int[] {1, 2}, new int[] {-1, -2}, new int[] {1, -2}, new int[] {-1, 2});
        SatResult r = new TuBranchAndBoundSolver(Options.paper()).solve(f);
        assertEquals(2, r.statistics().nodesPruned());
        assertEquals(0, r.statistics().propagatedVariables());

        SatResult withPropagation = new TuBranchAndBoundSolver().solve(f);
        assertEquals(2, withPropagation.statistics().nodesFalsified());
        assertTrue(withPropagation.statistics().propagatedVariables() > 0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurationArguments")
    void allClausesOverThreeVariablesIsUnsatisfiable(String name, Options options) {
        int[][] clauses = new int[8][];
        for (int mask = 0; mask < 8; mask++) {
            clauses[mask] = new int[] {(mask & 1) == 0 ? 1 : -1, (mask & 2) == 0 ? 2 : -2, (mask & 4) == 0 ? 3 : -3};
        }
        assertUnsatisfiable(new TuBranchAndBoundSolver(options).solve(CnfFormula.of(3, clauses)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurationArguments")
    void pigeonholeIsUnsatisfiable(String name, Options options) throws Exception {
        CnfFormula f = new DimacsReader().read(Path.of("src/test/resources/cnf/php-3-2.cnf"));
        assertFalse(Sat4jOracle.isSatisfiable(f));
        assertUnsatisfiable(new TuBranchAndBoundSolver(options).solve(f));
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
        for (Options options : new Options[] {Options.paper(), Options.defaults()}) {
            SatResult r = new TuBranchAndBoundSolver(options).solve(f);
            assertSatisfiable(f, r);
            assertArrayEquals(new boolean[] {true, true, true}, r.model().orElseThrow());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurationArguments")
    void datasetSamplesAreSatisfiable(String name, Options options) throws Exception {
        DimacsReader reader = new DimacsReader();
        for (String file : new String[] {"datasets/simple.cnf", "datasets/quinn.cnf"}) {
            CnfFormula f = reader.read(Path.of(file));
            assertSatisfiable(f, new TuBranchAndBoundSolver(options).solve(f));
        }
    }

    /**
     * Random 3-SAT at the phase transition (m/n ≈ 4.25) contains both satisfiable and unsatisfiable
     * instances; the answers of every configuration are cross-checked against SAT4J.
     */
    @ParameterizedTest(name = "{0}, seed {2}")
    @MethodSource("randomInstances")
    void agreesWithSat4jOnRandomThreeSat(String name, Options options, long seed) {
        CnfFormula f = RandomCnf.kSat(12, 51, 3, seed);
        SatResult r = new TuBranchAndBoundSolver(options).solve(f);
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
    void largerInstanceWithAllExtensions() {
        CnfFormula f = RandomCnf.kSat(40, 170, 3, 2024L);
        Options options = Options.defaults().withStrategy(SearchStrategy.DEPTH_FIRST)
                .withBranchingRule(BranchingRule.DYNAMIC).withThreads(4);
        SatResult r = new TuBranchAndBoundSolver(options).solve(f);
        if (Sat4jOracle.isSatisfiable(f)) {
            assertSatisfiable(f, r);
        } else {
            assertUnsatisfiable(r);
        }
    }

    @Test
    void nodeLimitYieldsUnknown() {
        CnfFormula f = RandomCnf.kSat(12, 51, 3, 3L);
        for (int threads : new int[] {1, 2}) {
            SatResult r = new TuBranchAndBoundSolver(Options.defaults().withMaxNodes(1).withThreads(threads)).solve(f);
            assertEquals(Status.UNKNOWN, r.status());
            assertEquals(1, r.statistics().nodesExplored());
        }
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
        assertThrows(IllegalArgumentException.class, () -> Options.defaults().withThreads(0));
        assertThrows(NullPointerException.class, () -> Options.defaults().withLpSolver(null));
        assertThrows(NullPointerException.class, () -> Options.defaults().withRelaxationSolvers(null));
        assertThrows(NullPointerException.class, () -> Options.defaults().withBranchingRule(null));
        assertThrows(IllegalArgumentException.class, () -> new Options(RelaxationSolverFactory.dualSimplex(),
                SearchStrategy.BREADTH_FIRST, BranchingRule.STATIC, true, 1, 1, 0.5));
        assertThrows(NullPointerException.class, () -> new TuBranchAndBoundSolver(null));

        Options defaults = new TuBranchAndBoundSolver().options();
        assertEquals(SearchStrategy.BREADTH_FIRST, defaults.strategy());
        assertEquals(BranchingRule.STATIC, defaults.branchingRule());
        assertTrue(defaults.propagation());
        assertEquals(1, defaults.threads());
        assertFalse(Options.paper().propagation());
    }
}
