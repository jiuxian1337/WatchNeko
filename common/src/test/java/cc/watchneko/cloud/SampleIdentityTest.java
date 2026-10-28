package cc.watchneko.cloud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sample ids carry the player's name, so these tests pin the shape and the normalisation rather than
 * privacy — there is none to protect here any more, by design.
 */
class SampleIdentityTest {

    private static final String NAME = "Steve";

    @Test
    void anIdIsBuiltFromTheNameAndACounter() {
        assertEquals("Steve-7", SampleIdentity.of(NAME, 7));
    }

    @Test
    void theSamePlayerAtDifferentCountsGetsDifferentIds() {
        assertFalse(SampleIdentity.of(NAME, 1).equals(SampleIdentity.of(NAME, 2)));
    }

    @Test
    void spacesAreReplacedSoTheIdStaysReadable() {
        assertEquals("Notch_Is_Cool-1", SampleIdentity.of("Notch Is Cool", 1));
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertEquals("Steve-1", SampleIdentity.of("  Steve  ", 1));
    }

    @Test
    void aMissingNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SampleIdentity.of("", 1));
        assertThrows(IllegalArgumentException.class, () -> SampleIdentity.of("   ", 1));
        assertThrows(IllegalArgumentException.class, () -> SampleIdentity.of(null, 1));
    }

    @Test
    void recordingIdsFromDifferentSessionsNeverCollideEvenAtTheSameCounter() {
        // A reconnect rebuilds the per-player counter, so both sessions start at 1. The cloud stores
        // samples by id and rejects ones it already has, so an id shared across sessions silently loses
        // the whole second recording.
        assertFalse(
                SampleIdentity.forRecording(NAME, "rs-a", 1)
                        .equals(SampleIdentity.forRecording(NAME, "rs-b", 1)),
                "two recordings of the same player must not produce the same sample id");
    }

    @Test
    void aRecordingIdIsStableForTheSameSessionAndCounter() {
        assertEquals(
                SampleIdentity.forRecording(NAME, "rs-a", 7),
                SampleIdentity.forRecording(NAME, "rs-a", 7),
                "the id must be reproducible, or a retried upload could not be recognised as a replay");
    }

    @Test
    void aRecordingIdCarriesTheNameSessionAndCounter() {
        final String id = SampleIdentity.forRecording(NAME, "rs-a", 7);

        assertTrue(id.startsWith("Steve"));
        assertTrue(id.contains("rs-a"));
        assertTrue(id.endsWith("-7"));
    }

    @Test
    void aRecordingIdWithoutASessionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SampleIdentity.forRecording(NAME, null, 1));
        assertThrows(IllegalArgumentException.class, () -> SampleIdentity.forRecording(NAME, "  ", 1));
    }
}
