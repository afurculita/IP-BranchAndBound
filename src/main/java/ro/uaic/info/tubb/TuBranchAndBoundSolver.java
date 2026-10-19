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

import ro.uaic.info.cnf.Clause;
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
 *   <li>{@linkplain BranchingRule per-node branching rules}: dynamic non-TU ranking of the reduced problem,
 *       or the Jeroslow–Wang heuristic;</li>
 *   <li>{@linkplain ConflictAnalyzer clause learning} from propagation conflicts and relaxation prunes;</li>
 *   <li>depth-first search and parallel node evaluation by several worker threads, with work stealing.</li>
 * </ul>
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class TuBranchAndBoundSolver {

    private static final Logger LOGGER = LogManager.getLogger(TuBranchAndBoundSolver.class);

    /** Longest learnt clause kept, and the size of the learnt clause database. */
    static final int MAX_LEARNT_LENGTH = 12;
    static final int MAX_LEARNT_CLAUSES = 20_000;

    /** Order in which active nodes are expanded. */
    public enum SearchStrategy {
        /** The paper's strategy: nodes are expanded level by level (FIFO queue shared by all workers). */
        BREADTH_FIRST,
        /**
         * Nodes are expanded deepest first (LIFO), the {@code x = 0} child before the {@code x = 1} child.
         * Each worker owns a stack and steals the shallowest node of another worker when its own is empty.
         */
        DEPTH_FIRST
    }

    /** How the branching variable of a node is chosen. */
    public enum BranchingRule {
        /** The paper's rule: the next free variable of the static order {@code S} computed once at the root. */
        STATIC,
        /**
         * The variable with the largest non-TU degree on the reduced formula of the node (pair degrees of
         * (IP1), then triple degrees of (IP2)); ties and the all-zero case fall back to Jeroslow–Wang.
         */
        DYNAMIC,
        /** The two-sided Jeroslow–Wang heuristic, a classic SAT branching rule, for comparison. */
        JEROSLOW_WANG
    }

    /**
     * Solver configuration.
     *
     * @param relaxationSolvers creates the relaxation solver of each worker thread
     * @param strategy          node expansion order
     * @param branchingRule     choice of the branching variable
     * @param propagation       apply unit propagation and pure-literal elimination at every node
     * @param learning          learn clauses from conflicts and prunes, and propagate with them
     * @param threads           number of worker threads evaluating nodes
     * @param maxNodes          maximum number of nodes to explore before giving up with
     *                          {@link SatResult.Status#UNKNOWN}; {@link Long#MAX_VALUE} for no limit
     * @param maxTriangles      bound on the (IP2) triangle enumeration of the ranking procedures
     * @param tolerance         numerical tolerance for zero and integrality tests on LP solutions
     */
    public record Options(RelaxationSolverFactory relaxationSolvers, SearchStrategy strategy, BranchingRule branchingRule,
                          boolean propagation, boolean learning, int threads, long maxNodes, long maxTriangles,
                          double tolerance) {
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
            if (maxTriangles <= 0) {
                throw new IllegalArgumentException("maxTriangles must be positive");
            }
            if (tolerance < 0 || tolerance >= 0.5) {
                throw new IllegalArgumentException("tolerance must be in [0, 0.5)");
            }
            if (learning && !propagation) {
                throw new IllegalArgumentException("learning requires propagation");
            }
        }

        /**
         * Revised dual simplex, breadth-first search, static branching order, propagation and learning on,
         * one thread, no node limit, two million triangles, tolerance {@code 1e-6}.
         */
        public static Options defaults() {
            return new Options(RelaxationSolverFactory.dualSimplex(), SearchStrategy.BREADTH_FIRST,
                    BranchingRule.STATIC, true, true, 1, Long.MAX_VALUE, 2_000_000L, SimplexLpSolver.DEFAULT_EPSILON);
        }

        /** The method exactly as in the paper: no propagation or learning, static order, breadth-first, one thread. */
        public static Options paper() {
            return defaults().withLearning(false).withPropagation(false);
        }

        public Options withRelaxationSolvers(RelaxationSolverFactory factory) {
            return new Options(factory, strategy, branchingRule, propagation, learning, threads, maxNodes, maxTriangles, tolerance);
        }

        /** Uses a general-purpose LP solver through a {@link RebuildingRelaxationSolver}. */
        public Options withLpSolver(LinearProgramSolver lpSolver) {
            Objects.requireNonNull(lpSolver, "lpSolver");
            return withRelaxationSolvers(RelaxationSolverFactory.rebuilding(lpSolver));
        }

        public Options withStrategy(SearchStrategy newStrategy) {
            return new Options(relaxationSolvers, newStrategy, branchingRule, propagation, learning, threads, maxNodes, maxTriangles, tolerance);
        }

        public Options withBranchingRule(BranchingRule rule) {
            return new Options(relaxationSolvers, strategy, rule, propagation, learning, threads, maxNodes, maxTriangles, tolerance);
        }

        /** Disabling propagation also disables learning. */
        public Options withPropagation(boolean enabled) {
            return new Options(relaxationSolvers, strategy, branchingRule, enabled, learning && enabled, threads, maxNodes, maxTriangles, tolerance);
        }

        public Options withLearning(boolean enabled) {
            return new Options(relaxationSolvers, strategy, branchingRule, propagation, enabled, threads, maxNodes, maxTriangles, tolerance);
        }

        public Options withThreads(int count) {
            return new Options(relaxationSolvers, strategy, branchingRule, propagation, learning, count, maxNodes, maxTriangles, tolerance);
        }

        public Options withMaxNodes(long newMaxNodes) {
            return new Options(relaxationSolvers, strategy, branchingRule, propagation, learning, threads, newMaxNodes, maxTriangles, tolerance);
        }

        public Options withMaxTriangles(long newMaxTriangles) {
            return new Options(relaxationSolvers, strategy, branchingRule, propagation, learning, threads, maxNodes, newMaxTriangles, tolerance);
        }
    }

    /**
     * A node of the search tree.
     *
     * @param fixed     the fixings (branching decisions plus propagated variables)
     * @param nextIndex position in the static order from which the next branching variable is searched
     * @param level     decision level of every fixed variable ({@code null} unless learning)
     * @param reason    forcing clause of every fixed variable, {@code null} for decisions ({@code null} unless learning)
     * @param maxLevel  the highest decision level of the node
     */
    private record Node(PartialAssignment fixed, int nextIndex, int[] level, Clause[] reason, int maxLevel) {
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
        BranchingOrder order = BranchingOrder.compute(formula, options.maxTriangles());
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
        private final int workers;

        /** BFS: one shared FIFO queue. DFS: one LIFO stack per worker, stolen from the other end. */
        private final ConcurrentLinkedDeque<Node> shared = new ConcurrentLinkedDeque<>();
        private final List<ConcurrentLinkedDeque<Node>> stacks = new ArrayList<>();
        private final AtomicInteger queueSize = new AtomicInteger();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<boolean[]> model = new AtomicReference<>();
        private final AtomicBoolean nodeLimitHit = new AtomicBoolean();
        private final LearntClauses learnt;

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
            this.workers = options.threads();
            for (int w = 0; w < workers; w++) {
                stacks.add(new ConcurrentLinkedDeque<>());
            }
            this.learnt = options.learning() ? new LearntClauses(MAX_LEARNT_LENGTH, MAX_LEARNT_CLAUSES) : null;
        }

        SatResult run() {
            int n = formula.numVariables();
            Node root = new Node(PartialAssignment.empty(n), 0,
                    options.learning() ? new int[n] : null, options.learning() ? new Clause[n] : null, 0);
            push(0, root);
            if (workers == 1) {
                work(0);
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
            ExecutorService pool = Executors.newFixedThreadPool(workers, runnable -> {
                Thread thread = new Thread(runnable, "tubb-worker");
                thread.setDaemon(true);
                return thread;
            });
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int w = 0; w < workers; w++) {
                    int id = w;
                    futures.add(pool.submit(() -> work(id)));
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

        /** Worker loop; runs on the calling thread when there is a single worker. */
        private void work(int id) {
            Ip1Relaxation relaxation = new Ip1Relaxation(formula, options.relaxationSolvers().create(formula), options.tolerance());
            while (!finished.get()) {
                inFlight.incrementAndGet();
                Node node = take(id);
                if (node == null) {
                    inFlight.decrementAndGet();
                    // Termination is sound: a node can only be added by a worker counted in inFlight, so
                    // reading inFlight == 0 before finding every queue empty means no node is left or forthcoming.
                    if (inFlight.get() == 0 && allEmpty()) {
                        return;
                    }
                    LockSupport.parkNanos(20_000);
                    continue;
                }
                queueSize.decrementAndGet();
                try {
                    process(id, node, relaxation);
                } finally {
                    inFlight.decrementAndGet();
                }
            }
        }

        private Node take(int id) {
            if (options.strategy() == SearchStrategy.BREADTH_FIRST) {
                return shared.pollFirst();
            }
            Node node = stacks.get(id).pollFirst();
            if (node != null) {
                return node;
            }
            // steal the shallowest (largest) subtree of another worker
            for (int k = 1; k < workers; k++) {
                node = stacks.get((id + k) % workers).pollLast();
                if (node != null) {
                    return node;
                }
            }
            return null;
        }

        private boolean allEmpty() {
            if (!shared.isEmpty()) {
                return false;
            }
            for (ConcurrentLinkedDeque<Node> stack : stacks) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private void push(int id, Node node) {
            if (options.strategy() == SearchStrategy.BREADTH_FIRST) {
                shared.addLast(node);
            } else {
                stacks.get(id).addFirst(node);
            }
            int size = queueSize.incrementAndGet();
            maxQueue.accumulateAndGet(size, Math::max);
        }

        private void process(int id, Node node, Ip1Relaxation relaxation) {
            long count = explored.incrementAndGet();
            if (count > options.maxNodes()) {
                nodeLimitHit.set(true);
                finished.set(true);
                return;
            }

            PartialAssignment fixed = node.fixed();
            int[] level = node.level();
            Clause[] reason = node.reason();
            int maxLevel = node.maxLevel();
            if (options.propagation()) {
                Clause[] learntSnapshot = learnt == null ? new Clause[0] : learnt.snapshot();
                Propagation.Outcome outcome = Propagation.propagate(formula, learntSnapshot, fixed, true);
                propagated.addAndGet(outcome.fixedCount());
                int[] trail = outcome.trailVariables();
                Clause[] trailReasons = outcome.trailReasons();
                if (learnt != null) {
                    // assign levels: implied variables share the latest level, pure literals open a new one
                    for (int t = 0; t < trail.length; t++) {
                        if (trailReasons[t] == null) {
                            maxLevel++;
                        }
                        level[trail[t]] = maxLevel;
                        reason[trail[t]] = trailReasons[t];
                    }
                }
                fixed = outcome.assignment();
                if (outcome.isConflict()) {
                    falsified.incrementAndGet();
                    if (learnt != null) {
                        Clause clause = ConflictAnalyzer.firstUip(outcome.conflict(), fixed, level, reason, trail, maxLevel);
                        if (clause != null && learnt.add(clause)) {
                            LOGGER.trace("learnt {} from a conflict at level {}", clause, maxLevel);
                        }
                    }
                    return;
                }
            }

            Ip1Relaxation.Result result = relaxation.solve(fixed);
            if (result.lpSolved()) {
                lpSolved.incrementAndGet();
            }
            LOGGER.trace("node {} {}: {} value={}", count, fixed, result.status(), result.value());
            switch (result.status()) {
                case FALSIFIED -> falsified.incrementAndGet();
                case POSITIVE -> {
                    pruned.incrementAndGet();
                    if (learnt != null) {
                        Clause clause = ConflictAnalyzer.noGood(fixed, level, reason);
                        if (clause != null && learnt.add(clause)) {
                            LOGGER.trace("learnt no-good {} from a positive relaxation", clause);
                        }
                    }
                }
                case INTEGRAL -> {
                    if (model.compareAndSet(null, result.assignment())) {
                        LOGGER.debug("integral relaxation with {} fixed variables after {} nodes", fixed.numFixed(), count);
                    }
                    finished.set(true);
                }
                case FRACTIONAL -> branch(id, node, fixed, level, reason, maxLevel);
            }
            if (count % 1000 == 0) {
                LOGGER.info("{} nodes explored, {} active, {} fixed at the current node, {} learnt clauses",
                        count, queueSize.get(), fixed.numFixed(), learnt == null ? 0 : learnt.size());
            }
        }

        private void branch(int id, Node node, PartialAssignment fixed, int[] level, Clause[] reason, int maxLevel) {
            int nextIndex = node.nextIndex();
            while (nextIndex < order.size() && !fixed.isFree(order.variableAt(nextIndex))) {
                nextIndex++;
            }
            int variable = -1;
            if (options.branchingRule() != BranchingRule.STATIC) {
                variable = NodeRanking.choose(formula, fixed, options.branchingRule(), options.maxTriangles());
            }
            if (variable < 0) {
                variable = staticVariable(nextIndex);
            }
            Node zero = child(fixed, variable, false, nextIndex, level, reason, maxLevel);
            Node one = child(fixed, variable, true, nextIndex, level, reason, maxLevel);
            if (options.strategy() == SearchStrategy.BREADTH_FIRST) {
                push(id, zero);
                push(id, one);
            } else {
                push(id, one);
                push(id, zero);
            }
        }

        private Node child(PartialAssignment fixed, int variable, boolean value, int nextIndex,
                           int[] level, Clause[] reason, int maxLevel) {
            int[] childLevel = null;
            Clause[] childReason = null;
            if (level != null) {
                childLevel = level.clone();
                childReason = reason.clone();
                childLevel[variable] = maxLevel + 1;
                childReason[variable] = null;
            }
            return new Node(fixed.fix(variable, value), nextIndex, childLevel, childReason, maxLevel + 1);
        }

        private int staticVariable(int nextIndex) {
            if (nextIndex >= order.size()) {
                // Cannot happen: with every variable fixed the relaxation is trivially integral or falsified.
                throw new IllegalStateException("fractional relaxation with all variables fixed");
            }
            return order.variableAt(nextIndex);
        }

        private SatResult.Statistics statistics() {
            return new SatResult.Statistics(Math.min(explored.get(), options.maxNodes()), lpSolved.get(), falsified.get(),
                    pruned.get(), propagated.get(), learnt == null ? 0 : learnt.size(), maxQueue.get(),
                    Duration.ofNanos(System.nanoTime() - start));
        }
    }
}
