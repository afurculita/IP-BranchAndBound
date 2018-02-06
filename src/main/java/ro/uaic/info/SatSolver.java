package ro.uaic.info;

import org.sat4j.minisat.SolverFactory;
import org.sat4j.reader.DimacsReader;
import org.sat4j.reader.ParseFormatException;
import org.sat4j.reader.Reader;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.IProblem;
import org.sat4j.specs.ISolver;
import org.sat4j.specs.TimeoutException;

import java.io.FileNotFoundException;
import java.io.IOException;

public class SatSolver {
    public static void main(String[] args) {
        ISolver solver = SolverFactory.newDefault();
        solver.setTimeout(3600);

        Reader reader = new DimacsReader(solver);

        // String filename = args[0];
        String filename = "datasets/UF75.325.100/uf75-01.cnf";

        try {
            IProblem problem = reader.parseInstance(filename);
            if (problem.isSatisfiable()) {
                System.out.println(" Satisfiable !");
                System.out.println(reader.decode(problem.model()));
            } else {
                System.out.println(" Unsatisfiable !");
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (ParseFormatException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ContradictionException e) {
            System.out.println(" Unsatisfiable ( trivial )!");
        } catch (TimeoutException e) {
            System.out.println(" Timeout , sorry !");
        }
    }
}
