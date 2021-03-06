/**
 * Copyright (c) 2013, Martin Pecka (peci1@seznam.cz)
 * All rights reserved.
 * Licensed under the following BSD License.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * 
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * 
 * Neither the name Martin Pecka nor the
 * names of contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package cz.cuni.mff.peckam.ais.detection;

import static java.lang.Math.PI;
import static java.lang.Math.atan;
import static java.lang.Math.cos;
import static java.lang.Math.sin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.commons.math3.analysis.function.HarmonicOscillator.Parametric;
import org.apache.commons.math3.exception.DimensionMismatchException;
import org.apache.commons.math3.exception.NullArgumentException;
import org.apache.commons.math3.fitting.HarmonicFitter;
import org.apache.commons.math3.optim.nonlinear.vector.jacobian.LevenbergMarquardtOptimizer;
import org.apache.commons.math3.stat.descriptive.rank.Min;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.util.MathArrays;

import cz.cuni.mff.peckam.ais.AISLBLProductReader;
import cz.cuni.mff.peckam.ais.EvenlySampledIonogram;
import cz.cuni.mff.peckam.ais.Ionogram;
import cz.cuni.mff.peckam.ais.Product;
import cz.cuni.mff.peckam.ais.Tuple;

/**
 * Detector using sums of rows/columns.
 * 
 * @author Martin Pecka
 */
public class SummingDetector extends FloatFeatureDetector
{

    /** The strategy used for computing. */
    private ComputationStrategy strategy = ComputationStrategy.COMBINED_QUANTILE_PERIODOGRAM;

    /**
     * The strategy used for computation.
     * 
     * @author Martin Pecka
     */
    public enum ComputationStrategy
    {
        /**
         * Compute the period using periodogram as defined in (Scargle, 1982).
         * 
         * @author Martin Pecka
         */
        PERIODOGRAM
        {
            @Override
            Tuple<Integer, Double[]> computePeriod(float[] peaks, float[] weights)
            {
                // variable names from (Scargle, 1982)
                final int n0 = peaks.length, t = n0;
                final NavigableMap<Double, Double> periodogram = new TreeMap<>();
                // the selection of n is such that we get periods lower than width/2 and higher than the lowest
                // detectable period
                for (int n = (int) (t / getMinPeakDistance(peaks)); n <= n0 / 2; n++) {
                    final double freq = 2 * PI * n / t;
                    final double periodogramVal = computePeriodogram(freq, peaks);
                    periodogram.put(periodogramVal, freq);
                }

                if (periodogram.size() == 0)
                    return null;

                final Double[] periods = new Double[Math.min(10, periodogram.size())];
                int i = 0;
                for (double freq : periodogram.descendingMap().values()) {
                    if (i < periods.length) {
                        periods[i++] = 2 * PI / freq;
                    }
                }
                // bestFreq contains the frequency with the highest periodogram peak, which should correspond to the
                // most probable frequency
                return new Tuple<>(null, periods);
            }

            /**
             * Compute an improved periodogram value as defined in (Scargle, 1982).
             * 
             * @param freq The input frequency.
             * @param values The values to compute the periodogram for.
             * @return The periodogram value, P_X(freq)
             */
            private double computePeriodogram(double freq, float[] values)
            {
                double tau_sinSum = 0;
                double tau_cosSum = 0;
                for (int i = 1; i <= values.length; i++) {
                    tau_sinSum += sin(2 * freq * i);
                    tau_cosSum += cos(2 * freq * i);
                }

                double tau;
                if (tau_cosSum <= 10E-15) {
                    tau = 0;
                } else {
                    final double tan_tau = tau_sinSum / tau_cosSum;
                    tau = atan(tan_tau) / (2 * PI);
                }

                double cos_valSum = 0, cos_sum = 0, sin_valSum = 0, sin_sum = 0;
                for (int i = 1; i <= values.length; i++) {
                    final double cos = cos(freq * (i - tau));
                    final double sin = sin(freq * (i - tau));
                    cos_valSum += values[i - 1] * cos;
                    cos_sum += cos * cos;
                    sin_valSum += values[i - 1] * sin;
                    sin_sum += sin * sin;
                }

                if (cos_sum <= 10E-15 || sin_sum <= 10E-15)
                    return 0;
                if (cos_valSum <= 10E-15)
                    cos_valSum = 0;
                if (sin_valSum <= 10E-15)
                    sin_valSum = 0;
                return 0.5 * (cos_valSum * cos_valSum / cos_sum + sin_valSum * sin_valSum / sin_sum);
            }

            @Override
            public String toString()
            {
                return "periodogram";
            }

        },

