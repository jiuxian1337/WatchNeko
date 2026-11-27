package cc.watchneko.checks.impl.scaffolding;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScaffoldETest {
    @Test
    void detectsOnlyWhenThePlacementHasAFullWindow() {
        var bridge = new Bridge();
        for (int sample = 0; sample < 24; sample++) {
            assertNull(bridge.sneakAndPlace(2));
        }
        ScaffoldE.Match match = bridge.sneakAndPlace(2);
        assertNotNull(match);
        assertEquals(2.0, match.average());
        assertEquals(0.0, match.deviation());
        assertNull(bridge.sneakAndPlace(2));
    }

    @Test
    void rejectsLongOrInconsistentSneaks() {
        var longSneaks = new Bridge();
        var inconsistentSneaks = new Bridge();
        for (int sample = 0; sample < 24; sample++) {
            assertNull(longSneaks.sneakAndPlace(4));
            assertNull(inconsistentSneaks.sneakAndPlace(1));
        }
        assertNull(longSneaks.sneakAndPlace(4));
        assertNull(inconsistentSneaks.sneakAndPlace(20));
    }

    @Test
    void aPlacementAfterAnIdleGapStartsAFreshWindow() {
        var bridge = new Bridge();
        for (int sample = 0; sample < 24; sample++) {
            assertNull(bridge.sneakAndPlace(2));
        }
        bridge.now += TimeUnit.SECONDS.toNanos(4);
        assertNull(bridge.pattern.place(bridge.now));
        for (int sample = 0; sample < 24; sample++) {
            assertNull(bridge.sneakAndPlace(2));
        }
        assertNotNull(bridge.sneakAndPlace(2));
    }

    private static final class Bridge {
        private final ScaffoldE.SneakPattern pattern = new ScaffoldE.SneakPattern();
        private long now;

        Bridge() {
            pattern.place(now);
        }

        ScaffoldE.Match sneakAndPlace(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                now += TimeUnit.MILLISECONDS.toNanos(50);
                pattern.tick(now, true);
            }
            now += TimeUnit.MILLISECONDS.toNanos(51);
            pattern.tick(now, false);
            return pattern.place(now);
        }
    }
}
