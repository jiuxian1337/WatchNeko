package cc.watchneko.utils.math;

import cc.watchneko.utils.lists.Tuple;

import java.util.*;
import java.util.function.Function;

public final class MathUtil {
    public static final double MINIMUM_DIVISOR = ((Math.pow(0.2f, 3) * 8) * 0.15) - 1e-3;
    public static final double EXPANDER = Math.pow(2.0, 24.0);

    private MathUtil() {
    }

    public static double getAverage(Collection<? extends Number> values) {
        double sum = 0.0;
        int cnt = 0;
        for (Number n : values) {
            sum += n.doubleValue();
            cnt++;
        }
        return cnt == 0 ? 0.0 : sum / cnt;
    }

    public static double getAverage(List<Double> data) {
        double sum = 0;
        for (double v : data) sum += v;
        return data.isEmpty() ? 0.0 : sum / data.size();
    }

    public static double getAverageDouble(final Collection<Double> nums) {
        return nums.isEmpty() ? 0 : getSumDouble(nums) / nums.size();
    }

    private static double getSumDouble(final Collection<Double> nums) {
        double sum = 0D;
        for (double v : nums) sum += v;
        return sum;
    }

    public static double getStandardDeviation(Collection<? extends Number> doubles) {
        double sq = getVariance(doubles);
        return doubles.isEmpty() ? 0.0 : Math.sqrt(sq / doubles.size());
    }

    public static double getStandardDeviation(List<Double> data) {
        return stdDev(data);
    }

    public static double getVariance(final Collection<? extends Number> data) {
        if (data.isEmpty()) {
            return 0.0;
        }
        double mean = getAverage(data);
        double var = 0.0;
        for (Number n : data) {
            double diff = n.doubleValue() - mean;
            var += diff * diff;
        }
        return var / data.size();
    }

    public static double getVariance(List<Double> data) {
        if (data.isEmpty()) return 0.0;
        double mean = getAverage(data);
        double var = 0.0;
        for (double v : data) {
            var += Math.pow(v - mean, 2);
        }
        return var / data.size();
    }

    public static double getKurtosis(final Collection<? extends Number> data) {
        int n = data.size();
        if (n < 3) return 0.0;
        double mean = getAverage(data);
        double m2 = 0, m4 = 0;
        for (Number num : data) {
            double diff = mean - num.doubleValue();
            m2 += diff * diff;
            m4 += diff * diff * diff * diff;
        }
        return (n * (n + 1.0) * m4 / (m2 * m2) - 3.0 * (n - 1.0)) / ((n - 1.0) * (n - 2.0) * (n - 3.0) / (double) (n * n));
    }

    public static List<Double> computeDerivatives(List<Double> data) {
        List<Double> diffs = new ArrayList<>(Math.max(0, data.size() - 1));
        for (int i = 0; i < data.size() - 1; i++) {
            diffs.add(data.get(i + 1) - data.get(i));
        }
        return diffs;
    }

    public static double stdDev(Collection values) {
        return stdDev(values, mean(values));
    }

    public static double stdDev(Collection values, double mean) {
        int size = values.size();
        if (size < 2) return 0.0;
        double sumSq = 0;
        for (Object v : values) {
            double diff = ((Number) v).doubleValue() - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / (size - 1));
    }

    public static double stdDev(double sum, double squareSum, int validSamples) {
        if (validSamples <= 0) return 0.0;
        double mean = sum / validSamples;
        return Math.sqrt(squareSum / validSamples - mean * mean);
    }

    public static double mean(Collection<?> values) {
        if (values == null || values.isEmpty()) return 0.0;
        double sum = 0.0;
        int cnt = 0;
        for (Object v : values) {
            sum += ((Number) v).doubleValue();
            cnt++;
        }
        return sum / cnt;
    }

    public static Tuple<List<Double>, List<Double>> getOutliers(final Collection<? extends Number> collection) {
        List<Double> vals = new ArrayList<>();
        for (Number n : collection) vals.add(n.doubleValue());
        Collections.sort(vals);
        double q1 = getMedian(vals.subList(0, vals.size() / 2));
        double q3 = getMedian(vals.subList(vals.size() / 2, vals.size()));
        double iqr = Math.abs(q1 - q3);
        double low = q1 - 1.5 * iqr;
        double high = q3 + 1.5 * iqr;
        Tuple<List<Double>, List<Double>> tuple = new Tuple<>(new ArrayList<>(), new ArrayList<>());
        for (double v : vals) {
            if (v < low) tuple.x().add(v);
            else if (v > high) tuple.y().add(v);
        }
        return tuple;
    }

