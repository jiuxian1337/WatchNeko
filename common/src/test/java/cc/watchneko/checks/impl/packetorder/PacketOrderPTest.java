package cc.watchneko.checks.impl.packetorder;

import cc.watchneko.utils.latency.CompensatedOpenWindow;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PacketOrderPTest {
    @Test
    void detectsAttackAndDigAfterClientClose() {
        var state = new PacketOrderP.CloseTickState();
        state.onTickEnd(true, 0);
        state.onClose(3);
        state.onAttack("attack", true, 0);
        assertEquals("attack after client close", state.onTickEnd(false, 0));

        state.onClose(0);
        state.onAttack("dig", true, 0);
        assertEquals("dig after client close", state.onTickEnd(false, 0));
    }

    @Test
    void detectsPlayerInventoryClickAndRepeatedClose() {
        var state = new PacketOrderP.CloseTickState();
        state.onTickEnd(true, 0);
        state.onClose(7);
        state.onClick(0);
        assertEquals("player inventory click after close", state.onTickEnd(false, 0));

        state.onClose(0);
        state.onClose(0);
        assertEquals("multiple player inventory closes", state.onTickEnd(false, 0));
    }

    @Test
    void separatesTicksAndOtherContainerClicks() {
        var state = new PacketOrderP.CloseTickState();
        state.onTickEnd(true, 0);
        state.onClose(7);
        state.onClick(8);
        assertNull(state.onTickEnd(false, 0));
        state.onAttack("attack", true, 0);
        state.onClick(0);
        state.onClose(0);
        assertNull(state.onTickEnd(false, 0));
    }

    @Test
    void requiresAConfirmedServerCloseAfterAnOpenTick() {
        var state = new PacketOrderP.CloseTickState();
        state.onTickEnd(true, 4);
        state.onAttack("attack", true, 4);
        state.onAttack("attack", false, 5);
        assertNull(state.onTickEnd(true, 4));

        state.onAttack("attack", true, 5);
        assertEquals("attack after server close", state.onTickEnd(false, 5));

        state.onAttack("attack", true, 6);
        assertNull(state.onTickEnd(false, 6));
    }

    @Test
    void dropsEvidenceWhenMovementPacketsSuggestMergedTicks() {
        var state = new PacketOrderP.CloseTickState();
        state.onTickEnd(true, 0);
        state.onClose(0);
        state.onAttack("attack", true, 0);
        state.onMovement();
        state.onMovement();
        assertNull(state.onTickEnd(false, 0));

        state.onClose(0);
        state.onMovement();
        state.onAttack("attack", true, 0);
        assertEquals("attack after client close", state.onTickEnd(false, 0));
    }

    @Test
    void waitsForANewBoundaryAfterAnUncertainTick() {
        var state = new PacketOrderP.CloseTickState();
        state.onTickEnd(true, 0);
        state.onClose(0);
        state.onAttack("attack", true, 0);
        state.invalidate();
        state.onClose(0);
        state.onAttack("attack", true, 0);
        assertNull(state.onTickEnd(false, 0));

        state.onClose(0);
        state.onAttack("attack", true, 0);
        assertEquals("attack after client close", state.onTickEnd(false, 0));
    }

    @Test
    void cancelledTransactionDoesNotDiscardCloseEvidence() {
        var state = new PacketOrderP.CloseTickState();
        state.onTickEnd(true, 0);
        state.onClose(0);
        state.onCancelled(PacketType.Play.Client.PONG);
        state.onAttack("attack", true, 0);
        assertEquals("attack after client close", state.onTickEnd(false, 0));

        state.onClose(0);
        state.onCancelled(PacketType.Play.Client.CLOSE_WINDOW);
        state.onAttack("attack", true, 0);
        assertNull(state.onTickEnd(false, 0));
    }

    @Test
    void transactionConfirmationCanProveAServerCloseWithinTheTick() {
        var windows = new CompensatedOpenWindow();
        var state = new PacketOrderP.CloseTickState();
        windows.confirm(windows.serverOpen(7, false));
        state.onTickEnd(windows.mustBeOpen(), windows.getConfirmedServerClose());

        var close = windows.serverClose();
        windows.mayHaveApplied(close);
        state.onAttack("attack", windows.mustBeClosed(), windows.getConfirmedServerClose());
        windows.confirmServerClose(close);
        state.onCancelled(PacketType.Play.Client.PONG);
        state.onAttack("attack", windows.mustBeClosed(), windows.getConfirmedServerClose());

        assertEquals("attack after server close",
                state.onTickEnd(windows.mustBeOpen(), windows.getConfirmedServerClose()));
    }

    @Test
    void ignoresInjectedClosesUntilEveryMitigationIsAcknowledged() {
        var state = new PacketOrderP.CloseTickState();
        state.onTickEnd(true, 0);
        state.beginMitigation();
        state.beginMitigation();
        state.onClose(0);
        state.onClose(0);
        state.onAttack("attack", true, 1);
        assertNull(state.onTickEnd(false, 1));

        state.endMitigation();
        state.onTickEnd(true, 1);
        state.onClose(0);
        state.onAttack("attack", true, 1);
        assertNull(state.onTickEnd(false, 1));

        state.endMitigation();
        state.onClose(0);
        state.onAttack("attack", true, 1);
        assertNull(state.onTickEnd(false, 1));
        state.onClose(0);
        state.onAttack("attack", true, 1);
        assertEquals("attack after client close", state.onTickEnd(false, 1));
    }

    @Test
    void emitsOnlyTheFirstViolationInEachTick() {
        var state = new PacketOrderP.CloseTickState();
        state.onTickEnd(true, 0);
        state.onClose(0);
        state.onClick(0);
        state.onAttack("attack", true, 0);
        state.onClose(0);
        assertEquals("player inventory click after close", state.onTickEnd(false, 0));
        assertNull(state.onTickEnd(false, 0));
    }
}
