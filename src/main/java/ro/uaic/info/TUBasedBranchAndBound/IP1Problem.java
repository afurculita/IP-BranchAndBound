package ro.uaic.info.TUBasedBranchAndBound;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import ro.uaic.info.Literal;
import ro.uaic.info.Clause;
import ro.uaic.info.SATProblem;

class IP1Problem {
    private RealMatrix A;

    IP1Problem(SATProblem SATProblem) {
        this.A = new Array2DRowRealMatrix(SATProblem.m(), SATProblem.n());

        int i = 0;

        for (Clause clause : SATProblem.clauses()) {
            populateRow(i, clause);

            i++;
        }
    }

    private void populateRow(int i, Clause clause) {
        for (Literal lit : clause) {
            A.setEntry(i, lit.index(), lit.sign());
        }
    }

    /**
     * non-TU degree contributed by coupling xi and xj
     */
    public int[][] e() {
        int[][] e = new int[A.getColumnDimension()][A.getColumnDimension()];

        for (int i = 0; i < A.getColumnDimension(); i++) {
            double[] colI = A.getColumn(i);
            for (int j = i + 1; j < A.getColumnDimension(); j++) {
                double[] colJ = A.getColumn(j);

                e[i][j] = countSameRows(colI, colJ) * countDifferentRows(colI, colJ);
                e[j][i] = e[i][j];
            }
        }

        return e;
    }

    public int[] w(int[][] e) {
        int[] w = new int[e.length];

        for (int i = 0; i < e.length; i++) {
            w[i] = 0;

            for (int j = 0; j < e.length; j++) {
                w[i] += e[i][j];
            }
        }

        return w;
    }

    private int countDifferentRows(double[] col1, double[] col2) {
        int count = 0;

        for (int i = 0; i < col1.length; i++) {
            if (col1[i] == 1 && col2[i] == 1)
                count++;
            if (col1[i] == -1 && col2[i] == -1)
                count++;
        }

        return count;
    }

    private int countSameRows(double[] col1, double[] col2) {
        int count = 0;

        for (int i = 0; i < col1.length; i++) {
            if (col1[i] == 1 && col2[i] == -1)
                count++;
            if (col1[i] == -1 && col2[i] == 1)
                count++;
        }

        return count;
    }
}
