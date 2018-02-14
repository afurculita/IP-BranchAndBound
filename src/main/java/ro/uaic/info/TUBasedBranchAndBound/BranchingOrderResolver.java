package ro.uaic.info.TUBasedBranchAndBound;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ro.uaic.info.SATProblem;

import java.util.ArrayList;
import java.util.List;

class BranchingOrderResolver {
    private static Logger LOGGER = LogManager.getLogger("BranchingOrderResolver");

    private List<Integer> S;

    public List<Integer> resolve(SATProblem problem) {
        S = new ArrayList<>(problem.n());

        LOGGER.info("Analysing IP1");
        analyseIP1(problem);

        LOGGER.info("Analysing IP2");
        analyseIP2(problem);

        if (S.size() == problem.n()) {
            return S;
        }

        for (int i = 0; i < problem.n(); i++) {
            if (S.contains(i)) {
                continue;
            }

            S.add(i);
        }

        return S;
    }

    private void analyseIP1(SATProblem problem) {
        IP1Problem ip1Problem = new IP1Problem(problem);

        LOGGER.info("Computing e for IP1");
        int[][] e = ip1Problem.e();

        int wMax;

        LOGGER.info("Starting analysing e");

        do {
            int[] w = ip1Problem.w(e);
            int wMaxIndex = 0;

            wMax = 0;

            for (int i = 0; i < w.length; i++) {
                if (w[i] > wMax) {
                    wMax = w[i];
                    wMaxIndex = i;
                }
            }

            if (wMax != 0) {
                LOGGER.info("New index added to branching order: " + wMaxIndex);

                S.add(wMaxIndex);

                for (int i = 0; i < e.length; i++) {
                    e[wMaxIndex][i] = 0;
                }
            }
        } while (wMax != 0);
    }

    private void analyseIP2(SATProblem problem) {
        IP2Problem ip2Problem = new IP2Problem(problem);

        LOGGER.info("Computing e for IP2");
        int[][][] e = ip2Problem.e();

        int wMax;

        LOGGER.info("Starting analysing e");

        do {
            int[] w = ip2Problem.w(e);
            int wMaxIndex = 0;

            wMax = 0;

            for (int i = 0; i < w.length; i++) {
                if (w[i] > wMax) {
                    wMax = w[i];
                    wMaxIndex = i;
                }
            }

            if (wMax != 0) {
                LOGGER.info("New index added to branching order: " + wMaxIndex);

                S.add(wMaxIndex);

                int[] emptyArray = new int[e.length];

                for (int j = 0; j < e.length; j++) {
                    e[wMaxIndex][j] = emptyArray;
                }
            }
        } while (wMax != 0);
    }
}
