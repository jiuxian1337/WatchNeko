package cc.watchneko.utils.chunks;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v1_16.Chunk_v1_9;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.ListPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.SingletonPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BitStorage;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.LegacyFlexibleStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSectionPoolTest {
    private static final ClientVersion MODERN = ClientVersion.V_1_21_4;
    private static final ClientVersion LEGACY = ClientVersion.V_1_8;

    @Test
    void differentPaletteOrdersShareTheSameSection() {
        var first = palette(0, 11, 7);
        first.storage.set(1, 1);
        first.storage.set(4095, 2);
        var reordered = palette(0, 7, 11);
        reordered.storage.set(1, 2);
        reordered.storage.set(4095, 1);

        BaseChunk section = shareModern(first, 2, 0);
        assertSame(section, shareModern(reordered, 2, 0));
        assertEquals(11, section.getBlockId(1, 0, 0));
        assertEquals(7, section.getBlockId(15, 15, 15));
    }

    @Test
    void equallyHashedBlockSequencesDoNotShareDifferentContent() {
        var nextBlock = palette(0, 31);
        nextBlock.storage.set(1, 1);
        var firstBlock = palette(0, 1);
        firstBlock.storage.set(0, 1);

        // Both sequences have the same polynomial hash: [0, 31, 0, ...] and [1, 0, 0, ...].
        BaseChunk first = shareModern(nextBlock, 1, 0);
        BaseChunk second = shareModern(firstBlock, 1, 0);
        assertNotSame(first, second);
        assertEquals(31, first.getBlockId(1, 0, 0));
        assertEquals(1, second.getBlockId(0, 0, 0));
    }

    @Test
    void theDecodedPaletteCanChangeAfterSharing() {
        var decoded = palette(0, 19);
        decoded.storage.set(0, 1);
        BaseChunk shared = shareModern(decoded, 1, 0);

        decoded.set(0, 0, 0, 42);
        assertEquals(19, shared.getBlockId(0, 0, 0));
        assertThrows(UnsupportedOperationException.class, () -> shared.set(0, 0, 0, 42));
    }

    @Test
    void aPlayerCopiesASharedSectionOnceOnFirstWrite() {
        BaseChunk shared = ChunkSectionPool.shareLegacyPalette(LEGACY, palette(4), 4096);
        Column first = new Column(0, 0, new BaseChunk[]{shared}, 1);
        Column second = new Column(0, 0, new BaseChunk[]{shared}, 1);

        BaseChunk writable = first.sectionForWrite(0);
        writable.set(2, 1, 3, 0);
        assertNotSame(shared, writable);
        assertSame(writable, first.sectionForWrite(0));
        assertEquals(0, first.chunks()[0].getBlockId(2, 1, 3));
        assertSame(shared, second.chunks()[0]);
        assertEquals(4, second.chunks()[0].getBlockId(2, 1, 3));
        assertFalse(second.chunks()[0].isEmpty());
    }

    @Test
    void aSingletonCopyCanExpandItsPaletteAndRetainsCounts() {
        var constant = new DataPalette(new SingletonPalette(37), null, PaletteType.CHUNK);
        BaseChunk shared = shareModern(constant, 4096, 4096);
        var column = new Column(0, 0, new BaseChunk[]{shared}, 1);
        var writable = (Chunk_v1_18) column.sectionForWrite(0);

        assertEquals(4096, writable.getBlockCount());
        assertEquals(4096, writable.getFluidCount());
        assertNull(writable.getBiomeData());
        writable.getChunkData().set(0, 0, 0, 1);
        assertEquals(1, writable.getBlockId(0, 0, 0));
        assertEquals(37, writable.getBlockId(1, 0, 0));
        assertEquals(37, shared.getBlockId(0, 0, 0));
    }

    @Test
    void aPartialColumnUpdateReplacesPresentSectionsOnly() {
        BaseChunk first = ChunkSectionPool.shareLegacyPalette(LEGACY, palette(1), 4096);
        BaseChunk second = ChunkSectionPool.shareLegacyPalette(LEGACY, palette(2), 4096);
        BaseChunk[] sections = new BaseChunk[16];
        sections[0] = first;
        sections[1] = first;
        Column column = new Column(0, 0, sections, 1);
        BaseChunk[] partial = new BaseChunk[16];
        partial[1] = second;

        column.mergeChunks(partial);
        assertSame(first, column.chunks()[0]);
        assertSame(second, column.chunks()[1]);
        assertNull(column.chunks()[2]);
        column.sectionForWrite(1).set(0, 3, 0, 1);
        assertEquals(2, second.getBlockId(0, 3, 0));
    }

    @Test
    void legacyStorageAcrossWordBoundariesKeepsItsBlockIds() {
        var source = new DataPalette(new ListPalette(5, new int[]{0, 16}), new LegacyFlexibleStorage(5, 4096), PaletteType.CHUNK);
        source.storage.set(12, 1);
        var decoded = new Chunk_v1_9(Integer.MAX_VALUE, source);
        var version = ClientVersion.V_1_12_2;
        BaseChunk[] first = ChunkSectionPool.shareSections(version, new BaseChunk[]{decoded});
        BaseChunk[] second = ChunkSectionPool.shareSections(version, new BaseChunk[]{decoded});

        assertSame(first[0], second[0]);
        assertEquals(16, first[0].getBlockId(12, 0, 0));
        new Column(0, 0, first, 1).sectionForWrite(0).set(12, 0, 0, 0);
        assertTrue(first[0].isEmpty());
        assertEquals(16, second[0].getBlockId(12, 0, 0));
    }

    @Test
    void aRevertedWritableSectionCanRejoinTheSharedPool() {
        BaseChunk shared = shareModern(palette(21), 4096, 0);
        var column = new Column(0, 0, new BaseChunk[]{shared}, 1);
        var writable = (Chunk_v1_18) column.sectionForWrite(0);
        writable.getChunkData().set(0, 0, 0, 22);
        assertNotSame(shared, ChunkSectionPool.shareSection(MODERN, writable));
        writable.getChunkData().set(0, 0, 0, 21);
        assertSame(shared, ChunkSectionPool.shareSection(MODERN, writable));
    }

    @Test
    void concurrentLoadersShareOneCopy() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            var sections = new ArrayList<Future<BaseChunk>>();
            for (int i = 0; i < 32; i++) {
                sections.add(executor.submit(() -> shareModern(palette(69), 4096, 0)));
            }
            BaseChunk shared = sections.get(0).get();
            for (Future<BaseChunk> section : sections) {
                assertSame(shared, section.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static BaseChunk shareModern(DataPalette data, int blocks, int fluids) {
        var decoded = new Chunk_v1_18(MODERN, blocks, fluids, data, PaletteType.BIOME.create());
        return ChunkSectionPool.shareSections(MODERN, new BaseChunk[]{decoded})[0];
    }

    private static DataPalette palette(int... ids) {
        return new DataPalette(new ListPalette(4, ids), new BitStorage(4, 4096), PaletteType.CHUNK);
    }
}