    public static double getMedian(final List<Double> data) {
        int size = data.size();
        return (size % 2 == 0) ? (data.get(size / 2) + data.get(size / 2 - 1)) / 2.0 : data.get(size / 2);
    }

    public static double roundToPlace(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    public static List<Float> getJiffDelta(List<? extends Number> data, int depth) {
        List<Float> result = new ArrayList<>();
        for (Number n : data) result.add(n.floatValue());
        for (int d = 0; d < depth; d++) {
            List<Float> next = new ArrayList<>();
            float prev = result.get(0);
            for (int i = 1; i < result.size(); i++) {
                float cur = result.get(i);
                next.add(Math.abs(Math.abs(cur) - Math.abs(prev)));
                prev = cur;
            }
            result = next;
        }
        return result;
    }

    public static int getDistinct(final Collection<? extends Number> data) {
        Set<Number> set = new HashSet<>(data);
        return set.size();
    }

    public static long getGcd(final long current, final long previous) {
        return (previous <= 16384L) ? current : getGcd(previous, current % previous);
    }

    public static double getGcd(final double a, final double b) {
        if (a == b) return 0;
        if (a < b) return getGcd(b, a);
        if (Math.abs(b) < 1E-5) return a;
        return getGcd(b, a - Math.floor(a / b) * b);
    }

    public static double getMin(final Collection<? extends Number> collection) {
        double min = Double.MAX_VALUE;
        for (Number n : collection) min = Math.min(min, n.doubleValue());
        return min;
    }

    public static double getMax(final Collection<? extends Number> collection) {
        double max = Double.MIN_VALUE;
        for (Number n : collection) max = Math.max(max, n.doubleValue());
        return max;
    }

    public static float getAngleDifference(final float a, final float b) {
        return wrapAngleTo180_float(wrapAngleTo180_float(a) - wrapAngleTo180_float(b));
    }

    public static float wrapAngleTo180_float(float value) {
        value = value % 360.0F;
        if (value >= 180.0F) {
            value -= 360.0F;
        }
        if (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }

    public static double calculateEntropy(List<Float> data, int bins) {
        if (data.isEmpty()) return 0.0;
        float min = Collections.min(data);
        float max = Collections.max(data);
        float range = max - min;
        if (range == 0) return 0.0;
        int[] binCounts = new int[bins];
        float binSize = range / bins;
        for (float value : data) {
            int binIndex = (int) ((value - min) / binSize);
            if (binIndex >= bins) binIndex = bins - 1;
            binCounts[binIndex]++;
        }
        double entropy = 0.0;
        int total = data.size();
        for (int count : binCounts) {
            if (count > 0) {
                double probability = (double) count / total;
                entropy -= probability * (Math.log(probability) / Math.log(2));
            }
        }
        return entropy;
    }

    public static float getGCDValueStatistic(double s) {
        return getGCD(s) * 0.15F;
    }

    public static float getGCD(double s) {
        float f1 = (float) (s * 0.6 + 0.2);
        return f1 * f1 * f1 * 8.0F;
    }

    public static double calculateNEntropy(List<Float> data) {
        Map<String, Long> map = new HashMap<>();
        for (Float v : data) {
            map.merge(String.format("%.1f", v), 1L, Long::sum);
        }
        double sum = 0.0;
        for (Long c : map.values()) {
            double p = (double) c / data.size();
            double v = -p * (Math.log(p) / Math.log(2));
            sum += v;
        }
        return sum;
    }

    public static int getDuplicates(final Collection<? extends Number> data) {
        return data.size() - getDistinct(data);
    }

    public static double kolmogorovSmirnovTest(final List<? extends Number> data, Function<Double, Double> cdfFunction) {
        List<Double> sorted = new ArrayList<>(data.size());
        for (Number n : data) {
            sorted.add(n.doubleValue());
        }
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        if (n == 0) {
            return 0.0;
        }
        double dStatistic = 0.0;
        for (int i = 0; i < n; i++) {
            double empiricalCDF = (i + 1) / (double) n;
            double theoreticalCDF = cdfFunction.apply(sorted.get(i));
            dStatistic = Math.max(dStatistic, Math.abs(empiricalCDF - theoreticalCDF));
        }
        return dStatistic;
    }

    public static double getIQR(final Collection<? extends Number> data) {
        if (data.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(data.size());
        for (Number n : data) {
            sorted.add(n.doubleValue());
        }
        sorted.sort(Double::compareTo);
        return calculatePercentileSorted(sorted, 75) - calculatePercentileSorted(sorted, 25);
    }

    private static double calculatePercentileSorted(List<Double> sortedValues, double percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        if (index < 0) index = 0;
        if (index >= sortedValues.size()) index = sortedValues.size() - 1;
        return sortedValues.get(index);
    }

    public static double getAngleInDegrees(Vec2f delta) {
        double angleInRadians = Math.atan2(delta.x(), delta.y());
        double angleInDegrees = Math.toDegrees(angleInRadians);
        if (angleInDegrees < 0) {
            angleInDegrees += 360;
        }
        return angleInDegrees;
    }

    public static double scaleVal(double value, double scale) {
        double scale2 = Math.pow(10, scale);
        return Math.ceil(value * scale2) / scale2;
    }

    public static double gcd(double a, double b) {
        if (a == 0) return 0;
        if (a < b) {
            double tmp = a;
            a = b;
            b = tmp;
        }
        while (b > MINIMUM_DIVISOR) {
            double tmp = a - Math.floor(a / b) * b;
            a = b;
            b = tmp;
        }
        return a;
    }

    public static float getGCDValue(double s) {
        return getGCD(s) * 0.15F;
    }

    public static double getShannonEntropy(final Collection<? extends Number> data) {
        Map<Double, Long> freq = new HashMap<>();
        for (Number n : data) {
            double v = n.doubleValue();
            freq.put(v, freq.getOrDefault(v, 0L) + 1);
        }
        double total = data.size();
        double entropy = 0.0;
        for (long c : freq.values()) {
            double p = c / total;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    public static boolean isExponentiallySmall(final Number number) {
        return number.doubleValue() < 1 && String.valueOf(number.doubleValue()).contains("E");
    }

    public static List<Double> getZScoreOutliers(final Collection<? extends Number> data, final float threshold) {
        List<Double> values = new ArrayList<>();
        for (Number number : data) {
            values.add(number.doubleValue());
        }
        if (values.size() < 2) {
            return Collections.emptyList();
        }
        double mean = mean(values);
        double std = stdDev(values, mean);
        if (std == 0.0) {
            return Collections.emptyList();
        }
        List<Double> outliers = new ArrayList<>();
        for (double value : values) {
            double z = (value - mean) / std;
            if (Math.abs(z) > threshold) {
                outliers.add(z);
            }
        }
        return outliers;
    }

    public static double getPearsonCorrelation(final Collection<? extends Number> first, final Collection<? extends Number> second) {
        if (first.size() != second.size() || first.size() < 2) {
            return 0.0;
        }
        List<Double> x = new ArrayList<>(first.size());
        List<Double> y = new ArrayList<>(second.size());
        for (Number number : first) x.add(number.doubleValue());
        for (Number number : second) y.add(number.doubleValue());
        return pearsonCorrelation(
                x.stream().mapToDouble(Double::doubleValue).toArray(),
                y.stream().mapToDouble(Double::doubleValue).toArray()
        );
    }

    public static double pearsonCorrelation(final double[] x, final double[] y) {
        if (x.length != y.length || x.length < 2) {
            return 0.0;
        }
        double meanX = 0.0;
        double meanY = 0.0;
        for (int i = 0; i < x.length; i++) {
            meanX += x[i];
            meanY += y[i];
        }
        meanX /= x.length;
        meanY /= y.length;

        double covariance = 0.0;
        double varianceX = 0.0;
        double varianceY = 0.0;
        for (int i = 0; i < x.length; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            covariance += dx * dy;
            varianceX += dx * dx;
            varianceY += dy * dy;
        }
        if (varianceX == 0.0 || varianceY == 0.0) {
            return 0.0;
        }
        return covariance / Math.sqrt(varianceX * varianceY);
    }

    public static double entropy(final Collection<? extends Number> data) {
        return getShannonEntropy(data);
    }
}
