package cc.watchneko.checks.impl.combat;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/** Outbound target positions for the capped PvP view, confined to the observer's packet thread. */
final class FairReachHistory {
    private static final int MAX_SAMPLES = 128;
    private static final long INTERPOLATION_NANOS = TimeUnit.MILLISECONDS.toNanos(3 * 65);

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final long settleAfterNanos;

    FairReachHistory(int maxCompensationMillis) {
        // A remote player's position interpolates over three client ticks. Allow jitter on each tick.
        settleAfterNanos = TimeUnit.MILLISECONDS.toNanos(maxCompensationMillis) + INTERPOLATION_NANOS;
    }

    void record(long sentAt, int receipt, double x, double y, double z) {
        samples.addLast(new Sample(sentAt, receipt, x, y, z));
        trim(sentAt);
        if (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    Bounds cappedBounds(long now, int lastReceipt) {
        trim(now);
        Sample anchor = samples.peekFirst();
        if (anchor == null || now - anchor.sentAt() < settleAfterNanos || anchor.receipt() <= lastReceipt) {
            return null;
        }

        // Include newer destinations and the space between them, so a target coming back into range is allowed.
        double minX = anchor.x(), maxX = anchor.x();
        double minY = anchor.y(), maxY = anchor.y();
        double minZ = anchor.z(), maxZ = anchor.z();
        for (Sample sample : samples) {
            minX = Math.min(minX, sample.x());
            minY = Math.min(minY, sample.y());
            minZ = Math.min(minZ, sample.z());
            maxX = Math.max(maxX, sample.x());
            maxY = Math.max(maxY, sample.y());
            maxZ = Math.max(maxZ, sample.z());
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    void clear() {
        samples.clear();
    }

    private void trim(long now) {
        while (samples.size() > 1) {
            Sample previous = samples.removeFirst();
            if (now - samples.getFirst().sentAt() < settleAfterNanos) {
                samples.addFirst(previous);
                return;
            }
        }
    }

    record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

    private record Sample(long sentAt, int receipt, double x, double y, double z) {}
}