        /**
         * Compute the period using harmonics fitting least squares.
         * 
         * @author Martin Pecka
         */
        HARMONICS_FITTING
        {

            @Override
            Tuple<Integer, Double[]> computePeriod(final float[] peaks, float[] weights)
            {
                final HarmonicFitter fitter = new HarmonicFitter(new LevenbergMarquardtOptimizer());

                // square root the weights in order to get them more equal, which helps
                float[] newWeights = new float[weights.length];
                for (int i = 0; i < weights.length; i++)
                    newWeights[i] = (float) FastMath.sqrt(weights[i]);
                newWeights = normalize(newWeights);

                // register the weighted peak values to the fitter
                for (int i = 0; i < peaks.length; i++) {
                    // the fitter requires non-zero weights
                    fitter.addObservedPoint(newWeights[i] > 0 ? newWeights[i] : 0.001, i, peaks[i]);
                }

                // the fitter cannot handle parameter constraints, so we extend the Harmonic function to return a big
                // negative number for all parameters we don't like (frequency is too big/small). We also need this to
                // be a wave with unit amplitude.
                final Parametric func = new Parametric() {
                    /** The maximum period we can detect is half of the data width. */
                    private final double minFreq = 2 * PI / (peaks.length / 2);
                    /** The minimum period we can detect. */
                    // we won't detect periods lower than the minimum distance of two peaks
                    private final double maxFreq = 2 * PI / getMinPeakDistance(peaks);

                    @Override
                    public double value(double x, double... param) throws NullArgumentException,
                            DimensionMismatchException
                    {
                        validateParameters(param);
                        if (param[0] < minFreq || param[0] > maxFreq)
                            return -1;
                        return FastMath.cos(x * param[0] + param[1]);
                    }

                    @Override
                    public double[] gradient(double x, double... param) throws NullArgumentException,
                            DimensionMismatchException
                    {
                        validateParameters(param);
                        final double dp = -FastMath.sin(x * param[0] + param[1]);
                        // try to direct the fitter to the wanted parameter values using the derivative
                        final double df = param[0] < minFreq ? 1 : param[0] > maxFreq ? -1 : dp * x;
                        return new double[] { df, dp };
                    }

                    private void validateParameters(double[] param) throws NullArgumentException,
                            DimensionMismatchException
                    {
                        if (param == null) {
                            throw new NullArgumentException();
                        }
                        if (param.length != 2) {
                            throw new DimensionMismatchException(param.length, 2);
                        }
                    }
                };

                // guess initial values for the fitting
                final double[] guess = new HarmonicFitter.ParameterGuesser(fitter.getObservations()).guess();

                // perform the fitting
                final double[] fit = fitter.fit(func, new double[] { guess[1], guess[2] });
                final double freq = (fit[0] % (2 * PI) + 2 * PI) % (2 * PI); // to get always positive freq in <0; 2pi)
                final double period = 2 * PI / freq;
                final double phase = ((fit[1] % period) + period) % period;
                // System.err.println("s=Sin[" + freq + "x+" + phase + "];");
                return new Tuple<>((int) phase, new Double[] { period });
            }

            @Override
            public String toString()
            {
                return "fitting";
            }
        },

