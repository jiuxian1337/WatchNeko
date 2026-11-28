package cc.watchneko.checks.impl.aim;

import cc.watchneko.checks.impl.aim.AimTrajectoryTracker.Frame;
import cc.watchneko.checks.impl.aim.AimTrajectoryTracker.TargetAngles;
import cc.watchneko.utils.math.MathUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class AimPeriodicity {
    static final int WINDOW_TICKS = 96;
    private static final int HALF_WINDOW = WINDOW_TICKS / 2;
    private static final double PERIOD = 20.08;
    private static final int BASELINE_TERMS = 4;
    private static final int PERIODIC_TERMS = 6;

    private AimPeriodicity() {}

    static Result evaluate(List<Frame> frames, double quantum) {
        List<Sample> training = new ArrayList<>(HALF_WINDOW);
        List<Sample> validation = new ArrayList<>(HALF_WINDOW);
        long firstTick = frames.get(0).tick();
        double minimumYaw = Math.max(0.75, 4 * quantum);

        for (int i = 1; i < frames.size(); i++) {
            Frame previous = frames.get(i - 1);
            Frame current = frames.get(i);
            TargetAngles before = previous.target();
            TargetAngles after = current.target();
            if (before == null || after == null || current.tick() != previous.tick() + 1
                    || Math.abs(MathUtil.getAngleDifference(current.yaw(), after.yaw())) > 25) continue;

            double yaw = MathUtil.getAngleDifference(current.yaw(), previous.yaw());
            if (Math.abs(yaw) < minimumYaw || Math.abs(yaw) > 12) continue;
            double pitchChange = Math.max(Math.abs(after.maxPitch() - before.minPitch()),
                    Math.abs(after.minPitch() - before.maxPitch()));
            if (pitchChange > 0.5) continue;

            double residual = current.pitch() - previous.pitch() - (after.pitch() - before.pitch());
            long elapsed = current.tick() - firstTick;
            double phase = 2 * Math.PI * ((elapsed % PERIOD) / PERIOD);
            Sample sample = new Sample(yaw, residual, phase);
            (elapsed < HALF_WINDOW ? training : validation).add(sample);
        }

        if (!hasCoverage(training) || !hasCoverage(validation)) {
            return Result.skipped(training.size(), validation.size(), "coverage");
        }
        double[] baseline = fit(training, BASELINE_TERMS);
        double[] periodic = fit(training, PERIODIC_TERMS);
        if (baseline == null || periodic == null) {
            return Result.skipped(training.size(), validation.size(), "rank");
        }

        double baselineError = squaredError(validation, baseline);
        if (Math.sqrt(baselineError / validation.size()) < Math.max(0.05, quantum / 2)) {
            return Result.skipped(training.size(), validation.size(), "baseline-noise");
        }
        double gain = 1 - squaredError(validation, periodic) / baselineError;
        double amplitude = Math.hypot(periodic[4], periodic[5]);
        boolean matched = gain >= 0.80 && amplitude >= 0.10 && amplitude <= 0.30;
        return new Result(matched, training.size(), validation.size(), gain, amplitude, matched ? "match" : "model");
    }

    private static boolean hasCoverage(List<Sample> samples) {
        if (samples.size() < 24) return false;
        int positive = 0;
        int[] quadrants = new int[4];
        for (Sample sample : samples) {
            if (sample.yaw() > 0) positive++;
            quadrants[(int) (sample.phase() / (Math.PI / 2))]++;
        }
        if (positive < 6 || samples.size() - positive < 6) return false;
        for (int count : quadrants) {
            if (count < 3) return false;
        }
        return true;
    }

    // Solve the small least-squares system with pivoting. An ill-conditioned window supplies no evidence.
    private static double[] fit(List<Sample> samples, int terms) {
        double[][] system = new double[terms][terms + 1];
        for (Sample sample : samples) {
            double[] row = sample.terms();
            for (int i = 0; i < terms; i++) {
                for (int j = 0; j < terms; j++) system[i][j] += row[i] * row[j];
                system[i][terms] += row[i] * sample.residual();
            }
        }
        for (int i = 0; i < terms; i++) {
            int pivot = i;
            for (int j = i + 1; j < terms; j++) {
                if (Math.abs(system[j][i]) > Math.abs(system[pivot][i])) pivot = j;
            }
            if (Math.abs(system[pivot][i]) < 1.0e-8) return null;
            double[] pivotRow = system[pivot];
            system[pivot] = system[i];
            system[i] = pivotRow;
            double scale = pivotRow[i];
            for (int j = i; j <= terms; j++) pivotRow[j] /= scale;
            for (int row = 0; row < terms; row++) {
                if (row == i) continue;
                double factor = system[row][i];
                for (int j = i; j <= terms; j++) system[row][j] -= factor * pivotRow[j];
            }
        }
        double[] coefficients = new double[terms];
        for (int i = 0; i < terms; i++) coefficients[i] = system[i][terms];
        return coefficients;
    }

    private static double squaredError(List<Sample> samples, double[] coefficients) {
        double error = 0;
        for (Sample sample : samples) {
            double[] terms = sample.terms();
            double prediction = 0;
            for (int i = 0; i < coefficients.length; i++) prediction += coefficients[i] * terms[i];
            double residual = sample.residual() - prediction;
            error += residual * residual;
        }
        return error;
    }

    private record Sample(double yaw, double residual, double phase) {
        double[] terms() {
            double sin = Math.sin(phase);
            double cos = Math.cos(phase);
            return new double[]{1, yaw, sin, cos, yaw * sin, yaw * cos};
        }
    }

    record Result(boolean matched, int trainingSamples, int validationSamples, double gain, double amplitude, String reason) {
        static Result skipped(int training, int validation, String reason) {
            return new Result(false, training, validation, Double.NaN, Double.NaN, reason);
        }

        String verbose() {
            return String.format(Locale.ROOT, "samples=%d/%d, gain=%.3f, amplitude=%.3f",
                    trainingSamples, validationSamples, gain, amplitude);
        }
    }
}
