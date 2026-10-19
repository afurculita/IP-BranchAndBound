package ro.uaic.info;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.cnf.DimacsFormatException;
import ro.uaic.info.cnf.DimacsReader;
import ro.uaic.info.tubb.SatResult;
import ro.uaic.info.tubb.TuBranchAndBoundSolver;
import ro.uaic.info.tubb.TuBranchAndBoundSolver.Options;
import ro.uaic.info.tubb.TuBranchAndBoundSolver.SearchStrategy;

/**
 * Command-line entry point.
 *
 * <pre>
 * usage: java -jar IP-BranchAndBound-all.jar [options] FILE_OR_DIR...
 *   --bfs            width-first search (default, as in the paper)
 *   --dfs            depth-first search
 *   --max-nodes N    give up after N nodes (answer UNKNOWN)
 *   --no-model       do not print the satisfying assignment
 *   -h, --help       this message
 * </pre>
 * Output follows the SAT competition conventions: {@code c} comment lines, one {@code s SATISFIABLE},
 * {@code s UNSATISFIABLE} or {@code s UNKNOWN} line per instance and {@code v} lines with the model.
 * When a single file is given the exit code is 10 for satisfiable, 20 for unsatisfiable and 0 for
 * unknown; with several inputs the exit code is 0. Usage and I/O errors exit with 1.
 */
public final class Main {

    static final int EXIT_SATISFIABLE = 10;
    static final int EXIT_UNSATISFIABLE = 20;
    static final int EXIT_UNKNOWN = 0;
    static final int EXIT_ERROR = 1;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Runs the CLI without exiting the JVM; returns the exit code. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        Options options = Options.defaults();
        boolean printModel = true;
        List<Path> inputs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> {
                    usage(out);
                    return EXIT_UNKNOWN;
                }
                case "--bfs" -> options = options.withStrategy(SearchStrategy.BREADTH_FIRST);
                case "--dfs" -> options = options.withStrategy(SearchStrategy.DEPTH_FIRST);
                case "--no-model" -> printModel = false;
                case "--max-nodes" -> {
                    if (i + 1 >= args.length) {
                        err.println("error: --max-nodes requires a value");
                        return EXIT_ERROR;
                    }
                    try {
                        options = options.withMaxNodes(Long.parseLong(args[++i]));
                    } catch (IllegalArgumentException e) {
                        err.println("error: invalid --max-nodes value '" + args[i] + "'");
                        return EXIT_ERROR;
                    }
                }
                default -> {
                    if (arg.startsWith("-")) {
                        err.println("error: unknown option " + arg);
                        usage(err);
                        return EXIT_ERROR;
                    }
                    inputs.add(Path.of(arg));
                }
            }
        }
        if (inputs.isEmpty()) {
            err.println("error: no input file given");
            usage(err);
            return EXIT_ERROR;
        }

        List<Path> files;
        try {
            files = expand(inputs);
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return EXIT_ERROR;
        }
        if (files.isEmpty()) {
            err.println("error: no .cnf files found");
            return EXIT_ERROR;
        }

        TuBranchAndBoundSolver solver = new TuBranchAndBoundSolver(options);
        DimacsReader reader = new DimacsReader();
        int lastExit = EXIT_UNKNOWN;
        for (Path file : files) {
            CnfFormula formula;
            try {
                formula = reader.read(file);
            } catch (IOException | DimacsFormatException e) {
                err.println("error: " + file + ": " + e.getMessage());
                return EXIT_ERROR;
            }
            out.println("c " + file + ": " + formula.numVariables() + " variables, " + formula.numClauses() + " clauses");
            SatResult result = solver.solve(formula);
            lastExit = report(result, out, printModel);
        }
        return files.size() == 1 ? lastExit : EXIT_UNKNOWN;
    }

    private static int report(SatResult result, PrintStream out, boolean printModel) {
        SatResult.Statistics st = result.statistics();
        out.printf(Locale.ROOT, "c branching order: %d ranked by (IP1), %d by (IP2), %d unranked%n",
                result.branchingOrder().ip1Ranked().size(), result.branchingOrder().ip2Ranked().size(),
                result.branchingOrder().unranked().size());
        out.printf(Locale.ROOT, "c nodes: %d explored, %d relaxations solved, %d falsified, %d pruned, max active %d%n",
                st.nodesExplored(), st.lpSolved(), st.nodesFalsified(), st.nodesPruned(), st.maxQueueSize());
        out.printf(Locale.ROOT, "c time: %.3f s%n", st.elapsed().toNanos() / 1e9);
        switch (result.status()) {
            case SATISFIABLE -> {
                out.println("s SATISFIABLE");
                if (printModel) {
                    StringBuilder sb = new StringBuilder("v");
                    for (int lit : result.modelAsDimacs()) {
                        sb.append(' ').append(lit);
                    }
                    out.println(sb.append(" 0"));
                }
                return EXIT_SATISFIABLE;
            }
            case UNSATISFIABLE -> {
                out.println("s UNSATISFIABLE");
                return EXIT_UNSATISFIABLE;
            }
            default -> {
                out.println("s UNKNOWN");
                return EXIT_UNKNOWN;
            }
        }
    }

    /** Expands directories into the sorted list of the {@code .cnf} files they contain (recursively). */
    private static List<Path> expand(List<Path> inputs) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (Stream<Path> walk = Files.walk(input)) {
                    walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".cnf"))
                            .sorted()
                            .forEach(files::add);
                }
            } else if (Files.isRegularFile(input)) {
                files.add(input);
            } else {
                throw new IOException(input + " does not exist");
            }
        }
        return files;
    }

    private static void usage(PrintStream out) {
        out.println("usage: java -jar IP-BranchAndBound-all.jar [options] FILE_OR_DIR...");
        out.println("  --bfs            width-first search (default, as in the paper)");
        out.println("  --dfs            depth-first search");
        out.println("  --max-nodes N    give up after N nodes (answer UNKNOWN)");
        out.println("  --no-model       do not print the satisfying assignment");
        out.println("  -h, --help       this message");
    }
}
