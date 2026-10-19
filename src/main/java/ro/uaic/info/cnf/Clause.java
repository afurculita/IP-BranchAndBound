package ro.uaic.info.cnf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A disjunction of literals.
 *
 * <p>A clause is immutable. Duplicate literals are removed on construction (as in the paper, "each literal
 * occurs only once"), which keeps the constraint matrix a 0-±1 matrix. A clause containing both a variable
 * and its complement is a {@linkplain #isTautology() tautology} and is always satisfied.
 */
public final class Clause {

    private final List<Literal> literals;

    /**
     * @param literals the literals of the clause; duplicates are dropped, order of first occurrence is kept
     * @throws IllegalArgumentException if the clause is empty
     */
    public Clause(List<Literal> literals) {
        if (literals.isEmpty()) {
            throw new IllegalArgumentException("a clause must contain at least one literal");
        }
        this.literals = List.copyOf(new LinkedHashSet<>(literals));
    }

    /** Creates a clause from DIMACS-encoded literals, e.g. {@code Clause.of(1, -3)} for {@code x1 ∨ ¬x3}. */
    public static Clause of(int... dimacsLiterals) {
        List<Literal> lits = new ArrayList<>(dimacsLiterals.length);
        for (int d : dimacsLiterals) {
            lits.add(Literal.of(d));
        }
        return new Clause(lits);
    }

    /** The distinct literals of this clause, in order of first occurrence. */
    public List<Literal> literals() {
        return literals;
    }

    /** Number of distinct literals. */
    public int size() {
        return literals.size();
    }

    /** Number of complement (negated) literals: the entry {@code b_i} of the (IP1) right-hand side. */
    public int countNegated() {
        return (int) literals.stream().filter(Literal::negated).count();
    }

    /** Whether the clause contains some variable both positively and negatively. */
    public boolean isTautology() {
        return literals.stream().anyMatch(l -> literals.contains(l.negate()));
    }

    /** Largest variable number occurring in the clause. */
    public int maxVariable() {
        return literals.stream().mapToInt(Literal::variable).max().orElseThrow();
    }

    /**
     * Evaluates the clause under a complete assignment.
     *
     * @param assignment {@code assignment[i]} is the value of variable {@code i + 1}
     */
    public boolean isSatisfiedBy(boolean[] assignment) {
        return literals.stream().anyMatch(l -> l.isSatisfiedBy(assignment[l.index()]));
    }

    /** DIMACS encoding of the clause, without the terminating {@code 0}. */
    public int[] toDimacs() {
        return literals.stream().mapToInt(Literal::toDimacs).toArray();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Clause other && literals.equals(other.literals);
    }

    @Override
    public int hashCode() {
        return literals.hashCode();
    }

    @Override
    public String toString() {
        return literals.stream().map(Literal::toString).collect(Collectors.joining(" ∨ ", "(", ")"));
    }

    /** Convenience for tests and examples: a list of clauses from DIMACS-encoded literal arrays. */
    public static List<Clause> listOf(int[]... clauses) {
        return Collections.unmodifiableList(
                IntStream.range(0, clauses.length).mapToObj(i -> Clause.of(clauses[i])).toList());
    }
}
