package cc.watchneko.checks.impl.autoclicker;

import com.github.retrooper.packetevents.util.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AutoclickerTimingTest {
    @Test
    void adjacentDifferencesKeepOnlyTheCurrentBoundedWindow() {
        var cadence = new AutoclickerC.Cadence();
        long now = 0;
        assertNull(cadence.click(now));
        for (int interval = 0; interval < 128; interval++) {
            now += nanos(78 + interval % 3);
            assertNull(cadence.click(now));
        }
        now += nanos(500);
        assertNull(cadence.click(now));
        for (int interval = 0; interval < 191; interval++) {
            now += nanos(81 + interval % 2);
            assertNull(cadence.click(now));
        }
        var match = cadence.click(now + nanos(81));
        assertNotNull(match);
        assertEquals("stable-differences", match.pattern());
    }

    @Test
    void repeatedUpperEdgeCanBeDetectedWithVariableIntervals() {
        var cadence = new AutoclickerC.Cadence();
        int[] gaps = {60, 95, 130, 95};
        long now = 0;
        assertNull(cadence.click(now));
        for (int interval = 0; interval < 191; interval++) {
            now += nanos(gaps[interval % gaps.length]);
            assertNull(cadence.click(now));
        }
        var match = cadence.click(now + nanos(gaps[191 % gaps.length]));
        assertNotNull(match);
        assertEquals("repeat-upper-edge", match.pattern());
    }

    @Test
    void missingMiningSwingsAreEvaluatedAfterTheCancelTick() {
        var timing = new AutoclickerD.MiningCadence();
        var block = new Vector3i(1, 60, 1);
        for (int index = 0; index < 48; index++) {
            assertNull(timing.start(block, 41, true, nanos(index * 75)));
            timing.abort(block, 41);
            timing.tick();
        }
        var match = timing.start(block, 41, true, nanos(48 * 75));
        assertNotNull(match);
        assertEquals("missing-swings", match.pattern());
    }

    @Test
    void miningCadenceDiscardsChangedTargetsAndInstantBreaks() {
        var timing = new AutoclickerD.MiningCadence();
        var first = new Vector3i(1, 60, 1);
        var second = new Vector3i(2, 60, 1);
        for (int index = 0; index < 80; index++) {
            var position = (index & 1) == 0 ? first : second;
            assertNull(timing.start(position, 41, index % 5 != 0, nanos(index * 75)));
            timing.swing();
            timing.abort(position, 41);
            timing.tick();
        }
    }

    @Test
    void rightClickCountsModernFallbackOnceAndSkipsHoldCadence() {
        var fast = new AutoclickerE.RightClicks();
        var held = new AutoclickerE.RightClicks();
        for (int index = 0; index < 96; index++) {
            assertNull(fast.click(AutoclickerE.Mode.BLOCK, nanos(index * 75)));
            assertNull(fast.click(AutoclickerE.Mode.ITEM, nanos(index * 75 + 1)));
            assertNull(held.click(AutoclickerE.Mode.BLOCK, nanos(index * 200)));
        }
        var match = fast.click(AutoclickerE.Mode.BLOCK, nanos(96 * 75));
        assertNotNull(match);
        assertEquals(75, match.mean());
    }

    private static long nanos(long millis) {
        return millis * 1_000_000;
    }
}
