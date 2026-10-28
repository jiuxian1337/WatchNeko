package cc.watchneko.cloud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code /watchneko record stop <player>} argument is completed from the sessions in progress, so the
 * list has to be read when the command is completed — not captured when the command is registered.
 */
class RecordingSessionsSuggestionsTest {

    @AfterEach
    void forgetEverything() {
        RecordingSessions.clear();
    }

    /** The same query the suggestion provider runs. */
    private static List<String> recordedNames() {
        return RecordingSessions.all().stream()
                .map(RecordingSession::playerName)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @Test
    void anEmptyServerSuggestsNothing() {
        assertTrue(recordedNames().isEmpty());
    }

    @Test
    void onlyPlayersBeingRecordedAreSuggested() {
        RecordingSessions.start(UUID.randomUUID(), "Notch", Label.CHEAT, "rs-1");
        RecordingSessions.start(UUID.randomUUID(), "Steve", Label.LEGIT, "rs-2");

        assertEquals(List.of("Notch", "Steve"), recordedNames());
    }

    @Test
    void aStoppedSessionDropsOutOfTheSuggestions() {
        final UUID player = UUID.randomUUID();
        final RecordingSession session =
                RecordingSessions.start(player, "Notch", Label.CHEAT, "rs-1").orElseThrow();
        assertEquals(List.of("Notch"), recordedNames());

        RecordingSessions.stop(session.player());

        assertTrue(recordedNames().isEmpty());
    }

    @Test
    void aPausedSessionIsStillOffered() {
        // A player who disconnected mid-recording still has samples to resolve, and the admin has to be
        // able to name them — that is the whole reason stop takes a name instead of a selector.
        final UUID player = UUID.randomUUID();
        RecordingSessions.start(player, "Notch", Label.CHEAT, "rs-1");

        RecordingSessions.pause(player);

        assertEquals(List.of("Notch"), recordedNames());
    }
}
