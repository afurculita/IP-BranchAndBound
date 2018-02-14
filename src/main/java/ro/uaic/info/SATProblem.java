package ro.uaic.info;

import ro.uaic.info.TUBasedBranchAndBound.Solver;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class SATProblem {
    private final Solver solver;
    private Set<Literal> literals = new TreeSet<>();
    private List<Clause> clauses = new ArrayList<>();

    SATProblem(Solver solver) {
        this.solver = solver;
    }

    public void addClause(List<Literal> lits) {
        Clause clause = new Clause();
        clause.addAll(lits);
        literals.addAll(lits);

        clauses.add(clause);
    }

    public void reset() {
        clauses.clear();
    }

    public List<Clause> clauses() {
        return clauses;
    }

    public int m() {
        return clauses.size();
    }

    public int n() {
        return literals.size();
    }

    public boolean isSatisfiable() {
        return solver.solve(this);
    }

    public int[] model() {
        return new int[0];
    }
}
