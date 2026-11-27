package cc.watchneko.checks.impl.combat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FairReachHistoryTest {
    @Test
    void waitsForCompensationAndInterpolation() {
        var history = new FairReachHistory(300);
        history.record(ms(10), 4, 5, 0, 0);

        assertNull(history.cappedBounds(ms(310), 3));
        assertNull(history.cappedBounds(ms(504), 3));
        assertNotNull(history.cappedBounds(ms(505), 3));
    }

    @Test
    void aMatchingReceiptLeavesTheHitToOrdinaryReach() {
        var history = new FairReachHistory(300);
        history.record(ms(0), 4, 5, 0, 0);

        assertNotNull(history.cappedBounds(ms(500), 3));
        assertNull(history.cappedBounds(ms(500), 4));
    }

    @Test
    void oldPositionsLeaveTheEnvelopeWhenTheNextPositionSettles() {
        var history = new FairReachHistory(300);
        history.record(ms(0), 4, 2, 0, 0);
        history.record(ms(100), 5, 5, 0, 0);

        assertEquals(2.0, history.cappedBounds(ms(550), 3).minX());
        assertEquals(5.0, history.cappedBounds(ms(600), 4).minX());
    }

    @Test
    void newerPositionsCanPutTheTargetBackInRange() {
        var history = new FairReachHistory(300);
        history.record(ms(0), 4, 5, 1, 0);
        history.record(ms(450), 7, 2, -1, 1);

        assertEquals(new FairReachHistory.Bounds(2, -1, 0, 5, 1, 1), history.cappedBounds(ms(500), 3));
    }

    @Test
    void historyStartsFreshAfterAReset() {
        var history = new FairReachHistory(300);
        history.record(ms(0), 4, 5, 0, 0);
        assertNotNull(history.cappedBounds(ms(500), 3));

        history.clear();
        history.record(ms(500), 5, 8, 0, 0);
        assertNull(history.cappedBounds(ms(501), 3));
        assertEquals(8.0, history.cappedBounds(ms(1000), 3).minX());
    }

    @Test
    void aBurstDoesNotRetainUnboundedHistory() {
        var history = new FairReachHistory(300);
        for (int i = 0; i < 256; i++) {
            history.record(ms(i), i + 1, i, 0, 0);
        }

        assertNull(history.cappedBounds(ms(622), 1));
        assertEquals(128.0, history.cappedBounds(ms(623), 1).minX());
    }

    private static long ms(int value) {
        return TimeUnit.MILLISECONDS.toNanos(value);
    }
}
