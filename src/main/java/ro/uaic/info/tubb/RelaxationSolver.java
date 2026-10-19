package ro.uaic.info.tubb;

import ro.uaic.info.lp.LpSolution;

/**
 * Solves the linear relaxation of (IP1) for the sub-problem defined by a partial assignment:
 * <pre>
 *   min 1ᵀs   s.t.  A x + s ≥ 1 − b,   xⱼ fixed to its value when assigned, 0 ≤ xⱼ ≤ 1 otherwise,   s ≥ 0
 * </pre>
 * Implementations may keep state between calls (a warm-started basis, for instance) and are therefore
 * <strong>not</strong> required to be thread-safe; the search creates one instance per worker thread
 * through a {@link RelaxationSolverFactory}.
 *
 * <p>Implementations must return a <em>vertex</em> of the feasible polyhedron (see
 * {@link ro.uaic.info.lp.LinearProgramSolver}).
 */
public interface RelaxationSolver {

    /**
     * @return the optimal value and the optimal {@code x}, indexed by variable ({@code n} entries, fixed
     *         variables at their fixed value)
     * @throws ro.uaic.info.lp.LpException if the relaxation cannot be solved
     */
    LpSolution solve(PartialAssignment fixed);
}
