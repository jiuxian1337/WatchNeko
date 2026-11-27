package cc.watchneko.utils.chunks;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v1_16.Chunk_v1_9;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.GlobalPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.ListPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.MapPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.Palette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.SingletonPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BaseStorage;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BitStorage;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.LegacyFlexibleStorage;

import java.util.Arrays;

/** A block-state palette and its storage owned exclusively by an immutable shared section. */
final class SharedChunkSection implements BaseChunk {
    private static final int BLOCKS_PER_SECTION = PaletteType.CHUNK.getStorageSize();

    private final ClientVersion version;
    private final int blockCount;
    private final int fluidCount;
    private final boolean empty;
    private final int paletteBits;
    private final int[] states;
    private final BaseStorage storage;
    private final int hash;
    private final ContentKey contentKey;

    SharedChunkSection(ClientVersion version, DataPalette data, int blockCount, int fluidCount, boolean empty) {
        this.version = version;
        this.blockCount = blockCount;
        this.fluidCount = fluidCount;
        this.empty = empty;
        this.paletteBits = data.palette.getBits();
        this.contentKey = null;
        if (data.palette instanceof GlobalPalette) {
            // With a global palette, stored values are already block state IDs.
            this.states = null;
        } else {
            this.states = new int[data.palette.size()];
            for (int i = 0; i < states.length; i++) {
                states[i] = data.palette.idToState(i);
            }
        }
        // Borrow decoded storage for the lookup. Only a detached copy may enter the pool.
        this.storage = data.storage;

        int result = version.hashCode();
        result = 31 * result + blockCount;
        result = 31 * result + fluidCount;
        result = 31 * result + Boolean.hashCode(empty);
        result = 31 * result + paletteBits;
        result = 31 * result + Arrays.hashCode(states);
        if (storage != null) {
            result = 31 * result + storage.getBitsPerEntry();
            result = 31 * result + Boolean.hashCode(storage instanceof LegacyFlexibleStorage);
            result = 31 * result + Arrays.hashCode(storage.getData());
        }
        this.hash = result;
    }

    private SharedChunkSection(SharedChunkSection source, int contentHash) {
        this.version = source.version;
        this.blockCount = source.blockCount;
        this.fluidCount = source.fluidCount;
        this.empty = source.empty;
        this.paletteBits = source.paletteBits;
        this.states = source.states;
        this.storage = copyStorage(source.storage);
        this.hash = source.hash;
        this.contentKey = new ContentKey(this, contentHash);
    }

    SharedChunkSection detachedCopy(ContentKey key) {
        return new SharedChunkSection(this, key.hashCode());
    }

    @Override
    public int getBlockId(int x, int y, int z) {
        return stateAt((y << 8) | (z << 4) | x);
    }

    private int stateAt(int index) {
        int id = storage == null ? 0 : storage.get(index);
        if (states == null) return id;
        return id >= 0 && id < states.length ? states[id] : 0;
    }

    ContentKey contentKey() {
        if (contentKey != null) return contentKey;
        int contentHash = version.hashCode();
        contentHash = 31 * contentHash + blockCount;
        contentHash = 31 * contentHash + fluidCount;
        contentHash = 31 * contentHash + Boolean.hashCode(empty);
        for (int index = 0; index < BLOCKS_PER_SECTION; index++) {
            contentHash = 31 * contentHash + stateAt(index);
        }
        return new ContentKey(this, contentHash);
    }

    @Override
    public boolean isEmpty() {
        return empty;
    }

    @Override
    public void set(int x, int y, int z, int combinedID) {
        throw new UnsupportedOperationException("Copy the shared chunk section before writing");
    }

    BaseChunk copyForWrite() {
        DataPalette palette = new DataPalette(copyPalette(), copyStorage(storage), PaletteType.CHUNK);
        if (version.isNewerThanOrEquals(ClientVersion.V_1_18)) {
            return new Chunk_v1_18(version, blockCount, fluidCount, palette, null);
        }
        return new Chunk_v1_9(blockCount, palette);
    }

    private Palette copyPalette() {
        if (storage == null) return new SingletonPalette(getBlockId(0, 0, 0));
        if (states == null) return GlobalPalette.INSTANCE;
        if (paletteBits > PaletteType.CHUNK.getMaxBitsPerEntryForList()) {
            MapPalette palette = new MapPalette(paletteBits);
            for (int i = 0; i < states.length; i++) {
                if (palette.stateToId(states[i]) != i) {
                    // Preserve the original indices even when the source has duplicate palette entries.
                    return new ListPalette(paletteBits, states);
                }
            }
            return palette;
        }
        return new ListPalette(paletteBits, states);
    }

    private static BaseStorage copyStorage(BaseStorage storage) {
        if (storage == null) return null;
        long[] words = storage.getData().clone();
        if (storage instanceof LegacyFlexibleStorage) {
            return new LegacyFlexibleStorage(storage.getBitsPerEntry(), words);
        }
        return new BitStorage(storage.getBitsPerEntry(), PaletteType.CHUNK.getStorageSize(), words);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SharedChunkSection section)) return false;
        if (hash != section.hash || version != section.version || blockCount != section.blockCount
                || fluidCount != section.fluidCount || empty != section.empty || paletteBits != section.paletteBits
                || !Arrays.equals(states, section.states)) return false;
        if (storage == null || section.storage == null) return storage == section.storage;
        return storage.getClass() == section.storage.getClass()
                && storage.getBitsPerEntry() == section.storage.getBitsPerEntry()
                && Arrays.equals(storage.getData(), section.storage.getData());
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /** Compared only after the packed lookup misses, so differing palette orders can still share. */
    static final class ContentKey {
        private final SharedChunkSection section;
        private final int hash;

        private ContentKey(SharedChunkSection section, int hash) {
            this.section = section;
            this.hash = hash;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ContentKey key) || hash != key.hash) return false;
            SharedChunkSection target = key.section;
            if (section.version != target.version || section.blockCount != target.blockCount
                    || section.fluidCount != target.fluidCount || section.empty != target.empty) return false;
            for (int index = 0; index < BLOCKS_PER_SECTION; index++) {
                if (section.stateAt(index) != target.stateAt(index)) return false;
            }
            return true;
        }
    }
}