        /**
         * Estimate the period from the quantiles of peak distances.
         * 
         * @author Martin Pecka
         */
        QUANTILE_PEAK_DISTANCE_ESTIMATION
        {

            @Override
            Tuple<Integer, Double[]> computePeriod(float[] peaks, float[] weights)
            {
                // compute peak distances and interpolate weights for them
                final List<Integer> dists = new ArrayList<>();
                final List<Float> selectedWeights = new ArrayList<>();
                int prevPeak = -1;
                for (int i = 0; i < peaks.length; i++) {
                    if (peaks[i] == 0)
                        continue;
                    if (prevPeak >= 0) {
                        dists.add(i - prevPeak);
                        selectedWeights.add((weights[i] + weights[prevPeak]) / 2);
                    }
                    prevPeak = i;
                }

                if (dists.size() == 0)
                    return null;
                else if (dists.size() == 1)
                    return new Tuple<>(null, new Double[] { (double) dists.get(0) });

                // convert to double[] for use with Commons Math
                final double[] distances = new double[dists.size()];
                final double[] newWeightsArr = new double[dists.size()];
                for (int i = 0; i < distances.length; i++) {
                    distances[i] = dists.get(i);
                    newWeightsArr[i] = selectedWeights.get(i);
                }

                // take the 0- and 65-percentils of distances, which means throw away big values;
                final double low = new Min().evaluate(distances), high = new Percentile(65)
                        .evaluate(distances);
                final double[] nearMedians = new double[distances.length / 2];
                final double[] nearMedianWeights = new double[distances.length / 2];
                for (int i = 0, j = 0; i < distances.length && j < nearMedians.length; i++) {
                    if (distances[i] >= low && distances[i] <= high) {
                        nearMedians[j] = distances[i];
                        nearMedianWeights[j] = newWeightsArr[i];
                        j++;
                    }
                }

                // perform a weighted sum of all the distances left after the previous step
                final double period = nearMedians.length > 1 ? MathArrays.linearCombination(nearMedians,
                        MathArrays.normalizeArray(nearMedianWeights, 1)) : nearMedians[0];

                return new Tuple<>(null, new Double[] { period });
            }

            @Override
            public String toString()
            {
                return "quantile";
            }
        },

        /**
         * Combined results of the quantile peak estimation and the periodogram method.
         * 
         * @author Martin Pecka
         */
        COMBINED_QUANTILE_PERIODOGRAM
        {

            @Override
            Tuple<Integer, Double[]> computePeriod(float[] peaks, float[] weights)
            {
                final Tuple<Integer, Double[]> perResult = PERIODOGRAM.computePeriod(peaks, weights);
                if (perResult == null || perResult.getY().length == 0)
                    return null;

                final Tuple<Integer, Double[]> quantResult = QUANTILE_PEAK_DISTANCE_ESTIMATION.computePeriod(peaks,
                        weights);
                if (quantResult == null || quantResult.getY().length == 0)
                    return null;

                double periodPer = perResult.getY()[0];
                double periodQuant = quantResult.getY()[0];

                // the whole multiplier of periodPer which gets it nearest to periodQuant
                int multiplier = (int) Math.round(periodQuant / periodPer);
                return new Tuple<>(null, new Double[] { 0.5 * periodQuant + 0.5 * periodPer * multiplier });
            }

            @Override
            public String toString()
            {
                return "combined";
            }

        };

        /**
         * Compute the period of the peaks possibly taking into account their weights.
         * 
         * @param peaks The normalized peaks and zeros elsewhere.
         * @param weights Weights of the peaks.
         * @return The period of the peaks. <code>null</code> if no period is present. More possible periods may be
         *         returned.
         */
        abstract Tuple<Integer, Double[]> computePeriod(float[] peaks, float[] weights);
    }

