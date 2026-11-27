package cc.watchneko.checks.impl.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatTimingTest {
    @Test
    void clickLimitUsesOneSecondOfRealSwingTimes() {
        var ordinary = new CpsLimit.ClickRate();
        var fast = new CpsLimit.ClickRate();
        for (long index = 0; index < 50; index++) {
            assertFalse(ordinary.swing(nanos(index * 50), 20));
        }
        for (long index = 0; index < 20; index++) {
            assertFalse(fast.swing(nanos(index * 30), 20));
        }
        assertTrue(fast.swing(nanos(600), 20));
        assertTrue(fast.exceeds(nanos(620), 20));
        assertFalse(fast.exceeds(nanos(1_100), 20));
        assertFalse(fast.swing(nanos(1_700), 20));
    }

    @Test
    void aSingleBurstCannotStartTheDamageRule() {
        var rate = new CpsLimit.ClickRate();
        for (long index = 0; index < 90; index++) {
            assertFalse(rate.swing(nanos(index * 2), 20));
        }
    }

    private static long nanos(long millis) {
        return millis * 1_000_000;
    }
}
