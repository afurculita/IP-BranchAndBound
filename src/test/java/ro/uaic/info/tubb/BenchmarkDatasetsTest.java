package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.DimacsReader;
import ro.uaic.info.tubb.SatResult.Status;
import ro.uaic.info.tubb.TuBranchAndBoundSolver.Options;
import ro.uaic.info.tubb.TuBranchAndBoundSolver.SearchStrategy;

/**
 * Cross-checks the solver against SAT4J on the SATLIB benchmark instances shipped in {@code datasets/}.
 *
 * <p>These take seconds to minutes each and are excluded from the default build. Run them with
 * {@code mvn verify -Pslow}; the number of instances per family is capped by the system property
 * {@code benchmark.instances} (default 3).
 */
@Tag("slow")
class BenchmarkDatasetsTest {

    static Stream<Path> instances() throws IOException {
        int perFamily = Integer.getInteger("benchmark.instances", 3);
        Stream<Path> uf75 = listCnf(Path.of("datasets/UF75.325.100")).limit(perFamily);
        Stream<Path> flat30 = listCnf(Path.of("datasets/flat30-60")).limit(perFamily);
        return Stream.concat(uf75, flat30);
    }

    private static Stream<Path> listCnf(Path dir) throws IOException {
        return Files.list(dir).filter(p -> p.toString().endsWith(".cnf")).sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("instances")
    void agreesWithSat4j(Path file) throws Exception {
        CnfFormula f = new DimacsReader().read(file);
        boolean expected = Sat4jOracle.isSatisfiable(f);

        SatResult r = new TuBranchAndBoundSolver(Options.defaults().withStrategy(SearchStrategy.DEPTH_FIRST)).solve(f);

        assertEquals(expected ? Status.SATISFIABLE : Status.UNSATISFIABLE, r.status(), file.toString());
        if (expected) {
            assertTrue(f.isSatisfiedBy(r.model().orElseThrow()), "model does not satisfy " + file);
        }
    }
}
