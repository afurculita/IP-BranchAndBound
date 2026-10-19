package ro.uaic.info.tubb;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.lp.LinearProgramSolver;
import ro.uaic.info.lp.SimplexLpSolver;

/**
 * The TU-based branch-and-bound method for SAT (Algorithm 1 of the paper).
 *
 * <ol>
 *   <li>The formula is modelled as (IP1); its satisfiability is equivalent to the optimal value being
 *       zero (Theorem 1).</li>
 *   <li>The {@link BranchingOrder} {@code S} is computed (Procedure 1).</li>
 *   <li>A width-first search fixes the variables in the order {@code S}. At every node the linear
 *       relaxation of the sub-problem is solved. A node is pruned when its relaxation is positive (the
 *       integer optimum is then at least 1). A zero-valued integral relaxation is a satisfying assignment
 *       and the search stops. Otherwise the node is split on the next variable of {@code S}.</li>
 *   <li>If the queue of active nodes becomes empty the formula is unsatisfiable.</li>
 * </ol>
 * Fixing the highly ranked variables first tends to make the reduced constraint matrix totally unimodular,
 * so that the relaxations become integral early and little further branching is needed.
 *
 * <p>Instances are immutable and thread-safe provided the configured {@link LinearProgramSolver} is.
 */
public final class TuBranchAndBoundSolver {

    private static final Logger LOGGER = LogManager.getLogger(TuBranchAndBoundSolver.class);

    /** Order in which active nodes are expanded. */
    public enum SearchStrategy {
        /** The paper's strategy: nodes are expanded level by level (FIFO queue). */
        BREADTH_FIRST,
        /** Nodes are expanded deepest first (LIFO), the {@code x = 0} child before the {@code x = 1} child. */
        DEPTH_FIRST
    }

    /**
     * Solver configuration.
     *
     * @param lpSolver  the LP solver for the relaxations; must return vertex solutions
     * @param strategy  node expansion order
     * @param maxNodes  maximum number of nodes to explore before giving up with {@link SatResult.Status#UNKNOWN};
     *                  {@link Long#MAX_VALUE} for no limit
     * @param tolerance numerical tolerance for zero and integrality tests on LP solutions
     */
    public record Options(LinearProgramSolver lpSolver, SearchStrategy strategy, long maxNodes, double tolerance) {
        public Options {
            Objects.requireNonNull(lpSolver, "lpSolver");
            Objects.requireNonNull(strategy, "strategy");
            if (maxNodes <= 0) {
                throw new IllegalArgumentException("maxNodes must be positive");
            }
            if (tolerance < 0 || tolerance >= 0.5) {
                throw new IllegalArgumentException("tolerance must be in [0, 0.5)");
            }
        }

        /** Simplex LP solver, breadth-first search, no node limit, tolerance {@code 1e-6}. */
        public static Options defaults() {
            return new Options(new SimplexLpSolver(), SearchStrategy.BREADTH_FIRST, Long.MAX_VALUE,
                    SimplexLpSolver.DEFAULT_EPSILON);
        }

        public Options withStrategy(SearchStrategy newStrategy) {
            return new Options(lpSolver, newStrategy, maxNodes, tolerance);
        }

        public Options withMaxNodes(long newMaxNodes) {
            return new Options(lpSolver, strategy, newMaxNodes, tolerance);
        }

        public Options withLpSolver(LinearProgramSolver newSolver) {
            return new Options(newSolver, strategy, maxNodes, tolerance);
        }
    }

    private record Node(PartialAssignment fixed, int depth) {
    }

    private final Options options;

    public TuBranchAndBoundSolver() {
        this(Options.defaults());
    }

    public TuBranchAndBoundSolver(Options options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    public Options options() {
        return options;
    }

    /** Decides the satisfiability of the formula. */
    public SatResult solve(CnfFormula formula) {
        long start = System.nanoTime();
        BranchingOrder order = BranchingOrder.compute(formula);
        LOGGER.debug("branching order: {}", order);
        return solve(formula, order, start);
    }

    /** Decides the satisfiability of the formula using a caller-supplied branching order. */
    public SatResult solve(CnfFormula formula, BranchingOrder order) {
        return solve(formula, order, System.nanoTime());
    }

    private SatResult solve(CnfFormula formula, BranchingOrder order, long start) {
        if (order.size() != formula.numVariables()) {
            throw new IllegalArgumentException(
                    "branching order ranks " + order.size() + " variables, formula has " + formula.numVariables());
        }
        Ip1Relaxation relaxation = new Ip1Relaxation(formula, options.lpSolver(), options.tolerance());

        Deque<Node> active = new ArrayDeque<>();
        active.add(new Node(PartialAssignment.empty(formula.numVariables()), 0));

        long explored = 0;
        long lpSolved = 0;
        long falsified = 0;
        long pruned = 0;
        int maxQueue = 1;

        while (!active.isEmpty()) {
            if (explored >= options.maxNodes()) {
                LOGGER.info("node limit of {} reached", options.maxNodes());
                return new SatResult(SatResult.Status.UNKNOWN, Optional.empty(),
                        stats(explored, lpSolved, falsified, pruned, maxQueue, start), order);
            }
            Node node = active.pollFirst();
            explored++;

            Ip1Relaxation.Result result = relaxation.solve(node.fixed());
            LOGGER.trace("node {} depth {} {}: {} value={}", explored, node.depth(), node.fixed(), result.status(), result.value());
            switch (result.status()) {
                case FALSIFIED -> falsified++;
                case POSITIVE -> {
                    lpSolved++;
                    pruned++;
                }
                case INTEGRAL -> {
                    if (result.numRows() > 0) {
                        lpSolved++;
                    }
                    LOGGER.debug("integral relaxation at depth {} after {} nodes", node.depth(), explored);
                    return new SatResult(SatResult.Status.SATISFIABLE, Optional.of(result.assignment()),
                            stats(explored, lpSolved, falsified, pruned, maxQueue, start), order);
                }
                case FRACTIONAL -> {
                    lpSolved++;
                    if (node.depth() >= order.size()) {
                        // Cannot happen: with every variable fixed the relaxation is trivially integral.
                        throw new IllegalStateException("fractional relaxation with all variables fixed");
                    }
                    int variable = order.variableAt(node.depth());
                    Node zero = new Node(node.fixed().fix(variable, false), node.depth() + 1);
                    Node one = new Node(node.fixed().fix(variable, true), node.depth() + 1);
                    if (options.strategy() == SearchStrategy.BREADTH_FIRST) {
                        active.addLast(zero);
                        active.addLast(one);
                    } else {
                        active.addFirst(one);
                        active.addFirst(zero);
                    }
                    maxQueue = Math.max(maxQueue, active.size());
                }
            }
            if (explored % 1000 == 0) {
                LOGGER.info("{} nodes explored, {} active, depth {}", explored, active.size(), node.depth());
            }
        }
        return new SatResult(SatResult.Status.UNSATISFIABLE, Optional.empty(),
                stats(explored, lpSolved, falsified, pruned, maxQueue, start), order);
    }

    private static SatResult.Statistics stats(long explored, long lpSolved, long falsified, long pruned,
                                              int maxQueue, long start) {
        return new SatResult.Statistics(explored, lpSolved, falsified, pruned, maxQueue,
                Duration.ofNanos(System.nanoTime() - start));
    }
}
