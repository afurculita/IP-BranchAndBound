package ro.uaic.info;

import org.apache.log4j.BasicConfigurator;
import org.sat4j.reader.ParseFormatException;
import ro.uaic.info.TUBasedBranchAndBound.Solver;

import java.io.IOException;
import java.io.PrintWriter;

public class Main {
    public static void main(String[] args) throws IOException, ParseFormatException {
        BasicConfigurator.configure();
        DimacsReader reader = new DimacsReader(new Solver());

        // String filename = args[0];
        String filename = "datasets/quinn.cnf";

        SATProblem problem = reader.parseInstance(filename);
        if (problem.isSatisfiable()) {
            System.out.println(" Satisfiable !");
        } else {
            System.out.println(" Unsatisfiable !");
        }
    }
}
