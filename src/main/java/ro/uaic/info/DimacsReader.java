package ro.uaic.info;

import org.sat4j.reader.EfficientScanner;
import org.sat4j.reader.ParseFormatException;
import org.sat4j.specs.ContradictionException;
import ro.uaic.info.TUBasedBranchAndBound.Solver;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Very simple Dimacs file parser. Allow solvers to read the constraints from a
 * Dimacs formatted file
 */
class DimacsReader implements Serializable {

    private static final long serialVersionUID = 1L;

    private int expectedNbOfConstr; // as announced on the p cnf line

    private SATProblem problem;

    private final String formatString;

    private EfficientScanner scanner;

    private List<Literal> literals = new ArrayList<>();

    public DimacsReader(Solver solver) {
        this(solver, "cnf");
    }

    public DimacsReader(Solver solver, String format) {
        this.problem = new SATProblem(solver);
        formatString = format;
    }

    /**
     * Skip comments at the beginning of the input stream.
     *
     * @throws IOException if an IO problem occurs.
     */
    private void skipComments() throws IOException {
        scanner.skipComments();
    }

    /**
     * @throws IOException          iff an IO occurs
     * @throws ParseFormatException if the input stream does not comply with the DIMACS format.
     */
    private void readProblemLine() throws IOException, ParseFormatException {

        String line = scanner.nextLine().trim();

        String[] tokens = line.split("\\s+");
        if (tokens.length < 4 || !"p".equals(tokens[0])
                || !formatString.equals(tokens[1])) {
            throw new ParseFormatException("problem line expected (p cnf ...)");
        }

        // reads the number of clauses
        expectedNbOfConstr = Integer.parseInt(tokens[3]);
        assert expectedNbOfConstr > 0;
    }


    /**
     * This is the usual method to feed a solver with a benchmark.
     *
     * @param filename the fully qualified name of the benchmark. The filename
     *                 extension may by used to detect which type of benchmarks it is
     *                 (SAT, OPB, MAXSAT, etc).
     * @return the problem to solve (an ISolver in fact).
     * @throws ParseFormatException if an error occurs during parsing.
     * @throws IOException          if an I/O error occurs.
     */
    public SATProblem parseInstance(final String filename)
            throws ParseFormatException, IOException {
        InputStream in = null;
        try {
            in = new FileInputStream(filename);

            return parseInstance(in);
        } catch (ParseFormatException | IOException e) {
            throw e;
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }

    /**
     * @throws IOException            iff an IO problems occurs
     * @throws ParseFormatException   if the input stream does not comply with the DIMACS format.
     * @throws ContradictionException si le probl?me est trivialement inconsistant.
     */
    private void readConstrs() throws IOException, ParseFormatException {
        int realNbOfConstr = 0;

        literals.clear();
        boolean needToContinue = true;

        while (needToContinue) {
            boolean added = false;
            if (scanner.eof()) {
                // end of file
                if (literals.size() > 0) {
                    // no 0 end the last clause
                    flushConstraint();
                    added = true;
                }
                needToContinue = false;
            } else {
                if (scanner.currentChar() == 'c') {
                    // ignore comment line
                    scanner.skipRestOfLine();
                    continue;
                }
                if (scanner.currentChar() == '%'
                        && expectedNbOfConstr == realNbOfConstr) {
                    break;
                }
                added = handleLine();
            }
            if (added) {
                realNbOfConstr++;
            }
        }

        if (expectedNbOfConstr != realNbOfConstr) {
            throw new ParseFormatException("wrong nbclauses parameter. Found "
                    + realNbOfConstr + ", " + expectedNbOfConstr + " expected");
        }
    }

    private void flushConstraint() {
        try {
            problem.addClause(literals);
        } catch (IllegalArgumentException ex) {
            System.err.println("c Skipping constraint " + literals);
        }
    }

    private boolean handleLine() throws IOException,
            ParseFormatException {
        int lit;
        boolean added = false;
        while (!scanner.eof()) {
            lit = scanner.nextInt();
            if (lit == 0) {
                if (literals.size() > 0) {
                    flushConstraint();
                    literals.clear();
                    added = true;
                }
                break;
            }
            literals.add(new Literal(lit));
        }
        return added;
    }

    public SATProblem parseInstance(InputStream in) throws ParseFormatException {
        scanner = new EfficientScanner(in);
        return parseInstance();
    }

    /**
     * @throws ParseFormatException if the input stream does not comply with the DIMACS format.
     */
    private SATProblem parseInstance() throws ParseFormatException {
        problem.reset();
        try {
            skipComments();
            readProblemLine();
            readConstrs();
            scanner.close();

            return problem;
        } catch (IOException e) {
            throw new ParseFormatException(e);
        } catch (NumberFormatException e) {
            throw new ParseFormatException("integer value expected ");
        }
    }

    public void decode(int[] model, PrintWriter out) {
        for (int aModel : model) {
            out.print(aModel);
            out.print(" ");
        }
        out.print("0");
    }
}
