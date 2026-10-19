package ro.uaic.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MainTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void satisfiableFileExitsWith10AndPrintsModel() {
        int exit = Main.run(new String[] {"src/test/resources/cnf/paper-example.cnf"}, out, err);

        assertEquals(Main.EXIT_SATISFIABLE, exit, err());
        assertTrue(out().contains("s SATISFIABLE"), out());
        assertTrue(out().lines().anyMatch(l -> l.startsWith("v ") && l.endsWith(" 0")), out());
        assertTrue(out().contains("c nodes:"), out());
    }

    @Test
    void unsatisfiableFileExitsWith20() {
        int exit = Main.run(new String[] {"--dfs", "--no-model", "src/test/resources/cnf/php-3-2.cnf"}, out, err);

        assertEquals(Main.EXIT_UNSATISFIABLE, exit, err());
        assertTrue(out().contains("s UNSATISFIABLE"), out());
        assertTrue(out().lines().noneMatch(l -> l.startsWith("v ")), out());
    }

    @Test
    void nodeLimitGivesUnknown() {
        int exit = Main.run(new String[] {"--max-nodes", "1", "src/test/resources/cnf/php-3-2.cnf"}, out, err);

        assertEquals(Main.EXIT_UNKNOWN, exit, err());
        assertTrue(out().contains("s UNKNOWN"), out());
    }

    @Test
    void directoriesAreExpandedAndExitZero() {
        int exit = Main.run(new String[] {"--bfs", "src/test/resources/cnf"}, out, err);

        // bad-header.cnf is in that directory and fails to parse
        assertEquals(Main.EXIT_ERROR, exit);
        assertTrue(err().contains("bad-header.cnf"), err());

        outBytes.reset();
        errBytes.reset();
        exit = Main.run(new String[] {"datasets/simple.cnf", "datasets/quinn.cnf"}, out, err);
        assertEquals(0, exit, err());
        assertEquals(2, out().lines().filter(l -> l.startsWith("s ")).count(), out());
    }

    @Test
    void usageErrors() {
        assertEquals(Main.EXIT_ERROR, Main.run(new String[0], out, err));
        assertTrue(err().contains("usage"), err());

        assertEquals(Main.EXIT_ERROR, Main.run(new String[] {"--bogus", "x.cnf"}, out, err));
        assertEquals(Main.EXIT_ERROR, Main.run(new String[] {"--max-nodes"}, out, err));
        assertEquals(Main.EXIT_ERROR, Main.run(new String[] {"--max-nodes", "many", "x.cnf"}, out, err));
        assertEquals(Main.EXIT_ERROR, Main.run(new String[] {"does-not-exist.cnf"}, out, err));
        assertEquals(Main.EXIT_ERROR, Main.run(new String[] {"src/test/resources/cnf/bad-header.cnf"}, out, err));
    }

    @Test
    void helpExitsZero() {
        assertEquals(0, Main.run(new String[] {"--help"}, out, err));
        assertTrue(out().contains("usage"), out());
    }
}
