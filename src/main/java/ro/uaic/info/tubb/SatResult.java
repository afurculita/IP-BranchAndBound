package ro.uaic.info.tubb;

import java.time.Duration;
import java.util.Optional;

/**
 * Outcome of a {@link TuBranchAndBoundSolver#solve} call.
 *
 * @param status         whether the formula is satisfiable, unsatisfiable or undecided (node limit reached)
 * @param model          a satisfying assignment when {@link Status#SATISFIABLE} ({@code model[i]} is the
 *                       value of variable {@code i + 1})
 * @param statistics     search statistics
 * @param branchingOrder the static branching order that was used (or used as fallback)
 */
public record SatResult(Status status, Optional<boolean[]> model, Statistics statistics, BranchingOrder branchingOrder) {

    public enum Status { SATISFIABLE, UNSATISFIABLE, UNKNOWN }

    /**
     * Search statistics.
     *
     * @param nodesExplored       nodes taken from the queue and evaluated
     * @param lpSolved            nodes whose linear relaxation was actually solved
     * @param nodesFalsified      nodes found infeasible by propagation or a falsified clause, without an LP
     * @param nodesPruned         nodes pruned because their relaxation had a positive value
     * @param propagatedVariables variables fixed by unit propagation and pure-literal elimination
     * @param maxQueueSize        largest number of active nodes held at once
     * @param elapsed             wall-clock time of the whole solve, including the branching order
     */
    public record Statistics(long nodesExplored, long lpSolved, long nodesFalsified, long nodesPruned,
                             long propagatedVariables, int maxQueueSize, Duration elapsed) {
    }

    public boolean isSatisfiable() {
        return status == Status.SATISFIABLE;
    }

    /** DIMACS encoding of the model ({@code v} lines content), e.g. {@code [1, -2, 3]}. */
    public int[] modelAsDimacs() {
        boolean[] m = model.orElseThrow(() -> new IllegalStateException("no model: status is " + status));
        int[] lits = new int[m.length];
        for (int i = 0; i < m.length; i++) {
            lits[i] = m[i] ? i + 1 : -(i + 1);
        }
        return lits;
    }
}
