package ro.uaic.info.tubb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import ro.uaic.info.cnf.CnfFormula;
import ro.uaic.info.lp.LinearProgramSolver;
import ro.uaic.info.lp.SimplexLpSolver;

/**
 * The TU-based branch-and-bound method for SAT (Algorithm 1 of the paper), with optional extensions.
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
 * <p>Extensions beyond the paper, all configurable through {@link Options}:
 * <ul>
 *   <li>a warm-started dual simplex for the relaxations ({@link RelaxationSolverFactory#dualSimplex()});</li>
 *   <li>{@linkplain Propagation unit propagation and pure-literal elimination} after each fixing;</li>
 *   <li>{@linkplain BranchingRule#DYNAMIC dynamic ranking}: the non-TU degrees are recomputed on the reduced
 *       formula of each node;</li>
 *   <li>depth-first search and parallel node evaluation by several worker threads.</li>
 * </ul>
 *
 * <p>Instances are immutable and thread-safe.
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

    /** How the branching variable of a node is chosen. */
    public enum BranchingRule {
        /** The paper's rule: the next free variable of the static order {@code S} computed once at the root. */
        STATIC,
        /**
         * Procedure 1 is re-run on the reduced formula of the node and its top-ranked variable is used;
         * when every non-TU degree is zero the static order is used as fallback.
         */
        DYNAMIC
    }

    /**
     * Solver configuration.
     *
     * @param relaxationSolvers creates the relaxation solver of each worker thread
     * @param strategy          node expansion order
     * @param branchingRule     static or dynamic choice of the branching variable
     * @param propagation       apply unit propagation and pure-literal elimination at every node
     * @param threads           number of worker threads evaluating nodes
     * @param maxNodes          maximum number of nodes to explore before giving up with
     *                          {@link SatResult.Status#UNKNOWN}; {@link Long#MAX_VALUE} for no limit
     * @param tolerance         numerical tolerance for zero and integrality tests on LP solutions
     */
    public record Options(RelaxationSolverFactory relaxationSolvers, SearchStrategy strategy, BranchingRule branchingRule,
                          boolean propagation, int threads, long maxNodes, double tolerance) {
        public Options {
            Objects.requireNonNull(relaxationSolvers, "relaxationSolvers");
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(branchingRule, "branchingRule");
            if (threads <= 0) {
                throw new IllegalArgumentException("threads must be positive");
            }
            if (maxNodes <= 0) {
                throw new IllegalArgumentException("maxNodes must be positive");
            }
            if (tolerance < 0 || tolerance >= 0.5) {
                throw new IllegalArgumentException("tolerance must be in [0, 0.5)");
            }
        }

        /**
         * Warm-started dual simplex, breadth-first search, static branching order, propagation on, one
         * thread, no node limit, tolerance {@code 1e-6}.
         */
        public static Options defaults() {
            return new Options(RelaxationSolverFactory.dualSimplex(), SearchStrategy.BREADTH_FIRST,
                    BranchingRule.STATIC, true, 1, Long.MAX_VALUE, SimplexLpSolver.DEFAULT_EPSILON);
        }

        /** The method exactly as in the paper: no propagation, static order, breadth-first, one thread. */
        public static Options paper() {
            return defaults().withPropagation(false);
        }

        public Options withRelaxationSolvers(RelaxationSolverFactory factory) {
            return new Options(factory, strategy, branchingRule, propagation, threads, maxNodes, tolerance);
        }

        /** Uses a general-purpose LP solver through a {@link RebuildingRelaxationSolver}. */
        public Options withLpSolver(LinearProgramSolver lpSolver) {
            Objects.requireNonNull(lpSolver, "lpSolver");
            return withRelaxationSolvers(RelaxationSolverFactory.rebuilding(lpSolver));
        }

        public Options withStrategy(SearchStrategy newStrategy) {
            return new Options(relaxationSolvers, newStrategy, branchingRule, propagation, threads, maxNodes, tolerance);
        }

        public Options withBranchingRule(BranchingRule rule) {
            return new Options(relaxationSolvers, strategy, rule, propagation, threads, maxNodes, tolerance);
        }

        public Options withPropagation(boolean enabled) {
            return new Options(relaxationSolvers, strategy, branchingRule, enabled, threads, maxNodes, tolerance);
        }

        public Options withThreads(int count) {
            return new Options(relaxationSolvers, strategy, branchingRule, propagation, count, maxNodes, tolerance);
        }

        public Options withMaxNodes(long newMaxNodes) {
            return new Options(relaxationSolvers, strategy, branchingRule, propagation, threads, newMaxNodes, tolerance);
        }
    }

    /**
     * A node of the search tree.
     *
     * @param fixed     the fixings (branching decisions plus propagated variables)
     * @param nextIndex position in the static order from which the next branching variable is searched
     */
    private record Node(PartialAssignment fixed, int nextIndex) {
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
        return new Search(formula, order, start).run();
    }

    /** Decides the satisfiability of the formula using a caller-supplied (static) branching order. */
    public SatResult solve(CnfFormula formula, BranchingOrder order) {
        if (order.size() != formula.numVariables()) {
            throw new IllegalArgumentException(
                    "branching order ranks " + order.size() + " variables, formula has " + formula.numVariables());
        }
        return new Search(formula, order, System.nanoTime()).run();
    }

    /** State shared by the worker threads of one solve. */
    private final class Search {
        private final CnfFormula formula;
        private final BranchingOrder order;
        private final long start;

        private final ConcurrentLinkedDeque<Node> queue = new ConcurrentLinkedDeque<>();
        private final AtomicInteger queueSize = new AtomicInteger();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<boolean[]> model = new AtomicReference<>();
        private final AtomicBoolean nodeLimitHit = new AtomicBoolean();

        private final AtomicLong explored = new AtomicLong();
        private final AtomicLong lpSolved = new AtomicLong();
        private final AtomicLong falsified = new AtomicLong();
        private final AtomicLong pruned = new AtomicLong();
        private final AtomicLong propagated = new AtomicLong();
        private final AtomicInteger maxQueue = new AtomicInteger(1);

        Search(CnfFormula formula, BranchingOrder order, long start) {
            this.formula = formula;
            this.order = order;
            this.start = start;
        }

        SatResult run() {
            push(new Node(PartialAssignment.empty(formula.numVariables()), 0), true);
            if (options.threads() == 1) {
                work();
            } else {
                runParallel();
            }
            SatResult.Status status;
            if (model.get() != null) {
                status = SatResult.Status.SATISFIABLE;
            } else if (nodeLimitHit.get()) {
                status = SatResult.Status.UNKNOWN;
            } else {
                status = SatResult.Status.UNSATISFIABLE;
            }
            return new SatResult(status, Optional.ofNullable(model.get()), statistics(), order);
        }

        private void runParallel() {
            ExecutorService pool = Executors.newFixedThreadPool(options.threads(), runnable -> {
                Thread thread = new Thread(runnable, "tubb-worker");
                thread.setDaemon(true);
                return thread;
            });
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < options.threads(); i++) {
                    futures.add(pool.submit(this::work));
                }
                for (Future<?> future : futures) {
                    future.get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished.set(true);
                throw new IllegalStateException("interrupted while solving", e);
            } catch (ExecutionException e) {
                finished.set(true);
                Throwable cause = e.getCause();
                throw cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
            } finally {
                pool.shutdownNow();
            }
        }

        /** Worker loop; runs on the calling thread when {@code threads == 1}. */
        private void work() {
            Ip1Relaxation relaxation = new Ip1Relaxation(formula, options.relaxationSolvers().create(formula), options.tolerance());
            while (!finished.get()) {
                inFlight.incrementAndGet();
                Node node = queue.pollFirst();
                if (node == null) {
                    inFlight.decrementAndGet();
                    // Termination is sound: a node can only be added by a worker counted in inFlight, so
                    // reading inFlight == 0 before an empty queue means no node is left or forthcoming.
                    if (inFlight.get() == 0 && queue.isEmpty()) {
                        return;
                    }
                    LockSupport.parkNanos(20_000);
                    continue;
                }
                queueSize.decrementAndGet();
                try {
                    process(node, relaxation);
                } finally {
                    inFlight.decrementAndGet();
                }
            }
        }

        private void process(Node node, Ip1Relaxation relaxation) {
            long count = explored.incrementAndGet();
            if (count > options.maxNodes()) {
                nodeLimitHit.set(true);
                finished.set(true);
                return;
            }

            PartialAssignment fixed = node.fixed();
            if (options.propagation()) {
                Propagation.Outcome outcome = Propagation.propagate(formula, fixed, true);
                propagated.addAndGet(outcome.fixedCount());
                if (outcome.isConflict()) {
                    falsified.incrementAndGet();
                    return;
                }
                fixed = outcome.assignment();
            }

            Ip1Relaxation.Result result = relaxation.solve(fixed);
            if (result.lpSolved()) {
                lpSolved.incrementAndGet();
            }
            LOGGER.trace("node {} {}: {} value={}", count, fixed, result.status(), result.value());
            switch (result.status()) {
                case FALSIFIED -> falsified.incrementAndGet();
                case POSITIVE -> pruned.incrementAndGet();
                case INTEGRAL -> {
                    if (model.compareAndSet(null, result.assignment())) {
                        LOGGER.debug("integral relaxation with {} fixed variables after {} nodes", fixed.numFixed(), count);
                    }
                    finished.set(true);
                }
                case FRACTIONAL -> branch(node, fixed);
            }
            if (count % 1000 == 0) {
                LOGGER.info("{} nodes explored, {} active, {} fixed at the current node", count, queueSize.get(), fixed.numFixed());
            }
        }

        private void branch(Node node, PartialAssignment fixed) {
            int nextIndex = node.nextIndex();
            while (nextIndex < order.size() && !fixed.isFree(order.variableAt(nextIndex))) {
                nextIndex++;
            }
            int variable;
            if (options.branchingRule() == BranchingRule.DYNAMIC) {
                variable = dynamicVariable(fixed);
                if (variable < 0) {
                    variable = staticVariable(nextIndex);
                }
            } else {
                variable = staticVariable(nextIndex);
            }
            Node zero = new Node(fixed.fix(variable, false), nextIndex);
            Node one = new Node(fixed.fix(variable, true), nextIndex);
            if (options.strategy() == SearchStrategy.BREADTH_FIRST) {
                push(zero, false);
                push(one, false);
            } else {
                push(one, true);
                push(zero, true);
            }
        }

        private int staticVariable(int nextIndex) {
            if (nextIndex >= order.size()) {
                // Cannot happen: with every variable fixed the relaxation is trivially integral or falsified.
                throw new IllegalStateException("fractional relaxation with all variables fixed");
            }
            return order.variableAt(nextIndex);
        }

        /** Top-ranked variable of Procedure 1 on the reduced formula, or {@code -1} if all degrees are zero. */
        private int dynamicVariable(PartialAssignment fixed) {
            BranchingOrder local = BranchingOrder.compute(Propagation.reduce(formula, fixed));
            if (!local.ip1Ranked().isEmpty()) {
                return local.ip1Ranked().get(0);
            }
            if (!local.ip2Ranked().isEmpty()) {
                return local.ip2Ranked().get(0);
            }
            return -1;
        }

        private void push(Node node, boolean first) {
            if (first) {
                queue.addFirst(node);
            } else {
                queue.addLast(node);
            }
            int size = queueSize.incrementAndGet();
            maxQueue.accumulateAndGet(size, Math::max);
        }

        private SatResult.Statistics statistics() {
            return new SatResult.Statistics(Math.min(explored.get(), options.maxNodes()), lpSolved.get(), falsified.get(),
                    pruned.get(), propagated.get(), maxQueue.get(), Duration.ofNanos(System.nanoTime() - start));
        }
    }
}
