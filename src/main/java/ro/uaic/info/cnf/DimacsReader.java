package ro.uaic.info.cnf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the DIMACS CNF file format.
 *
 * <p>The accepted grammar is the one used by the SAT competitions and the SATLIB benchmarks:
 * <ul>
 *   <li>lines starting with {@code c} are comments and may appear anywhere;</li>
 *   <li>exactly one problem line {@code p cnf <variables> <clauses>} precedes the clauses;</li>
 *   <li>clauses are sequences of non-zero integers terminated by {@code 0}; a clause may span several
 *       lines and a line may contain several clauses;</li>
 *   <li>a line starting with {@code %} ends the clause section (SATLIB convention), anything after it is
 *       ignored;</li>
 *   <li>a missing terminating {@code 0} on the last clause is tolerated.</li>
 * </ul>
 * The number of clauses read must match the problem line, and no literal may reference a variable larger
 * than the declared number of variables.
 */
public final class DimacsReader {

    /** Parses a DIMACS CNF file. */
    public CnfFormula read(Path file) throws IOException, DimacsFormatException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.US_ASCII)) {
            return read(reader);
        }
    }

    /** Parses DIMACS CNF text. */
    public CnfFormula read(String dimacs) throws DimacsFormatException {
        try {
            return read(new StringReader(dimacs));
        } catch (IOException e) {
            throw new IllegalStateException("StringReader cannot fail", e);
        }
    }

    /** Parses DIMACS CNF text from a reader. The reader is not closed. */
    public CnfFormula read(Reader input) throws IOException, DimacsFormatException {
        BufferedReader reader = input instanceof BufferedReader br ? br : new BufferedReader(input);

        int numVariables = -1;
        int expectedClauses = -1;
        List<Clause> clauses = new ArrayList<>();
        List<Literal> current = new ArrayList<>();

        String line;
        int lineNumber = 0;
        boolean done = false;
        while (!done && (line = reader.readLine()) != null) {
            lineNumber++;
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.charAt(0) == 'c') {
                continue;
            }
            if (trimmed.charAt(0) == '%') {
                done = true;
                continue;
            }
            if (trimmed.charAt(0) == 'p') {
                if (numVariables >= 0) {
                    throw new DimacsFormatException("line " + lineNumber + ": duplicate problem line");
                }
                String[] tokens = trimmed.split("\\s+");
                if (tokens.length != 4 || !tokens[1].equals("cnf")) {
                    throw new DimacsFormatException(
                            "line " + lineNumber + ": expected 'p cnf <variables> <clauses>' but found '" + trimmed + "'");
                }
                numVariables = parseInt(tokens[2], lineNumber);
                expectedClauses = parseInt(tokens[3], lineNumber);
                if (numVariables < 0 || expectedClauses < 0) {
                    throw new DimacsFormatException("line " + lineNumber + ": counts must be non-negative");
                }
                continue;
            }
            if (numVariables < 0) {
                throw new DimacsFormatException("line " + lineNumber + ": clause found before the problem line");
            }
            for (String token : trimmed.split("\\s+")) {
                int value = parseInt(token, lineNumber);
                if (value == 0) {
                    if (!current.isEmpty()) {
                        clauses.add(new Clause(current));
                        current = new ArrayList<>();
                    }
                } else {
                    if (Math.abs(value) > numVariables) {
                        throw new DimacsFormatException("line " + lineNumber + ": literal " + value
                                + " references a variable beyond the declared " + numVariables);
                    }
                    current.add(Literal.of(value));
                }
            }
        }

        if (numVariables < 0) {
            throw new DimacsFormatException("missing problem line 'p cnf <variables> <clauses>'");
        }
        if (!current.isEmpty()) {
            clauses.add(new Clause(current)); // last clause without a terminating 0
        }
        if (clauses.size() != expectedClauses) {
            throw new DimacsFormatException(
                    "problem line announces " + expectedClauses + " clauses but " + clauses.size() + " were found");
        }
        return new CnfFormula(numVariables, clauses);
    }

    private static int parseInt(String token, int lineNumber) throws DimacsFormatException {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new DimacsFormatException("line " + lineNumber + ": integer expected but found '" + token + "'", e);
        }
    }
}
