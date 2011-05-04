package org.jdoris.core.utils;

import org.apache.log4j.Logger;
import org.jblas.ComplexDouble;
import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;
import org.jdoris.core.Window;

import static org.jblas.MatrixFunctions.pow;
import static org.jblas.MatrixFunctions.sqrt;

public class SarUtils {

    static Logger logger = Logger.getLogger(SarUtils.class.getName());

    /**
     * B=oversample(A, factorrow, factorcol);
     * 2 factors possible, extrapolation at end.
     * no vectors possible.
     */
    public static ComplexDoubleMatrix oversample(ComplexDoubleMatrix inputMatrix, final int factorRow, final int factorCol) throws IllegalArgumentException {

        final int l = inputMatrix.rows;
        final int p = inputMatrix.columns;
        final int halfL = l / 2;
        final int halfP = p / 2;
        final int L2 = factorRow * l;      // numrows of output matrix
        final int P2 = factorCol * p;      // columns of output matrix

        if (inputMatrix.isVector()) {
            logger.error("oversample: only 2d matrices.");
            throw new IllegalArgumentException();
        }
        if (!MathUtils.isPower2(l) && factorRow != 1) {
            logger.error("oversample: numlines != 2^n.");
            throw new IllegalArgumentException();
        }
        if (!MathUtils.isPower2(p) && factorCol != 1) {
            logger.error("oversample: numcols != 2^n.");
            throw new IllegalArgumentException();
        }

        final ComplexDouble half = new ComplexDouble(0.5);
        ComplexDoubleMatrix outputMatrix = new ComplexDoubleMatrix(L2, P2);


        final Window winA1;
        final Window winA2;
        final Window winR2;


        if (factorRow == 1) {

            // 1d fourier transform per row
            SpectralUtils.fft_inplace(inputMatrix, 2);

            // divide by 2 because even fftlength
            inputMatrix.putColumn(halfP, inputMatrix.getColumn(halfP).mmuli(half));
//            for (i=0; i<l; ++i) {
//                A.put(i, halfp, A.get(i, halfp).mul(half));
//            }

            // zero padding windows
            winA1 = new Window(0, l - 1, 0, halfP);
            winA2 = new Window(0, l - 1, halfP, p - 1);
            winR2 = new Window(0, l - 1, P2 - halfP, P2 - 1);

            // prepare data
            LinearAlgebraUtils.setdata(outputMatrix, winA1, inputMatrix, winA1);
            LinearAlgebraUtils.setdata(outputMatrix, winR2, inputMatrix, winA2);

            // inverse fft per row
            SpectralUtils.fft_inplace(outputMatrix, 2);

        } else if (factorCol == 1) {

            // 1d fourier transform per column
            SpectralUtils.fft_inplace(inputMatrix, 1);

            // divide by 2 'cause even fftlength
            inputMatrix.putRow(halfL, inputMatrix.getRow(halfL).mmuli(half));
//            for (i=0; i<p; ++i){
//                A(halfl,i) *= half;
//            }

            // zero padding windows
            winA1 = new Window(0, halfL, 0, p - 1);
            winA2 = new Window(halfL, l - 1, 0, p - 1);
            winR2 = new Window(L2 - halfL, L2 - 1, 0, p - 1);

            // prepare data
            LinearAlgebraUtils.setdata(outputMatrix, winA1, inputMatrix, winA1);
            LinearAlgebraUtils.setdata(outputMatrix, winR2, inputMatrix, winA2);

            // inverse fft per row
            SpectralUtils.fft_inplace(outputMatrix, 1);

        } else {

            // define extra windows for 2d unwrapping
            Window winA3;
            Window winA4;
            Window winR3;
            Window winR4;

            // A=fft2d(A)
            SpectralUtils.fft2D_inplace(inputMatrix);

            // divide by 2 'cause even fftlength
            inputMatrix.putColumn(halfP, inputMatrix.getColumn(halfP).mmuli(half));
            inputMatrix.putRow(halfL, inputMatrix.getRow(halfL).mmuli(half));
//            for (i=0; i<l; ++i) {
//                A(i,halfp) *= half;
//            }
//            for (i=0; i<p; ++i) {
//                A(halfl,i) *= half;
//            }

            // zero padding windows
            winA1 = new Window(0, halfL, 0, halfP);   // zero padding windows
            winA2 = new Window(0, halfL, halfP, p - 1);
            winA3 = new Window(halfL, l - 1, 0, halfP);
            winA4 = new Window(halfL, l - 1, halfP, p - 1);
            winR2 = new Window(0, halfL, P2 - halfP, P2 - 1);
            winR3 = new Window(L2 - halfL, L2 - 1, 0, halfP);
            winR4 = new Window(L2 - halfL, L2 - 1, P2 - halfP, P2 - 1);

            // prepare data
            LinearAlgebraUtils.setdata(outputMatrix, winA1, inputMatrix, winA1);
            LinearAlgebraUtils.setdata(outputMatrix, winR2, inputMatrix, winA2);
            LinearAlgebraUtils.setdata(outputMatrix, winR3, inputMatrix, winA3);
            LinearAlgebraUtils.setdata(outputMatrix, winR4, inputMatrix, winA4);

            // inverse back in 2d
            SpectralUtils.invfft2d_inplace(outputMatrix);
        }

        // scale
        outputMatrix.mmuli((double) (factorRow * factorCol));
        return outputMatrix;

    }

