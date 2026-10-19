package ro.uaic.info.lp;

/**
 * Solves linear programs to optimality.
 *
 * <p>The branch-and-bound method of the paper relies on the total unimodularity of the (reduced) constraint
 * matrix: when the matrix is TU every <em>vertex</em> of the feasible polyhedron is integral. Implementations
 * must therefore return a vertex (basic) optimal solution, as the simplex method does. Interior-point methods,
 * which converge to the analytic centre of the optimal face, are not suitable.
 */
public interface LinearProgramSolver {

    /**
     * Minimises the program.
     *
     * @throws LpException if the program is infeasible or unbounded, or if the solver fails to converge
     */
    LpSolution minimize(LinearProgram program);
}