    @Override
    protected List<DetectedFeature> detectFeaturesImpl(Product<Float, ?, ?> product)
    {
        final List<DetectedFeature> result = new LinkedList<>();

        final float[][] data = prepareData(product.getData());

        {
            final Tuple<Integer, Double> horizRepeat = detectRepetition(getColumnSums(data));
            if (horizRepeat != null) {
                int offset = horizRepeat.getX() != null ? horizRepeat.getX() : 0;
                result.add(new ElectronPlasmaOscillation(offset, horizRepeat.getY(), 8));
            }
        }

        {
            final Tuple<Integer, Double> vertRepeat = detectRepetition(getRowSums(data));
            if (vertRepeat != null) {
                int offset = vertRepeat.getX() != null ? vertRepeat.getX() : 0;
                result.add(new ElectronCyclotronEchoes(offset, vertRepeat.getY(), 8));
            }
        }

        return result;
    }

    /**
     * Detect repetition in the given row/column sums.
     * 
     * @param sums The row/column sums.
     * 
     * @return <code>null</code> if no pattern has been found. Otherwise, the first entry in the tuple means offset,
     *         while the other entry means period of repetition.
     */
    private Tuple<Integer, Double> detectRepetition(float[] sums)
    {
        final int n0 = sums.length, t = n0;
        final float[] peaks = new float[t];
        final double quantile = new Percentile(60).evaluate(asDouble(sums));
        for (int i = 0; i < peaks.length; i++) {
            if (sums[i] >= quantile)
                peaks[i] = sums[i];
        }

        // only local maxima should remain in peaks
        filterPeaks(peaks);

        // make the peaks uniform and normalize their weights
        float[] weights = new float[peaks.length];
        System.arraycopy(peaks, 0, weights, 0, peaks.length);
        weights = normalize(weights);
        for (int i = 0; i < peaks.length; i++) {
            if (peaks[i] > 0) {
                peaks[i] = 1;
            }
        }

        final Tuple<Integer, Double[]> strategyResult = strategy.computePeriod(peaks, weights);
        if (strategyResult != null)
            return pickBestResult(strategyResult, sums);
        return null;
    }

    /**
     * Pick the best result.
     * 
     * @param results The results.
     * @param sums The sums.
     * @return The best result.
     */
    private Tuple<Integer, Double> pickBestResult(Tuple<Integer, Double[]> results, float[] sums)
    {
        if (results.getY().length == 1)
            return new Tuple<>(results.getX(), results.getY()[0]);

        final Double[] ys = results.getY();
        final Integer offset = results.getX();

        double bestY = 0;
        double bestValue = 0;
        for (double y : ys) {
            final double value = getPeriodQuality(offset != null ? offset : 0, y, sums);
            if (value > bestValue) {
                bestValue = value;
                bestY = y;
            }
        }

        return new Tuple<>(offset, bestY);
    }

    /**
     * @param offset offset
     * @param period period
     * @param sums sums
     * @return quality
     */
    private double getPeriodQuality(int offset, double period, float[] sums)
    {
        int repeats = 0;
        double sum = 0;
        int prevI = Integer.MIN_VALUE;
        for (double i = offset; i < sums.length; i += period) {
            if (i >= 0 && ((int) i) != prevI) { // offset may be negative
                repeats++;
                prevI = (int) i;
                sum += sums[(int) i];
            }
        }

        if (repeats == 0)
            return 0;

        return sum / repeats;
    }

