package cc.watchneko.checks.impl.autoclicker;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AutoclickerBTest {
    private static final long[] DISTINCTIVE_INTERVALS = {
            68, 113, 86, 76, 131, 96, 103, 52, 80, 121, 67, 95, 112, 74, 84, 109
    };
    private static final AutoclickerB.Settings SETTINGS = new AutoclickerB.Settings(
            true, DISTINCTIVE_INTERVALS.length, 2, TimeUnit.MILLISECONDS.toNanos(350),
            TimeUnit.SECONDS.toNanos(3), 0.95, 0.10, TimeUnit.MILLISECONDS.toNanos(4)
    );

    @Test
    void orderedSimilarityRequiresMatchingShapeAndTempo() {
        double[] original = Arrays.stream(DISTINCTIVE_INTERVALS).mapToDouble(TimeUnit.MILLISECONDS::toNanos).toArray();
        double[] shuffled = original.clone();
        Arrays.sort(shuffled);
        double[] slower = Arrays.stream(original).map(value -> value * 1.5).toArray();

        assertNotNull(AutoclickerB.compare(original, original, SETTINGS));
        assertNull(AutoclickerB.compare(original, shuffled, SETTINGS));
        assertNull(AutoclickerB.compare(original, slower, SETTINGS));
    }

    @Test
    void skipsConstantAndSharedShortCadenceWindows() {
        double[] regular = new double[DISTINCTIVE_INTERVALS.length];
        Arrays.fill(regular, TimeUnit.MILLISECONDS.toNanos(90));
        assertNull(AutoclickerB.compare(regular, regular, SETTINGS));

        long[] cadence = {75, 90, 105, 92};
        for (int i = 0; i < regular.length; i++) {
            regular[i] = TimeUnit.MILLISECONDS.toNanos(cadence[i % cadence.length] + i / cadence.length);
        }
        assertNull(AutoclickerB.compare(regular, regular, SETTINGS));
    }

    @Test
    void coalescedSwingBreaksThePairStreak() {
        var stream = new SwingStream();
        assertNull(stream.addMatchingPair());
        assertNull(stream.addInterval(1));
        assertNull(stream.addMatchingPair());
        assertNotNull(stream.addMatchingPair());
    }

    private static final class SwingStream {
        private final AutoclickerB.ClickWindows windows = new AutoclickerB.ClickWindows(SETTINGS);
        private long now = -5_000_000L;

        SwingStream() {
            assertNull(windows.add(now));
        }

        AutoclickerB.Match addInterval(long milliseconds) {
            now += TimeUnit.MILLISECONDS.toNanos(milliseconds);
            return windows.add(now);
        }

        AutoclickerB.Match addMatchingPair() {
            AutoclickerB.Match match = null;
            for (int window = 0; window < 2; window++) {
                for (long interval : DISTINCTIVE_INTERVALS) {
                    assertNull(match);
                    match = addInterval(interval);
                }
            }
            return match;
        }
    }
}
