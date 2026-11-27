package cc.watchneko.checks.impl.inventory;

import cc.watchneko.checks.impl.inventory.AutoTotem.Action;
import cc.watchneko.checks.impl.inventory.AutoTotem.Input;
import cc.watchneko.checks.impl.inventory.AutoTotem.InputKind;
import cc.watchneko.checks.impl.inventory.AutoTotem.Mode;
import cc.watchneko.checks.impl.inventory.AutoTotem.Result;
import cc.watchneko.checks.impl.inventory.AutoTotem.Settings;
import cc.watchneko.checks.impl.inventory.AutoTotem.Tracker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoTotemTest {
    private static final Settings SETTINGS = new Settings(true, 5, 3, ms(5), ms(1500), 1.0);

    @Test
    void repeatedFastCursorPairsUseDistinctConfirmedPops() {
        var tracker = new Tracker(SETTINGS);
        Result result = null;
        int[] reactions = {400, 470, 540, 620, 700};
        for (int i = 0; i < reactions.length; i++) {
            result = cursorSample(tracker, ms(i * 2000L), reactions[i], i < 3 ? 3 : 50, false, i);
        }
        assertEquals(Mode.CURSOR, result.mode());
        assertEquals(3, result.rapid());
        assertEquals(5, result.pairs());
        assertEquals("rapid", result.pattern());
    }

    @Test
    void stableReplenishmentIsMeasuredSeparatelyForEachSwapMode() {
        var tracker = new Tracker(SETTINGS);
        Result handResult = null;
        Result inventoryResult = null;
        for (int i = 0; i < 5; i++) {
            handResult = swapSample(tracker, ms(i * 4000L), 400 + i % 2,
                    InputKind.HAND_SWAP, Action.HAND_SWAP, -1, -1, i * 2L);
            inventoryResult = swapSample(tracker, ms(i * 4000L + 2000), 500 + i % 2,
                    InputKind.OFFHAND_SWAP, Action.INVENTORY_SWAP, 10, 40, i * 2L + 1);
            if (i < 4) {
                assertNull(handResult);
                assertNull(inventoryResult);
            }
        }
        assertEquals(Mode.HAND_SWAP, handResult.mode());
        assertEquals(Mode.INVENTORY_SWAP, inventoryResult.mode());
        assertEquals("consistent", handResult.pattern());
        assertEquals("consistent", inventoryResult.pattern());
    }

    @Test
    void cursorAndHotbarSwapsRequireMatchingServerEventsAndOffhandVerification() {
        var tracker = new Tracker(SETTINGS);
        tracker.onPop(ms(0));
        tracker.onStatus(ms(0));
        tracker.onInput(new Input(InputKind.HOTBAR_SWAP, 0, 45, 2, ms(400), false));
        tracker.onServerAction(InputKind.HOTBAR_SWAP, 45, 2, Action.INVENTORY_SWAP, false, 1, ms(410));
        assertNull(tracker.onVerified(2, true, ms(460)));
        assertTrue(tracker.isActive());
        assertNull(tracker.onVerified(1, false, ms(460)));
        assertFalse(tracker.isActive());

        tracker.onPop(ms(2000));
        tracker.onStatus(ms(2000));
        tracker.onInput(new Input(InputKind.LEFT, 0, 45, 0, ms(2400), false));
        tracker.onServerAction(InputKind.LEFT, 45, 0, Action.CURSOR_PLACE, true, 3, ms(2410));
        assertNull(tracker.onVerified(3, true, ms(2460)));
    }

    @Test
    void batchesAndMissingOrAmbiguousEventsDoNotBecomeSamples() {
        var tracker = new Tracker(SETTINGS);
        for (int i = 0; i < 4; i++) {
            assertNull(cursorSample(tracker, ms(i * 2000L), 400 + i * 40, 3, false, i));
        }
        assertNull(cursorSample(tracker, ms(8000), 650, 3, true, 4));

        tracker.onPop(ms(10000));
        tracker.onStatus(ms(10000));
        tracker.onInput(new Input(InputKind.LEFT, 0, 45, 0, ms(10400), false));
        tracker.onInput(new Input(InputKind.LEFT, 0, 45, 0, ms(10401), false));
        tracker.onServerAction(InputKind.LEFT, 45, 0, Action.CURSOR_PLACE, false, 5, ms(10410));
        assertNull(tracker.onVerified(5, true, ms(10460)));

        tracker.onPop(ms(12000));
        tracker.onStatus(ms(12000));
        tracker.onServerAction(InputKind.LEFT, 45, 0, Action.CURSOR_PLACE, false, 6, ms(12400));
        assertNull(tracker.onVerified(6, true, ms(12460)));
        Result result = cursorSample(tracker, ms(14000), 720, 50, false, 7);
        assertEquals(Mode.CURSOR, result.mode());
        assertEquals(4, result.rapid());
    }

    @Test
    void ignoresInputsBeforePopAndAfterTheTimeLimit() {
        var tracker = new Tracker(SETTINGS);
        tracker.onPop(ms(1000));
        tracker.onStatus(ms(1000));
        tracker.onInput(new Input(InputKind.HAND_SWAP, -1, -1, -1, ms(999), false));
        tracker.onServerAction(InputKind.HAND_SWAP, -1, -1, Action.HAND_SWAP, false, 1, ms(1001));
        assertNull(tracker.onVerified(1, true, ms(1050)));

        tracker.onInput(new Input(InputKind.HAND_SWAP, -1, -1, -1, ms(2501), false));
        assertFalse(tracker.isActive());
    }

    @Test
    void requiresThePlayersTotemStatusBeforeAcceptingInputs() {
        var tracker = new Tracker(SETTINGS);
        tracker.onPop(ms(1000));
        tracker.onStatus(ms(999));
        tracker.onInput(new Input(InputKind.HAND_SWAP, -1, -1, -1, ms(1100), false));
        tracker.onServerAction(InputKind.HAND_SWAP, -1, -1, Action.HAND_SWAP, false, 1, ms(1110));
        assertNull(tracker.onVerified(1, true, ms(1160)));
        assertFalse(tracker.isActive());

        tracker.onPop(ms(2000));
        tracker.onStatus(ms(2050));
        tracker.onInput(new Input(InputKind.HAND_SWAP, -1, -1, -1, ms(2049), false));
        tracker.onServerAction(InputKind.HAND_SWAP, -1, -1, Action.HAND_SWAP, false, 2, ms(2051));
        assertNull(tracker.onVerified(2, true, ms(2100)));
    }

    @Test
    void measuresReplenishmentFromTheStatusPacket() {
        var tracker = new Tracker(SETTINGS);
        Result result = null;
        for (int i = 0; i < 5; i++) {
            long pop = ms(i * 3000L);
            long status = pop + ms(i * 100L);
            long input = status + ms(400);
            tracker.onPop(pop);
            tracker.onStatus(status);
            tracker.onInput(new Input(InputKind.HAND_SWAP, -1, -1, -1, input, false));
            tracker.onServerAction(InputKind.HAND_SWAP, -1, -1, Action.HAND_SWAP, false, i, input + ms(1));
            result = tracker.onVerified(i, true, input + ms(50));
        }
        assertEquals("consistent", result.pattern());
        assertEquals(400.0, result.meanMs());
    }

    @Test
    void duplicateStatusPacketsDoNotMoveTheActivationTime() {
        var tracker = new Tracker(SETTINGS);
        Result result = null;
        for (int i = 0; i < 5; i++) {
            long pop = ms(i * 3000L);
            long status = pop + ms(100);
            long input = status + ms(400);
            tracker.onPop(pop);
            tracker.onStatus(status);
            tracker.onStatus(status + ms(50L * (i + 1)));
            tracker.onInput(new Input(InputKind.HAND_SWAP, -1, -1, -1, input, false));
            tracker.onServerAction(InputKind.HAND_SWAP, -1, -1, Action.HAND_SWAP, false, i, input + ms(1));
            result = tracker.onVerified(i, true, input + ms(50));
        }
        assertEquals("consistent", result.pattern());
        assertEquals(400.0, result.meanMs());
    }

    @Test
    void expiresIdlePopsAndUnconfirmedOperations() {
        var tracker = new Tracker(SETTINGS);
        tracker.onPop(ms(0));
        tracker.expire(ms(1501));
        assertFalse(tracker.isActive());

        tracker.onPop(ms(2000));
        tracker.onStatus(ms(2000));
        tracker.onInput(new Input(InputKind.LEFT, 0, 10, 0, ms(2400), false));
        tracker.expire(ms(5401));
        assertFalse(tracker.isActive());

        tracker.onPop(ms(6000));
        tracker.onStatus(ms(6000));
        tracker.onInput(new Input(InputKind.HAND_SWAP, -1, -1, -1, ms(6400), false));
        tracker.onServerAction(InputKind.HAND_SWAP, -1, -1, Action.HAND_SWAP, false, 1, ms(6410));
        tracker.expire(ms(9411));
        assertFalse(tracker.isActive());
    }

    @Test
    void laterClicksAndNewPopsInvalidatePendingVerification() {
        var tracker = new Tracker(SETTINGS);
        tracker.onPop(ms(0));
        tracker.onStatus(ms(0));
        tracker.onInput(new Input(InputKind.HAND_SWAP, -1, -1, -1, ms(400), false));
        tracker.onServerAction(InputKind.HAND_SWAP, -1, -1, Action.HAND_SWAP, false, 1, ms(410));
        tracker.onInput(new Input(InputKind.LEFT, 0, 5, 0, ms(420), false));
        assertNull(tracker.onVerified(1, true, ms(460)));
        assertFalse(tracker.isActive());

        tracker.onPop(ms(2000));
        tracker.onStatus(ms(2000));
        tracker.onInput(new Input(InputKind.HAND_SWAP, -1, -1, -1, ms(2400), false));
        tracker.onServerAction(InputKind.HAND_SWAP, -1, -1, Action.HAND_SWAP, false, 2, ms(2410));
        tracker.onPop(ms(2450));
        assertNull(tracker.onVerified(2, true, ms(2460)));
        assertTrue(tracker.isActive());
    }

    @Test
    void aClosedWindowCanStillVerifyAnAlreadyAcceptedPlacement() {
        var tracker = new Tracker(SETTINGS);
        for (int i = 0; i < 4; i++) {
            assertNull(swapSample(tracker, ms(i * 2000L), 400 + i % 2,
                    InputKind.HAND_SWAP, Action.HAND_SWAP, -1, -1, i));
        }
        tracker.onPop(ms(8000));
        tracker.onStatus(ms(8000));
        tracker.onInput(new Input(InputKind.HAND_SWAP, -1, -1, -1, ms(8400), false));
        tracker.onServerAction(InputKind.HAND_SWAP, -1, -1, Action.HAND_SWAP, false, 5, ms(8410));
        tracker.clearInteractions();
        Result result = tracker.onVerified(5, true, ms(8460));
        assertEquals(Mode.HAND_SWAP, result.mode());
        assertEquals("consistent", result.pattern());
    }

    @Test
    void windowsDoNotReuseOldSamples() {
        var tracker = new Tracker(SETTINGS);
        Result result = null;
        for (int i = 0; i < 5; i++) {
            result = swapSample(tracker, ms(i * 2000L), 400,
                    InputKind.HAND_SWAP, Action.HAND_SWAP, -1, -1, i);
        }
        assertEquals("consistent", result.pattern());
        assertNull(swapSample(tracker, ms(10000), 400,
                InputKind.HAND_SWAP, Action.HAND_SWAP, -1, -1, 6));
    }

    private static Result cursorSample(Tracker tracker, long pop, int reactionMs, int gapMs, boolean grouped, long token) {
        long pickup = pop + ms(reactionMs - gapMs);
        long place = pop + ms(reactionMs);
        tracker.onPop(pop);
        tracker.onStatus(pop);
        tracker.onInput(new Input(InputKind.LEFT, 0, 10, 0, pickup, grouped));
        tracker.onServerAction(InputKind.LEFT, 10, 0, Action.PICKUP, false, token * 2, pickup + ms(1));
        tracker.onInput(new Input(InputKind.LEFT, 0, 45, 0, place, false));
        tracker.onServerAction(InputKind.LEFT, 45, 0, Action.CURSOR_PLACE, false, token * 2 + 1, place + ms(1));
        return tracker.onVerified(token * 2 + 1, true, place + ms(50));
    }

    private static Result swapSample(Tracker tracker, long pop, int reactionMs, InputKind kind, Action action,
                                     int slot, int button, long token) {
        long input = pop + ms(reactionMs);
        tracker.onPop(pop);
        tracker.onStatus(pop);
        tracker.onInput(new Input(kind, 0, slot, button, input, false));
        tracker.onServerAction(kind, slot, button, action, false, token, input + ms(1));
        return tracker.onVerified(token, true, input + ms(50));
    }

    private static long ms(long value) {
        return TimeUnit.MILLISECONDS.toNanos(value);
    }
}
