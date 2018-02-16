package ro.uaic.info.TUBasedBranchAndBound;

import cern.colt.matrix.tdouble.DoubleFactory1D;
import cern.colt.matrix.tdouble.DoubleMatrix1D;
import com.joptimizer.optimizers.LPOptimizationRequest;
import com.joptimizer.optimizers.LPPrimalDualMethod;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import ro.uaic.info.Literal;
import ro.uaic.info.Clause;
import ro.uaic.info.SATProblem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class IP1Problem {
    private RealMatrix A;
    private RealVector b;

    IP1Problem(SATProblem sat) {
        this.A = new Array2DRowRealMatrix(sat.m(), sat.n());
        this.b = new ArrayRealVector(sat.m());

        int i = 0;

        for (Clause clause : sat.clauses()) {
            populateRow(i, clause);
            b.setEntry(i, clause.countComplementLiterals());

            i++;
        }
    }

    IP1Problem(IP1Problem ip1Problem, Map<Integer, Integer> predefinedVariableValues) {
        List<Integer> selectedColumnsList = new ArrayList<>();

        for (Integer i = 0; i < ip1Problem.A.getColumnDimension(); i++) {
            if (!predefinedVariableValues.containsKey(i)) {
                selectedColumnsList.add(i);
            }
        }

        int[] selectedRows = new int[ip1Problem.A.getRowDimension()];
        for (int i = 0; i < ip1Problem.A.getRowDimension(); i++) {
            selectedRows[i] = i;
        }

        this.A = ip1Problem.A.getSubMatrix(selectedRows, ArrayUtils.toPrimitive(selectedColumnsList.toArray(new Integer[0])));
        this.b = ip1Problem.b.copy();

        predefinedVariableValues.forEach((Integer column, Integer value) -> {
            if (value == 0) {
                return;
            }

            RealVector col = ip1Problem.A.getColumnVector(column);

            this.b.subtract(col);
        });
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

    private double[] getCMatrixFromGeneralForm() {
        int dimension = A.getColumnDimension();
        double[] c = new double[dimension + 1];

        c[dimension] = 1;

        return c;
    }

    /**
     * We need G.x < h
     * We now have A*x + I*s >= 1 - b
     * or -A*x - I*s < b - 1
     */
    private double[][] getGMatrixFromGeneralForm() {
        int dimension = A.getColumnDimension();
        double[][] G = new double[A.getRowDimension()][dimension + 1];

        for (int i = 0; i < A.getRowDimension(); i++) {
            for (int j = 0; j < dimension; j++) {
                G[i][j] = this.A.getEntry(i, j) * -1;
            }
        }
        for (int i = 0; i < A.getRowDimension(); i++) {
            G[i][dimension] = -1;
        }

        return G;
    }

    /**
     * We need G.x < h
     * We now have A*x + I*s >= 1 - b
     * or -A*x - I*s < b - 1
     */
    private double[] getHMatrixFromGeneralForm() {
        double[] h = new double[A.getRowDimension()];

        for (int i = 0; i < A.getRowDimension(); i++) {
            h[i] = this.b.getEntry(i) - 1;
        }

        return h;
    }

    private double[] getLowerBound() {
        int dimension = A.getColumnDimension();

        return new double[dimension + 1];
    }

    private DoubleMatrix1D getUpperBound() {
        int dimension = A.getColumnDimension();

        return DoubleFactory1D.dense.make(dimension + 1, 1);
    }

    public double solveRelaxation() {
        LPOptimizationRequest or = new LPOptimizationRequest();
        or.setC(this.getCMatrixFromGeneralForm());
        or.setG(this.getGMatrixFromGeneralForm());
        or.setH(this.getHMatrixFromGeneralForm());
        or.setLb(this.getLowerBound());
        or.setUb(this.getUpperBound());

        LPPrimalDualMethod opt = new LPPrimalDualMethod();

        opt.setLPOptimizationRequest(or);
        try {
            opt.optimize();
        } catch (Exception e) {
            e.printStackTrace();
        }

        double[] sol = opt.getOptimizationResponse().getSolution();

        return sol[sol.length - 1];
    }
}
