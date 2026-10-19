package ro.uaic.info.cnf;

/** Thrown when an input does not comply with the DIMACS CNF format. */
public class DimacsFormatException extends Exception {

    private static final long serialVersionUID = 1L;

    public DimacsFormatException(String message) {
        super(message);
    }

    public DimacsFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
