package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.DimacsReader;
import ro.uaic.info.tubb.SatResult.Status;
import ro.uaic.info.tubb.TuBranchAndBoundSolver.BranchingRule;
import ro.uaic.info.tubb.TuBranchAndBoundSolver.Options;
import ro.uaic.info.tubb.TuBranchAndBoundSolver.SearchStrategy;

/**
 * Cross-checks the solver against SAT4J on the SATLIB benchmark instances shipped in {@code datasets/}.
 *
 * <p>These take seconds each and are excluded from the default build. Run them with
 * {@code mvn verify -Pslow}; the number of instances per family is capped by the system property
 * {@code benchmark.instances} (default 5).
 */
@Tag("slow")
class BenchmarkDatasetsTest {

    private static final Map<String, Options> CONFIGURATIONS = Map.of(
            "paper", Options.paper(),
            "dfs, propagation", Options.defaults().withStrategy(SearchStrategy.DEPTH_FIRST),
            "dfs, dynamic, propagation, 4 threads", Options.defaults().withStrategy(SearchStrategy.DEPTH_FIRST)
                    .withBranchingRule(BranchingRule.DYNAMIC).withThreads(4));

    static Stream<Arguments> instances() throws IOException {
        int perFamily = Integer.getInteger("benchmark.instances", 5);
        Stream<Path> uf75 = listCnf(Path.of("datasets/UF75.325.100")).limit(perFamily);
        Stream<Path> flat30 = listCnf(Path.of("datasets/flat30-60")).limit(perFamily);
        return Stream.concat(uf75, flat30).toList().stream()
                .flatMap(file -> CONFIGURATIONS.entrySet().stream().map(e -> Arguments.of(file, e.getKey(), e.getValue())));
    }

    private static Stream<Path> listCnf(Path dir) throws IOException {
        return Files.list(dir).filter(p -> p.toString().endsWith(".cnf")).sorted();
    }

    @ParameterizedTest(name = "{0} [{1}]")
    @MethodSource("instances")
    void agreesWithSat4j(Path file, String name, Options options) throws Exception {
        CnfFormula f = new DimacsReader().read(file);
        boolean expected = Sat4jOracle.isSatisfiable(f);

        SatResult r = new TuBranchAndBoundSolver(options).solve(f);

        assertEquals(expected ? Status.SATISFIABLE : Status.UNSATISFIABLE, r.status(), file.toString());
        if (expected) {
            assertTrue(f.isSatisfiedBy(r.model().orElseThrow()), "model does not satisfy " + file);
        }
    }
}
