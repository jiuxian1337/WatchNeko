package cc.watchneko.checks.impl.aim;

import cc.watchneko.checks.impl.aim.AimTrajectoryTracker.Frame;
import cc.watchneko.checks.impl.aim.AimTrajectoryTracker.TargetAngles;
import cc.watchneko.utils.math.MathUtil;

import java.util.List;
import java.util.Locale;

final class AimBraking {
    static final int MIN_TICKS = 5;
    static final int MAX_TICKS = 16;

    private AimBraking() {}

    static double quietTolerance(double quantum) {
        return Math.max(0.03, quantum / 2);
    }

    static Result evaluate(List<Frame> frames, double quantum) {
        if (!hasStableTarget(frames)) return Result.skipped("geometry");
        if (frames.get(0).target().couldHit() || !frames.get(frames.size() - 1).target().canReach()) {
            return Result.skipped("target-transition");
        }

        int ticks = frames.size() - 1;
        double[] yawMoves = new double[ticks];
        double[] pitchMoves = new double[ticks];
        double netYaw = 0;
        double netPitch = 0;
        for (int i = 0; i < ticks; i++) {
            Frame from = frames.get(i);
            Frame to = frames.get(i + 1);
            yawMoves[i] = MathUtil.getAngleDifference(to.yaw(), from.yaw());
            pitchMoves[i] = to.pitch() - from.pitch();
            netYaw += yawMoves[i];
            netPitch += pitchMoves[i];
        }
        boolean primaryYaw = Math.abs(netYaw) >= Math.abs(netPitch);
        double netAngle = primaryYaw ? netYaw : netPitch;
        double amplitude = Math.abs(netAngle);
        if (amplitude < 10 || amplitude > 65) return Result.skipped("turn-size");

        double[] moves = primaryYaw ? yawMoves : pitchMoves;
        double tolerance = quietTolerance(quantum);
        int lastMove = lastMovement(moves, tolerance);
        int otherLastMove = lastMovement(primaryYaw ? pitchMoves : yawMoves, tolerance);
        int stopGap = otherLastMove < 0 ? -1 : Math.abs(lastMove - otherLastMove);

        double[] remaining = new double[lastMove + 2];
        double sign = Math.signum(netAngle);
        int peakTick = 0;
        double peak = 0;
        for (int i = lastMove; i >= 0; i--) {
            moves[i] *= sign;
            if (moves[i] < -tolerance) return Result.skipped("reversal");
            remaining[i] = remaining[i + 1] + moves[i];
            if (moves[i] >= peak) {
                peak = moves[i];
                peakTick = i;
            }
        }

        double firstRate = 0;
        double secondRate = 0;
        for (int i = peakTick + 1; i < lastMove; i++) {
            if (remaining[i] < 4 * quantum || remaining[i + 1] <= quantum || moves[i] <= quantum) continue;
            double rate = solveRate(remaining[i], remaining[i + 1]);
            if (firstRate > 0) {
                secondRate = rate;
                break;
            }
            firstRate = rate;
        }
        if (secondRate == 0) return Result.skipped("braking-samples");

        double rate = (firstRate + secondRate) / 2;
        double rateSpread = Math.abs(firstRate - secondRate) / (firstRate + secondRate);
        double error = 0;
        double energy = 0;
        for (int i = peakTick + 1; i <= lastMove; i++) {
            double difference = predictRemaining(remaining[i], rate) - remaining[i + 1];
            error += difference * difference;
            energy += moves[i] * moves[i];
        }
        double normalizedError = Math.sqrt(error / energy);
        double lastMoveFraction = moves[lastMove] / peak;
        double smoothError = smoothError(remaining);
        boolean matched = rateSpread <= 0.10 && normalizedError <= 0.08 && lastMoveFraction >= 0.12
                && smoothError > Math.max(0.15, 1.5 * quantum);
        return new Result(true, matched, amplitude, normalizedError, lastMoveFraction, smoothError, stopGap,
                matched ? "match" : "model");
    }

    private static boolean hasStableTarget(List<Frame> frames) {
        TargetAngles first = frames.get(0).target();
        if (first == null) return false;
        double minYaw = Double.POSITIVE_INFINITY;
        double maxYaw = Double.NEGATIVE_INFINITY;
        double minPitch = Double.POSITIVE_INFINITY;
        double maxPitch = Double.NEGATIVE_INFINITY;
        for (Frame frame : frames) {
            TargetAngles target = frame.target();
            if (target == null) return false;
            double delta = MathUtil.getAngleDifference(target.yaw(), first.yaw());
            minYaw = Math.min(minYaw, delta + target.minYawOffset());
            maxYaw = Math.max(maxYaw, delta + target.maxYawOffset());
            minPitch = Math.min(minPitch, target.minPitch());
            maxPitch = Math.max(maxPitch, target.maxPitch());
        }
        return maxYaw - minYaw <= 3 && maxPitch - minPitch <= 2;
    }

    private static int lastMovement(double[] moves, double tolerance) {
        for (int i = moves.length - 1; i >= 0; i--) {
            if (Math.abs(moves[i]) > tolerance) return i;
        }
        return -1;
    }

    private static double predictRemaining(double remaining, double rate) {
        for (int i = 0; i < 3; i++) remaining = Math.max(0, remaining - rate * Math.sqrt(remaining));
        return remaining;
    }

    private static double solveRate(double before, double after) {
        double low = 0;
        double high = Math.sqrt(before);
        for (int i = 0; i < 20; i++) {
            double mid = (low + high) / 2;
            if (predictRemaining(before, mid) > after) low = mid;
            else high = mid;
        }
        return (low + high) / 2;
    }

    private static double smoothError(double[] remaining) {
        int end = remaining.length - 1;
        double best = Double.POSITIVE_INFINITY;
        for (int startPhase = -3; startPhase <= 2; startPhase++) {
            double start = startPhase / 3.0;
            for (int endPhase = -2; endPhase <= 3; endPhase++) {
                double duration = end + endPhase / 3.0 - start;
                double first = ease(-start / duration);
                double last = ease((end - start) / duration);
                double error = 0;
                for (int i = 1; i < end; i++) {
                    double progress = (ease((i - start) / duration) - first) / (last - first);
                    double difference = remaining[i] - remaining[0] * (1 - progress);
                    error += difference * difference;
                }
                best = Math.min(best, error);
            }
        }
        return Math.sqrt(best / (end - 1));
    }

    private static double ease(double time) {
        double t = Math.max(0, Math.min(time, 1));
        return t * t * t * (10 + t * (-15 + 6 * t));
    }

    record Result(boolean qualified, boolean matched, double amplitude, double error, double lastMoveFraction,
                  double smoothError, int stopGap, String reason) {
        static Result skipped(String reason) {
            return new Result(false, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1, reason);
        }

        String verbose() {
            return String.format(Locale.ROOT, "turn=%.2fdeg, error=%.3f, tail=%.3f, smooth=%.2fdeg, stopGap=%d",
                    amplitude, error, lastMoveFraction, smoothError, stopGap);
        }
    }
}
