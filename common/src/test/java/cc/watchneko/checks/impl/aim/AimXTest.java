package cc.watchneko.checks.impl.aim;

import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AimXTest {
    @Test
    void requiresSeveralWindowsOfSignedProportionalCorrection() {
        var correlation = new AimX.Correlation();
        for (int sample = 0; sample < 119; sample++) {
            double expected = angle(sample);
            assertNull(correlation.sample(expected, 0.7 * expected, nanos(sample)));
        }
        double expected = angle(119);
        var match = correlation.sample(expected, 0.7 * expected, nanos(119));
        assertNotNull(match);
        assertEquals(0.7, match.gain(), 1.0E-6);
        assertEquals(1, match.correlation(), 1.0E-6);
    }

    @Test
    void rejectsOppositeTurnsAndWindowsWithARepeatedGap() {
        var opposite = new AimX.Correlation();
        var stale = new AimX.Correlation();
        for (int sample = 0; sample < 160; sample++) {
            double expected = angle(sample);
            assertNull(opposite.sample(expected, -0.7 * expected, nanos(sample)));
            assertNull(stale.sample(expected, 0.7 * expected, nanos(sample) + TimeUnit.SECONDS.toNanos(sample / 40)));
        }
    }

    @Test
    void rejectsAmbiguousCompensatedTargetPositions() {
        assertTrue(AimX.hasNarrowTarget(new SimpleCollisionBox(0, 0, 0, 0.05, 0, 0.05), 3));
        assertFalse(AimX.hasNarrowTarget(new SimpleCollisionBox(0, 0, 0, 0.4, 0, 0.4), 3));
    }

    private static double angle(int sample) {
        double absolute = 3 + sample % 11;
        return sample % 2 == 0 ? absolute : -absolute;
    }

    private static long nanos(int sample) {
        return TimeUnit.MILLISECONDS.toNanos(50L * sample);
    }
}