    public static DoubleMatrix intensity(ComplexDoubleMatrix inputMatrix) {
        return pow(inputMatrix.real(), 2).add(pow(inputMatrix.imag(), 2));
    }

    public static DoubleMatrix magnitude(ComplexDoubleMatrix inputMatrix) {
        return sqrt(intensity(inputMatrix));
    }

    public static ComplexDoubleMatrix coherence(ComplexDoubleMatrix inputMatrix, ComplexDoubleMatrix norms, final int winL, final int winP) {

        logger.trace("coherence ver #2");
        if (!(winL >= winP)) {
            logger.debug("coherence: estimator window size L<P not very efficiently programmed.");
        }

        if (inputMatrix.rows != norms.rows || inputMatrix.rows != inputMatrix.rows) {
            logger.debug("coherence2::not same dimensions.");
        }

        // allocate output :: account for window overlap
        ComplexDoubleMatrix outputMatrix = new ComplexDoubleMatrix(inputMatrix.rows - winL + 1, inputMatrix.columns);

        // temp variables
        int i, j, k, l;
        ComplexDouble sum;
        ComplexDouble power;
        double product;
        int leadingZeros = (winP - 1) / 2;  // number of pixels=0 floor...
        int trailingZeros = (winP) / 2;     // floor...

        for (j = leadingZeros; j < outputMatrix.columns - trailingZeros; j++) {

            sum = new ComplexDouble(0.);
            power = new ComplexDouble(0.);

            //// Compute sum over first data block ////
            for (k = 0; k < winL; k++) {
                for (l = j - leadingZeros; l < j - leadingZeros + winP; l++) {
                    sum.add(inputMatrix.get(k, l));
                    power.add(norms.get(k, l));
                }
            }

            product = power.real() * power.imag();
            outputMatrix.put(0, j, (product > 0.0) ? sum.divi(product) : new ComplexDouble(0.0));

            //// Compute (relatively) sum over rest of data blocks ////
            for (i = 0; i < outputMatrix.rows - 1; i++) {
                for (l = j - leadingZeros; l < j - leadingZeros + winP; l++) {
                    sum.add(inputMatrix.get(i + winL, l).sub(inputMatrix.get(i, l)));
                    power.add(norms.get(i + winL, l).sub(norms.get(i, l)));
                }

                product = power.real() * power.imag();
                outputMatrix.put(i + 1, j, (product > 0.0) ? sum.divi(product) : new ComplexDouble(0.0));
            }
        }
        return outputMatrix;
    }

    // TODO: how fast is this?
    public static ComplexDoubleMatrix multilook(ComplexDoubleMatrix inputMatrix, int factorRow, int factorColumn) {

        if (factorRow == 1 && factorColumn == 1) {
            return inputMatrix;
        }

        logger.debug("multilook input [inputMatrix] size: " +
                inputMatrix.length + " lines: " + inputMatrix.rows + " pixels: " + inputMatrix.columns);

        if (inputMatrix.rows / factorRow == 0 || inputMatrix.columns / factorColumn == 0) {
            logger.debug("Multilooking was not necessary for this inputMatrix: inputMatrix.rows < mlR or buffer.columns < mlC");
            return inputMatrix;
        }

        ComplexDouble sum;
        final ComplexDouble factorLP = new ComplexDouble(factorRow, factorColumn);
        ComplexDoubleMatrix outputMatrix = new ComplexDoubleMatrix(inputMatrix.rows / factorRow, inputMatrix.columns / factorColumn);
        for (int i = 0; i < inputMatrix.rows; i++) {
            for (int j = 0; j < inputMatrix.columns; j++) {
                sum = new ComplexDouble(0);
                for (int k = i * factorRow; k < (i + 1) * factorRow; k++) {
                    for (int l = j * factorColumn; l < (j + 1) * factorColumn; l++) {
                        sum.add(inputMatrix.get(k, l));
                    }
                }
                outputMatrix.put(i, j, sum.divi(factorLP));
            }
        }
        return outputMatrix;
    }

}
