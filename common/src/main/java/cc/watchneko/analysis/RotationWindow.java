package cc.watchneko.analysis;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * The trailing window of rotation updates a player produced, and the feature vector derived from it.
 *
 * <p>Per ADR-0001 the window is counted in <em>rotation updates</em>, not ticks and not wall-clock time.
 * A player holding their mouse still produces no rotation updates, so the window spans however long that
 * takes; what it always contains is a fixed number of samples.
 *
 * <p>The feature vector is the window flattened: for each rotation update, its yaw and pitch delta
 * normalised against the largest absolute delta of that axis inside the window. That yields
 * {@link #FEATURE_DIMENSIONS} values in {@code [-1, 1]}, and it is the only form in which a sample leaves
 * the server.
 *
 * <p>This class holds no policy and touches nothing outside the JDK, so the windowing rule can be tested
 * on its own.
 */
public final class RotationWindow {

    /** Rotation updates kept either side of the attack anchor. */
    public static final int RADIUS = 5;

    /** Rotation updates in a complete window. */
    public static final int WINDOW_SIZE = (RADIUS * 2) + 1;

    /** A window flattened to yaw and pitch per rotation update. */
    public static final int FEATURE_DIMENSIONS = WINDOW_SIZE * 2;

    private static final int CAPACITY = WINDOW_SIZE * 4;
    private static final double EPSILON = 1.0E-6;

    private record Frame(long sequence, float deltaYaw, float deltaPitch) {
    }

    /**
     * One complete window: the normalised vector that goes on the wire, plus the raw movement energy that
     * stays local.
     *
     * <p>The energy is kept because it is the cheap, local answer to "is this sample even worth sending" —
     * a window where nothing moved carries no information about aim, and under quota pressure it is the
     * first thing that can be dropped without losing anything.
     *
     * @param features the {@link #FEATURE_DIMENSIONS} normalised values
     * @param energy   sum of absolute yaw and pitch deltas across the window, in degrees
     */
    public record ExtractedWindow(double[] features, double energy) {
    }

    private final Deque<Frame> frames = new ArrayDeque<>(CAPACITY);

    /** Records one rotation update at the given sequence position. */
    public void append(final long sequence, final float deltaYaw, final float deltaPitch) {
        frames.addLast(new Frame(sequence, deltaYaw, deltaPitch));
        while (frames.size() > CAPACITY) {
            frames.removeFirst();
        }
    }

    public void clear() {
        frames.clear();
    }

    public int size() {
        return frames.size();
    }

    public long oldestSequence() {
        final Frame oldest = frames.peekFirst();
        return oldest == null ? Long.MAX_VALUE : oldest.sequence();
    }

    /**
     * Extracts the window centred on {@code centerSequence}, or empty when it is not yet complete — either
     * because the anchor is unknown to this buffer, or because fewer than {@link #RADIUS} rotation updates
     * have arrived after it.
     */
    public Optional<ExtractedWindow> extract(final long centerSequence) {
        final Frame[] all = frames.toArray(new Frame[0]);
        int center = -1;
        for (int i = 0; i < all.length; i++) {
            if (all[i].sequence() == centerSequence) {
                center = i;
                break;
            }
        }

        if (center < 0 || center < RADIUS || center + RADIUS >= all.length) {
            return Optional.empty();
        }

        double maxYaw = 0.0;
        double maxPitch = 0.0;
        double energy = 0.0;
        for (int i = center - RADIUS; i <= center + RADIUS; i++) {
            final double yaw = Math.abs(all[i].deltaYaw());
            final double pitch = Math.abs(all[i].deltaPitch());
            maxYaw = Math.max(maxYaw, yaw);
            maxPitch = Math.max(maxPitch, pitch);
            energy += yaw + pitch;
        }

        final double yawDenominator = Math.max(maxYaw, EPSILON);
        final double pitchDenominator = Math.max(maxPitch, EPSILON);

        final double[] features = new double[FEATURE_DIMENSIONS];
        int out = 0;
        for (int i = center - RADIUS; i <= center + RADIUS; i++) {
            features[out++] = all[i].deltaYaw() / yawDenominator;
            features[out++] = all[i].deltaPitch() / pitchDenominator;
        }
        return Optional.of(new ExtractedWindow(features, energy));
    }
}
