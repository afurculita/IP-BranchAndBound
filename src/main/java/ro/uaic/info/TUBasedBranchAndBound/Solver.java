package ro.uaic.info.TUBasedBranchAndBound;

import ro.uaic.info.SATProblem;

import java.util.List;

public class Solver {
    private SATProblem SATProblem;

    public boolean solve(SATProblem problem) {
        this.SATProblem = problem;

        // Step 1. Obtain the branching order S
        List<Integer> branchingOrder = new BranchingOrderResolver().resolve(problem);

        // Step 2. The width-first search strategy is used
        // within the framework of the branch-and-bound
        // method to solve (IP1) or (IP2) according to the
        // branching rule S. At any branch node with an
        // integer optimal solution achieved, if the optimal
        // value is zero, the algorithm terminates and the SAT
        // problem is satisfiable; Otherwise, continue.

        // Step 3. If no active node exists, the algorithm
        // terminates and the SAT problem is unsatisfiable;
        // Otherwise, continue.

        // Step 4. Apply the branch-and-bound method with
        // an arbitrary branching order to the remaining
        // variables un-ranked in S.

        return true;
    }

}
