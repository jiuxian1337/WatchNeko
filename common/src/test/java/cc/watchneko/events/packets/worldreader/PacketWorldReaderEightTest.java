package cc.watchneko.events.packets.worldreader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketWorldReaderEightTest {
    @Test
    void distinguishesUnloadFromFullAndPartialChunkUpdates() {
        assertTrue(PacketWorldReaderEight.isUnload(true, 0));
        assertFalse(PacketWorldReaderEight.isUnload(false, 0));
        assertFalse(PacketWorldReaderEight.isUnload(true, 1));
    }
}
