package ro.uaic.info.cnf;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A propositional formula in conjunctive normal form (CNF): a conjunction of {@link Clause}s over the
 * variables {@code x1 .. xn}.
 *
 * <p>Instances are immutable. Tautological clauses are dropped on construction because they are satisfied
 * by every assignment and would introduce a zero row in the (IP1) constraint matrix. Variables that do
 * not occur in any clause are still counted in {@link #numVariables()} (they are free).
 */
public final class CnfFormula {

    private final int numVariables;
    private final List<Clause> clauses;

    /**
     * @param numVariables number of variables {@code n} (as declared on the DIMACS {@code p cnf} line)
     * @param clauses      the clauses; tautologies are discarded
     * @throws IllegalArgumentException if {@code numVariables} is negative or a clause mentions a variable
     *                                  larger than {@code numVariables}
     */
    public CnfFormula(int numVariables, List<Clause> clauses) {
        if (numVariables < 0) {
            throw new IllegalArgumentException("numVariables must be non-negative, got " + numVariables);
        }
        List<Clause> kept = new ArrayList<>(clauses.size());
        for (Clause c : clauses) {
            if (c.maxVariable() > numVariables) {
                throw new IllegalArgumentException(
                        "clause " + c + " uses variable x" + c.maxVariable() + " but only " + numVariables
                                + " variables are declared");
            }
            if (!c.isTautology()) {
                kept.add(c);
            }
        }
        this.numVariables = numVariables;
        this.clauses = List.copyOf(kept);
    }

    /** Builds a formula from DIMACS-encoded clauses, e.g. {@code CnfFormula.of(3, new int[]{1, -3}, new int[]{2, 3, -1})}. */
    public static CnfFormula of(int numVariables, int[]... clauses) {
        return new CnfFormula(numVariables, Clause.listOf(clauses));
    }

    /** Number of variables {@code n}. */
    public int numVariables() {
        return numVariables;
    }

    /** Number of (non-tautological) clauses {@code m}. */
    public int numClauses() {
        return clauses.size();
    }

    /** The clauses, in input order. */
    public List<Clause> clauses() {
        return clauses;
    }

    /**
     * Evaluates the formula under a complete assignment.
     *
     * @param assignment {@code assignment[i]} is the value of variable {@code i + 1}; must have length
     *                   {@link #numVariables()}
     */
    public boolean isSatisfiedBy(boolean[] assignment) {
        if (assignment.length != numVariables) {
            throw new IllegalArgumentException(
                    "assignment has " + assignment.length + " values, expected " + numVariables);
        }
        return clauses.stream().allMatch(c -> c.isSatisfiedBy(assignment));
    }

    /** Returns the formula serialised in DIMACS CNF format. */
    public String toDimacs() {
        StringBuilder sb = new StringBuilder();
        sb.append("p cnf ").append(numVariables).append(' ').append(clauses.size()).append('\n');
        for (Clause c : clauses) {
            for (int lit : c.toDimacs()) {
                sb.append(lit).append(' ');
            }
            sb.append("0\n");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        if (clauses.isEmpty()) {
            return "⊤";
        }
        return clauses.stream().map(Clause::toString).collect(Collectors.joining(" ∧ "));
    }
}
