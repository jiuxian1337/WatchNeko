package cc.watchneko.checks.impl.breaking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the per-block tolerance that stops a jittery connection from being read as FastBreak.
 *
 * <p>The check counts a block as early whenever the measured break time is shorter than predicted, and
 * accumulates the shortfall. Anything the measurement can be wrong by therefore has to be excluded
 * before it reaches that accumulator, or a run of unlucky blocks flags a legitimate player — which is
 * what https://github.com/jiuxian1337/WatchNeko/issues/24 reports.
 */
class FastBreakToleranceTest {

    @Test
    void aPerfectConnectionStillGetsOneTickOfSlack() {
        // The client only sends the animation packet once per tick, so a break spanning a tick boundary
        // can be measured up to a tick short with no latency involved at all.
        assertEquals(50, FastBreak.tolerance(0));
    }

    @Test
    void latencyWidensTheSlack() {
        assertTrue(FastBreak.tolerance(200) > FastBreak.tolerance(0));
        assertEquals(250, FastBreak.tolerance(200));
    }

    @Test
    void theSlackIsCappedSoASpoofedPingCannotHideARealAdvantage() {
        // Without a cap, claiming a five-second ping would exempt five seconds per block.
        assertEquals(50 + 400, FastBreak.tolerance(5_000));
        assertEquals(FastBreak.tolerance(400), FastBreak.tolerance(10_000));
    }

    @Test
    void aNegativePingCannotShrinkTheSlackBelowOneTick() {
        // getTransactionPing is 0 until the first transaction comes back; a -1 from an unknown source
        // must not turn the tolerance negative and make every block look early.
        assertEquals(50, FastBreak.tolerance(-1));
    }

    @Test
    void theOldThresholdWasBelowOneTick() {
        // The regression being fixed: the previous cut-off was 25 ms, i.e. half a tick, which is less
        // than the measurement's own granularity. Any tolerance must be at least a tick.
        assertTrue(FastBreak.tolerance(0) >= 50);
    }
}