    /**
     * Leave only local maxima in the data, put zeros elsewhere.
     * 
     * @param peaks The data to filter.
     */
    private void filterPeaks(float[] peaks)
    {
        // filter out all values that are not local maxima
        for (int i = 0; i < peaks.length - 1; i++) {
            if ((i == 0 || peaks[i - 1] <= peaks[i]) && peaks[i + 1] <= peaks[i]) {
                if (i > 0) { // set j to the leftmost end of a nonincreasing part left from i
                    int j = i - 1;
                    while (j > 0 && peaks[j] > 0 && peaks[j] <= peaks[j + 1])
                        j--;
                    for (; j < i; j++)
                        peaks[j] = 0;
                }

                { // here we process to the right until the sequence is nonincreasing
                  // we intentionally increase the iteration variable i!
                    final int peakI = i;
                    i++;
                    for (; i < peaks.length - 1 && peaks[i] > 0 && peaks[i] >= peaks[i + 1]; i++)
                        peaks[i] = 0;
                    peaks[peakI + 1] = 0;
                }
            }
        }
    }

    /**
     * Process the input data and do whatever is needed to be able to work on them.
     * 
     * @param data The data.
     * @return The processed data.
     */
    private float[][] prepareData(Float[][] data)
    {
        final int w = data.length, h = data[0].length;
        final float[][] result = new float[w][h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                result[x][y] = data[x][y];
            }
        }

        return result;
    }

    /**
     * Return the row sums of the given data.
     * 
     * @param data The data to sum up.
     * @return The row sums
     */
    private float[] getRowSums(float[][] data)
    {
        final int w = data.length / 2, h = data[0].length;

        final float[] result = new float[h];
        for (int y = 0; y < h; y++) {
            float sum = 0;
            for (int x = 0; x < w; x++) {
                sum += data[x][y];
            }
            result[y] = sum;
        }
        return result;
    }

    /**
     * Return the column sums of the given data.
     * 
     * @param data The data to sum up.
     * @return The column sums
     */
    private float[] getColumnSums(float[][] data)
    {
        final int w = data.length / 2, h = data[0].length;

        final float[] result = new float[w];
        for (int x = 0; x < w; x++) {
            float sum = 0;
            for (int y = 0; y < h; y++) {
                sum += data[x][y];
            }
            result[x] = sum;
        }
        return result;
    }

    /**
     * Set the computation strategy.
     * 
     * @param strategy The strategy to use.
     */
    public void setStrategy(ComputationStrategy strategy)
    {
        this.strategy = strategy;
    }

    /**
     * @return The computation strategy.
     */
    public ComputationStrategy getStrategy()
    {
        return strategy;
    }

    /**
     * Normalize the data.
     * 
     * @param data The data to normalize.
     * @return The normalized copy of data.
     */
    private static float[] normalize(float[] data)
    {
        float sum = 0;
        for (float f : data)
            sum += f;
        final float[] result = new float[data.length];
        for (int i = 0; i < result.length; i++)
            result[i] = data[i] / sum;
        return result;
    }

    /**
     * Return the minimal horizontal distance between peaks.
     * 
     * @param peaks The peaks array.
     * @return The minimal distance between peaks.
     */
    private static float getMinPeakDistance(float[] peaks)
    {
        float minPeakDistance = Float.MAX_VALUE;
        int prevPeak = -1;
        for (int i = 0; i < peaks.length; i++) {
            if (peaks[i] == 0)
                continue;
            if (prevPeak >= 0) {
                if (i - prevPeak < minPeakDistance)
                    minPeakDistance = i - prevPeak;
            }
            prevPeak = i;
        }
        return minPeakDistance;
    }

    /**
     * Test the detector on the given ionogram.
     * 
     * @param args 0 =&gt; Orbit file, 1 =&gt; position of the ionogram in the file.
     * 
     * @throws IOException On IO exception.
     */
    public static void main(String[] args) throws IOException
    {
        final File orbitFile = new File(args[0]);
        final int position = Integer.parseInt(args[1]);

        final Ionogram ionogram = new EvenlySampledIonogram(new AISLBLProductReader().readFile(orbitFile)[position]);
        System.out.println(new SummingDetector().detectFeatures(ionogram));
    }


    /**
     * @param a the array
     * @return the array
     */
    private static double[] asDouble(float[] a)
    {
        final double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i];
        }
        return r;
    }
}
