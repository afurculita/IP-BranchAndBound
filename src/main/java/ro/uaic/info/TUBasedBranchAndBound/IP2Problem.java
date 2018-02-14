package ro.uaic.info.TUBasedBranchAndBound;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import ro.uaic.info.Literal;
import ro.uaic.info.Clause;
import ro.uaic.info.SATProblem;

class IP2Problem {
    private RealMatrix A;
    private int i = 0;

    IP2Problem(SATProblem SATProblem) {
        this.A = new Array2DRowRealMatrix(SATProblem.m(), 2 * SATProblem.n());

        for (Clause clause : SATProblem.clauses()) {
            populateRow(clause);
        }
    }

    private void populateRow(Clause clause) {
        for (Literal lit : clause) {
            A.setEntry(i, lit.index(true), 1);
        }
        i++;
    }

    public int[][][] e() {
        int n = A.getColumnDimension();
        int[][][] e = new int[n][n][n];

        int[] v1 = {0, 1, 1};
        int[] v2 = {1, 0, 1};
        int[] v3 = {1, 1, 0};

        for (int i = 0; i < n; i++) {
            double[] colI = A.getColumn(i);
            for (int j = i + 1; j < n; j++) {
                double[] colJ = A.getColumn(j);
                for (int k = j + 1; k < n; k++) {
                    double[] colK = A.getColumn(k);

                    int count = countRows(colI, colJ, colK, v1)
                            * countRows(colI, colJ, colK, v2)
                            * countRows(colI, colJ, colK, v3);

                    e[i][j][k] = e[i][k][j] = e[j][i][k] = e[j][k][i] = e[k][i][j] = e[k][j][i] = count;
                }
            }
        }

        return e;
    }

    private int countRows(double[] colI, double[] colJ, double[] colK, int[] v) {
        int count = 0;

        for (int i = 0; i < colI.length; i++) {
            if (colI[i] == v[0] && colJ[i] == v[1] && colK[i] == v[2]) {
                count++;
            }
        }

        return count;
    }

    public int[] w(int[][][] e) {
        int[] w = new int[e.length];

        for (int i = 0; i < e.length; i++) {
            w[i] = 0;

            for (int j = 0; j < e.length; j++) {
                for (int k = 0; k < e.length; k++) {
                    w[i] += e[i][j][k];
                }
            }
        }

        return w;
    }
}
