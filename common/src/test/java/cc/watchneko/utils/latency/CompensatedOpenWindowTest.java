package cc.watchneko.utils.latency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompensatedOpenWindowTest {

    @Test
    void openingNeedsTheTrailingReceipt() {
        var windows = new CompensatedOpenWindow();
        var open = windows.serverOpen(7, false);
        assertTrue(windows.mustBeClosed());

        windows.mayHaveApplied(open);
        assertUncertain(windows);

        windows.confirm(open);
        assertTrue(windows.mustBeOpen());
    }

    @Test
    void closingCanHaveHappenedBeforeTheTrailingReceipt() {
        var windows = new CompensatedOpenWindow();
        windows.confirm(windows.serverOpen(7, false));
        var close = windows.serverClose();
        assertTrue(windows.mustBeOpen());

        windows.mayHaveApplied(close);
        assertUncertain(windows);

        windows.confirm(close);
        assertTrue(windows.mustBeClosed());
    }

    @Test
    void closingThePreviousContainerDoesNotDiscardTheReplacement() {
        var windows = new CompensatedOpenWindow();
        windows.confirm(windows.serverOpen(7, false));
        var replacement = windows.serverOpen(8, false);
        windows.mayHaveApplied(replacement);
        assertTrue(windows.mustBeOpen());

        windows.onClientClose(7);
        assertUncertain(windows);

        windows.confirm(replacement);
        windows.onClientClose(7);
        assertTrue(windows.mustBeOpen());
        windows.onClientClose(8);
        assertTrue(windows.mustBeClosed());
    }

    @Test
    void closingTheNewContainerBeforeItsReceiptDoesNotReopenIt() {
        var windows = new CompensatedOpenWindow();
        var open = windows.serverOpen(7, false);
        windows.mayHaveApplied(open);

        windows.onClientClose(7);
        windows.confirm(open);
        assertTrue(windows.mustBeClosed());
    }

    @Test
    void oldProofKeepsNewerPossibleChanges() {
        var windows = new CompensatedOpenWindow();
        var open = windows.serverOpen(7, false);
        var close = windows.serverClose();
        windows.mayHaveApplied(open);
        windows.mayHaveApplied(close);

        windows.confirm(open);
        assertUncertain(windows);

        windows.confirm(close);
        windows.confirm(open);
        assertTrue(windows.mustBeClosed());
    }

    @Test
    void clickCanConfirmOnlyAContainerTheServerMayHaveOpened() {
        var windows = new CompensatedOpenWindow();
        var open = windows.serverOpen(7, false);
        windows.mayHaveApplied(open);

        windows.onContainerClick(8);
        assertUncertain(windows);
        windows.onContainerClick(7);
        assertTrue(windows.mustBeOpen());
    }

    @Test
    void legacyPlayerInventoryClickAloneDoesNotProveItOpened() {
        var windows = new CompensatedOpenWindow();
        windows.onContainerClick(CompensatedOpenWindow.PLAYER_INVENTORY);
        assertTrue(windows.mustBeClosed());

        windows.onPlayerInventoryOpened();
        assertTrue(windows.mustBeOpen());
        windows.onClientClose(CompensatedOpenWindow.PLAYER_INVENTORY);
        assertTrue(windows.mustBeClosed());
    }

    @Test
    void legacyWindowCanStayClosedEvenAfterTheReceipt() {
        var windows = new CompensatedOpenWindow();
        windows.confirm(windows.serverOpen(7, true));
        assertUncertain(windows);

        windows.onContainerClick(7);
        assertTrue(windows.mustBeOpen());
        windows.mayHaveClosed();
        assertUncertain(windows);
    }

    @Test
    void closeDuringSameIdReplacementKeepsBothOutcomes() {
        var windows = new CompensatedOpenWindow();
        windows.confirm(windows.serverOpen(7, false));
        var replacement = windows.serverOpen(7, false);
        windows.mayHaveApplied(replacement);

        windows.onClientClose(7);
        windows.confirm(replacement);
        assertUncertain(windows);
    }

    @Test
    void aReusedIdOnlyIdentifiesAContainerOnceItsOpenSequenceIsUnambiguous() {
        var windows = new CompensatedOpenWindow();
        var original = windows.serverOpen(7, false);
        windows.confirm(original);
        assertEquals(original.sequence(), windows.getContainerSequence(7));
        assertEquals(-1, windows.getContainerSequence(8));

        var replacement = windows.serverOpen(7, false);
        windows.mayHaveApplied(replacement);
        windows.onContainerClick(7);
        assertEquals(-1, windows.getContainerSequence(7));

        windows.confirm(replacement);
        assertEquals(replacement.sequence(), windows.getContainerSequence(7));
        windows.onClientClose(7);
        assertEquals(-1, windows.getContainerSequence(7));
    }

    @Test
    void onlyAConfirmedServerCloseAdvancesTheCloseEvidence() {
        var windows = new CompensatedOpenWindow();
        windows.confirm(windows.serverOpen(7, false));
        var close = windows.serverClose();
        windows.mayHaveApplied(close);
        assertEquals(0, windows.getConfirmedServerClose());

        windows.confirmServerClose(close);
        assertEquals(close.sequence(), windows.getConfirmedServerClose());
        assertTrue(windows.mustBeClosed());
    }

    @Test
    void respawnAndStaleServerClosesDoNotCreateCloseEvidence() {
        var windows = new CompensatedOpenWindow();
        var staleClose = windows.serverClose();
        var newerOpen = windows.serverOpen(7, false);
        windows.confirm(newerOpen);
        windows.confirmServerClose(staleClose);
        assertEquals(0, windows.getConfirmedServerClose());
        assertTrue(windows.mustBeOpen());

        var respawn = windows.serverClose();
        windows.confirm(respawn);
        assertEquals(0, windows.getConfirmedServerClose());
        assertTrue(windows.mustBeClosed());
    }

    private static void assertUncertain(CompensatedOpenWindow windows) {
        assertFalse(windows.mustBeOpen());
        assertFalse(windows.mustBeClosed());
    }
}
