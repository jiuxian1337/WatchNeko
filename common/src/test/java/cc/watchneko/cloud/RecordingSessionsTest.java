package cc.watchneko.cloud;

import cc.watchneko.analysis.RotationWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingSessionsTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private static FeatureSample sample(final String id) {
        return new FeatureSample(id, "Steve", new double[RotationWindow.FEATURE_DIMENSIONS]);
    }

    @AfterEach
    void forgetEverything() {
        RecordingSessions.clear();
    }

    @Test
    void aStartedSessionCarriesTheLabelItWasGivenAtTheStart() {
        final RecordingSession session =
                RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();

        assertEquals(Label.CHEAT, session.label());
        assertEquals("rs-1", session.sessionId());
        assertTrue(RecordingSessions.isRecording(PLAYER));
    }

    @Test
    void samplesAccumulateUntilTheSessionEnds() {
        final RecordingSession session =
                RecordingSessions.start(PLAYER, "Notch", Label.LEGIT, "rs-1").orElseThrow();

        session.add(sample("a"));
        session.add(sample("b"));

        assertEquals(2, session.size());
        assertEquals(2, session.samples().size());
    }

    @Test
    void stoppingHandsTheSessionBackAndEndsIt() {
        RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();

        final RecordingSession ended = RecordingSessions.stop(PLAYER).orElseThrow();

        assertEquals("rs-1", ended.sessionId());
        assertFalse(RecordingSessions.isRecording(PLAYER));
    }

    @Test
    void aSecondStartForTheSamePlayerIsRefusedRatherThanReplacingTheFirst() {
        RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();

        assertTrue(RecordingSessions.start(PLAYER, "Notch", Label.LEGIT, "rs-2").isEmpty(),
                "silently discarding a half-finished recording would quietly lose data");
        assertEquals("rs-1", RecordingSessions.active(PLAYER).orElseThrow().sessionId());
    }

    @Test
    void stoppingAPlayerWhoIsNotBeingRecordedYieldsNothing() {
        assertTrue(RecordingSessions.stop(PLAYER).isEmpty());
    }

    @Test
    void sessionsForDifferentPlayersAreIndependent() {
        final UUID other = UUID.randomUUID();
        RecordingSessions.start(PLAYER, "A", Label.CHEAT, "rs-1").orElseThrow();
        RecordingSessions.start(other, "B", Label.LEGIT, "rs-2").orElseThrow();

        assertEquals(Label.CHEAT, RecordingSessions.active(PLAYER).orElseThrow().label());
        assertEquals(Label.LEGIT, RecordingSessions.active(other).orElseThrow().label());

        RecordingSessions.stop(PLAYER);
        assertTrue(RecordingSessions.isRecording(other));
    }

    @Test
    void clearingForgetsEverySessionWithoutUploadingAnything() {
        RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();
        RecordingSessions.clear();
        assertFalse(RecordingSessions.isRecording(PLAYER));
    }

    @Test
    void closingFreezesTheSampleSetButKeepsTheSessionRetrievable() {
        final RecordingSession session =
                RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();
        session.add(sample("a"));
        session.close();

        assertTrue(session.isClosed());
        assertTrue(RecordingSessions.isRecording(PLAYER),
                "a stopped session must stay reachable so a failed upload can be retried");
        assertEquals(1, session.size(), "the samples it stopped with are the samples it keeps");
    }

    @Test
    void aClosedSessionRefusesFurtherSamples() {
        final RecordingSession session =
                RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();
        session.add(sample("a"));
        session.close();

        assertFalse(session.add(sample("b")), "stopping decides what the recording is");
        assertEquals(1, session.size(), "a retry must upload exactly what the admin stopped");
    }

    @Test
    void closingIsIdempotentSoARetryDoesNotDisturbTheSamples() {
        final RecordingSession session =
                RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();
        session.add(sample("a"));
        session.close();
        session.close();

        assertEquals(1, session.size());
        assertTrue(session.isClosed());
    }

    @Test
    void anUploadIsClaimedOnceSoTwoStopsInARowCannotSendTheSameBatchTwice() {
        final RecordingSession session =
                RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();

        assertTrue(session.beginUpload());
        assertFalse(session.beginUpload(), "an in-flight upload must not be started a second time");

        session.endUpload();
        assertTrue(session.beginUpload(), "a finished attempt may be retried");
    }

    @Test
    void theSessionSurvivesAFailedUploadAndCanStillBeStopped() {
        final RecordingSession session =
                RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();
        session.add(sample("a"));
        session.close();

        // What a failed upload does: release the claim and leave the session alone.
        session.beginUpload();
        session.endUpload();

        assertTrue(RecordingSessions.isRecording(PLAYER));
        assertEquals(1, RecordingSessions.stop(PLAYER).orElseThrow().size());
    }

    @Test
    void aLateUploadCallbackDoesNotRemoveANewerSessionForTheSamePlayer() {
        final RecordingSession uploaded =
                RecordingSessions.start(PLAYER, "Notch", Label.CHEAT, "rs-1").orElseThrow();
        uploaded.close();

        // The admin discards while the upload is still in flight, then starts a fresh recording.
        RecordingSessions.stop(PLAYER);
        final RecordingSession newer =
                RecordingSessions.start(PLAYER, "Notch", Label.LEGIT, "rs-2").orElseThrow();

        // The old upload now finishes successfully and tries to clean up after itself.
        assertFalse(RecordingSessions.stopIfCurrent(PLAYER, uploaded),
                "the callback must not match a session it did not start");
        assertTrue(RecordingSessions.isRecording(PLAYER));
        assertEquals("rs-2", RecordingSessions.active(PLAYER).orElseThrow().sessionId());
        assertTrue(RecordingSessions.stopIfCurrent(PLAYER, newer), "the current session is still removable");
    }
}
