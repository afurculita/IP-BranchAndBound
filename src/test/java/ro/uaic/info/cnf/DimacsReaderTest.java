package ro.uaic.info.cnf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class DimacsReaderTest {

    private final DimacsReader reader = new DimacsReader();

    static Path resource(String name) {
        return Path.of("src/test/resources/cnf", name);
    }

    @Test
    void readsPaperExampleFile() throws Exception {
        CnfFormula f = reader.read(resource("paper-example.cnf"));
        assertEquals(4, f.numVariables());
        assertEquals(Clause.listOf(new int[] {1, 2}, new int[] {-1, 2, -3}, new int[] {-2, -4}), f.clauses());
    }

    @Test
    void handlesCommentsMultiLineClausesAndPercentTerminator() throws Exception {
        CnfFormula f = reader.read(resource("tricky-format.cnf"));
        assertEquals(5, f.numVariables());
        assertEquals(List.of(Clause.of(1, -2, 3), Clause.of(-1, 2), Clause.of(4, 5), Clause.of(-3, -4, -5)),
                f.clauses());
    }

    @Test
    void readsSatlibBenchmarkWithPercentTrailer() throws Exception {
        CnfFormula f = reader.read(Path.of("datasets/UF75.325.100/uf75-01.cnf"));
        assertEquals(75, f.numVariables());
        assertEquals(325, f.numClauses());
        assertTrue(f.clauses().stream().allMatch(c -> c.size() == 3));
    }

    @Test
    void toleratesMissingFinalZero() throws Exception {
        CnfFormula f = reader.read("p cnf 2 2\n1 2 0\n-1 -2");
        assertEquals(List.of(Clause.of(1, 2), Clause.of(-1, -2)), f.clauses());
    }

    @Test
    void acceptsWhitespaceVariations() throws Exception {
        CnfFormula f = reader.read("  p  cnf   3   1 \r\n  1\t-2 \t 3   0  \r\n");
        assertEquals(List.of(Clause.of(1, -2, 3)), f.clauses());
    }

    @Test
    void rejectsMalformedProblemLine() {
        DimacsFormatException e = assertThrows(DimacsFormatException.class, () -> reader.read(resource("bad-header.cnf")));
        assertTrue(e.getMessage().contains("p cnf"), e.getMessage());
        assertThrows(DimacsFormatException.class, () -> reader.read("p sat 3 1\n1 2 0\n"));
        assertThrows(DimacsFormatException.class, () -> reader.read("p cnf x 1\n1 0\n"));
    }

    @Test
    void rejectsMissingProblemLine() {
        assertThrows(DimacsFormatException.class, () -> reader.read("c only a comment\n"));
        assertThrows(DimacsFormatException.class, () -> reader.read("1 2 0\n"));
    }

    @Test
    void rejectsDuplicateProblemLine() {
        assertThrows(DimacsFormatException.class, () -> reader.read("p cnf 2 1\np cnf 2 1\n1 2 0\n"));
    }

    @Test
    void rejectsClauseCountMismatch() {
        DimacsFormatException e = assertThrows(DimacsFormatException.class, () -> reader.read("p cnf 2 3\n1 2 0\n-1 0\n"));
        assertTrue(e.getMessage().contains("announces 3"), e.getMessage());
    }

    @Test
    void rejectsVariableOutOfRange() {
        assertThrows(DimacsFormatException.class, () -> reader.read("p cnf 2 1\n1 3 0\n"));
    }

    @Test
    void rejectsNonIntegerTokens() {
        assertThrows(DimacsFormatException.class, () -> reader.read("p cnf 2 1\n1 two 0\n"));
    }

    @Test
    void reportsMissingFiles() {
        assertThrows(IOException.class, () -> reader.read(Path.of("does/not/exist.cnf")));
    }
}
