package ro.uaic.info.lp;

/** Signals that a linear program could not be solved (infeasible, unbounded or numerical failure). */
public class LpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LpException(String message, Throwable cause) {
        super(message, cause);
    }
}
