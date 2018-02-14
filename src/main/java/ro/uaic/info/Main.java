package ro.uaic.info;

import org.sat4j.reader.ParseFormatException;
import ro.uaic.info.TUBasedBranchAndBound.Solver;

import java.io.IOException;
import java.io.PrintWriter;

public class Main {
    public static void main(String[] args) throws IOException, ParseFormatException {
        DimacsReader reader = new DimacsReader(new Solver());

        // String filename = args[0];
        String filename = "datasets/UF75.325.100/uf75-01.cnf";

        SATProblem problem = reader.parseInstance(filename);
        if (!problem.isSatisfiable()) {
            System.out.println(" Unsatisfiable !");
            return;
        }

        PrintWriter out = new PrintWriter(System.out, true);

        reader.decode(problem.model(), out);

        out.flush();
        out.close();
    }
}
