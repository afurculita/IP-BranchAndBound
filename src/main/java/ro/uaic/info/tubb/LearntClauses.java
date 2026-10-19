package ro.uaic.info.tubb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ro.uaic.info.cnf.Clause;

/**
 * Thread-safe database of learnt clauses shared by the workers of one search.
 *
 * <p>Every clause stored here is implied by the original formula (it was derived by resolution from
 * original clauses, or is the negation of a set of decisions proven infeasible), so adding it to the
 * propagation of any node is sound. Clauses longer than {@code maxLength} and duplicates are rejected, and
 * the database stops growing at {@code maxClauses}.
 */
public final class LearntClauses {

    private final int maxLength;
    private final int maxClauses;
    private final List<Clause> clauses = new ArrayList<>();
    private final Set<Set<Integer>> seen = new HashSet<>();
    private volatile Clause[] snapshot = new Clause[0];

    public LearntClauses(int maxLength, int maxClauses) {
        this.maxLength = maxLength;
        this.maxClauses = maxClauses;
    }

    /** Adds a clause; returns {@code false} if it was too long, a duplicate, or the database is full. */
    public synchronized boolean add(Clause clause) {
        if (clause.size() > maxLength || clauses.size() >= maxClauses) {
            return false;
        }
        Set<Integer> key = new HashSet<>();
        for (int lit : clause.toDimacs()) {
            key.add(lit);
        }
        if (!seen.add(key)) {
            return false;
        }
        clauses.add(clause);
        snapshot = clauses.toArray(new Clause[0]);
        return true;
    }

    /** The current clauses (a consistent array that is never modified). */
    public Clause[] snapshot() {
        return snapshot;
    }

    public int size() {
        return snapshot.length;
    }
}
