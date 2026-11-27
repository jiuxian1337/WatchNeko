package cc.watchneko.checks.impl.breaking;

import com.github.retrooper.packetevents.util.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyFastBreakTest {
    @Test
    void detectsRepeatedSameTickFinishes() {
        var timing = new LegacyFastBreak.DigTiming();
        for (int index = 0; index < 3; index++) {
            var block = new Vector3i(index, 64, 0);
            assertFalse(timing.start(block, true));
            if (index < 2) {
                assertFalse(timing.finish(block, true));
            } else {
                assertTrue(timing.finish(block, true));
            }
            timing.tick();
            timing.tick();
        }
    }

    @Test
    void detectsFastRestartsSeparatelyFromSameTickFinishes() {
        var timing = new LegacyFastBreak.DigTiming();
        for (int index = 0; index < 4; index++) {
            var block = new Vector3i(index, 64, 0);
            if (index < 3) {
                assertFalse(timing.start(block, true));
            } else {
                assertTrue(timing.start(block, true));
            }
            timing.tick();
            timing.tick();
            assertFalse(timing.finish(block, true));
            timing.tick();
        }
    }

    @Test
    void ignoresInstantBlocksAndMismatchedCompletions() {
        var timing = new LegacyFastBreak.DigTiming();
        for (int index = 0; index < 10; index++) {
            var source = new Vector3i(index, 64, 0);
            assertFalse(timing.start(source, true));
            assertFalse(timing.finish(new Vector3i(index, 64, 1), true));
            assertFalse(timing.start(source, false));
            assertFalse(timing.finish(source, false));
        }
    }
}
