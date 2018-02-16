package ro.uaic.info.TUBasedBranchAndBound;

import ro.uaic.info.SATProblem;

import java.util.*;

public class Solver {
    private List<Integer> branchingOrder;
    private IP1Problem ip1Problem;

    public boolean solve(SATProblem problem) {
        ip1Problem = new IP1Problem(problem);

        // Obtain the branching order S
        branchingOrder = new BranchingOrderResolver().resolve(problem, ip1Problem);

        // The width-first search strategy is used
        // within the framework of the branch-and-bound
        // method to solve (IP1) or (IP2) according to the
        // branching rule S. At any branch node with an
        // integer optimal solution achieved, if the optimal
        // value is zero, the algorithm terminates and the SAT
        // problem is satisfiable; Otherwise, continue.

        // If no active node exists, the algorithm
        // terminates and the SAT problem is unsatisfiable;
        // Otherwise, continue.

        // Apply the branch-and-bound method with
        // an arbitrary branching order to the remaining
        // variables un-ranked in S.

        Node result = branchAndBound();

        return result.bound == 0.0;
    }

    private Node branchAndBound() {
        Node best = new Node();

        Node root = new Node();
        root.computeBound();

        if (root.bound == 0.0) {
            best = root;

            return best;
        }

        PriorityQueue<Node> q = new PriorityQueue<>();
        q.offer(root);

        while (!q.isEmpty()) {
            Node node = q.poll();

            if (branchingOrder.size() < node.h + 1) {
                break;
            }

            int orderKey = branchingOrder.get(node.h);

            Node zero = new Node(node);
            zero.taken.put(orderKey, 0);

            zero.computeBound();

            if (zero.bound > best.bound) {
                q.offer(zero);
            } else {
                best = zero;

                if (best.bound == 0.0) break;
            }

            Node one = new Node(node);
            zero.taken.put(orderKey, 1);

            one.computeBound();

            if (one.bound > best.bound) {
                q.offer(one);
            } else {
                best = one;

                if (best.bound == 0.0) break;
            }
        }

        return best;
    }

    private class Node implements Comparable<Node> {
        int h = 0;
        Map<Integer, Integer> taken;
        double bound;

        Node() {
            taken = new HashMap<>();
        }

        Node(Node parent) {
            h = parent.h + 1;
            taken = new HashMap<>(parent.taken);
            bound = parent.bound;
        }

        public int compareTo(Node other) {
            return (int) (bound - other.bound);
        }

        void computeBound() {
            bound = ip1Problem.solveWith(taken);
        }
    }
}
