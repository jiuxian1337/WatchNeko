package cc.watchneko.checks.impl.inventory;

import cc.watchneko.checks.impl.inventory.ChestStealer.Click;
import cc.watchneko.checks.impl.inventory.ChestStealer.Outcome;
import cc.watchneko.checks.impl.inventory.ChestStealer.Tracker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChestStealerTest {
    @Test
    void combinesRepeatedVerifiedTransfersAndClosesAcrossWindows() {
        var tracker = new Tracker();
        assertNull(window(tracker, 1, 1_000, 70, 55, 5, Outcome.TRANSFERRED));
        assertNull(window(tracker, 2, 2_000, 75, 50, 5, Outcome.TRANSFERRED));
        var match = window(tracker, 3, 3_000, 80, 50, 5, Outcome.TRANSFERRED);
        assertNotNull(match);
        assertEquals(5, match.slots());
        assertEquals(80, match.meanMillis());
        assertEquals(50, match.closeMillis());
        assertNull(window(tracker, 4, 4_000, 70, 55, 5, Outcome.TRANSFERRED));
    }

    @Test
    void excludesFailedTransfersRepeatedSlotsBatchedClicksAndSlowClosures() {
        var tracker = new Tracker();
        for (int sequence = 1; sequence <= 3; sequence++) {
            long start = sequence * 10_000L;
            assertNull(window(tracker, sequence * 4L, start, 80, 55, 5, Outcome.OTHER));
            assertNull(window(tracker, sequence * 4L + 1, start + 1_000, 80, 55, 2, Outcome.TRANSFERRED));
            assertNull(window(tracker, sequence * 4L + 2, start + 2_000, 3, 55, 5, Outcome.TRANSFERRED));
            assertNull(window(tracker, sequence * 4L + 3, start + 3_000, 80, 400, 5, Outcome.TRANSFERRED));
        }
    }

    @Test
    void missingOrMismatchedBukkitResultsClearTheWindowEvidence() {
        var tracker = new Tracker();
        assertNull(window(tracker, 1, 1_000, 70, 55, 5, Outcome.TRANSFERRED));
        assertNull(window(tracker, 2, 2_000, 70, 55, 5, Outcome.TRANSFERRED));
        tracker.click(3, new Click(0, 0, true), nanos(3_000));
        tracker.serverClick(new Click(1, 0, true), Outcome.TRANSFERRED, nanos(3_010));
        tracker.clientClose(3, nanos(3_200));
        assertNull(tracker.serverClose(nanos(3_225)));
        assertNull(window(tracker, 4, 4_000, 70, 55, 5, Outcome.TRANSFERRED));
        assertNull(window(tracker, 5, 5_000, 70, 55, 5, Outcome.TRANSFERRED));
        assertNotNull(window(tracker, 6, 6_000, 70, 55, 5, Outcome.TRANSFERRED));
    }

    private static ChestStealer.Match window(Tracker tracker, long sequence, long start, int gap,
                                             int closeGap, int distinctSlots, Outcome outcome) {
        for (int click = 0; click < 5; click++) {
            tracker.click(sequence, new Click(click % distinctSlots, 0, true), nanos(start + click * gap));
        }
        long close = start + 4L * gap + closeGap;
        tracker.clientClose(sequence, nanos(close));
        // All packets can arrive before the server thread dispatches the matching events.
        for (int click = 0; click < 5; click++) {
            tracker.serverClick(new Click(click % distinctSlots, 0, true), outcome, nanos(close + click + 5));
        }
        return tracker.serverClose(nanos(close + 20));
    }

    private static long nanos(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
