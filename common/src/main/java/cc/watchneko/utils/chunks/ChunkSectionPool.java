package cc.watchneko.utils.chunks;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.GlobalPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Shares equivalent packed block-state sections between compensated world caches. */
public final class ChunkSectionPool {
    private static final WeakHashMap<SharedChunkSection, WeakReference<SharedChunkSection>> PACKED_SECTIONS = new WeakHashMap<>();
    private static final WeakHashMap<SharedChunkSection.ContentKey, WeakReference<SharedChunkSection>> SECTION_CONTENTS = new WeakHashMap<>();

    private ChunkSectionPool() {}

    public static BaseChunk[] shareSections(ClientVersion version, BaseChunk[] decoded) {
        BaseChunk[] sections = new BaseChunk[decoded.length];
        for (int i = 0; i < decoded.length; i++) {
            sections[i] = shareSection(version, decoded[i]);
        }
        return sections;
    }

    // The 1.8 reader already has the decoded palette, so it can skip repacking the block IDs.
    public static BaseChunk shareLegacyPalette(ClientVersion version, DataPalette data, int blockCount) {
        return share(new SharedChunkSection(version, data, blockCount, 0, blockCount == 0));
    }

    public static BaseChunk shareModernPalette(ClientVersion version, DataPalette data, int blocks, int fluids) {
        return share(new SharedChunkSection(version, data, blocks, fluids, blocks == 0));
    }

    public static BaseChunk shareSection(ClientVersion version, BaseChunk section) {
        if (section == null || section instanceof SharedChunkSection) return section;
        if (section instanceof Chunk_v1_18 modern) {
            return shareModernPalette(version, modern.getChunkData(), modern.getBlockCount(), modern.getFluidCount());
        }

        // Older PacketEvents section types expose block IDs, but their packed palettes are private.
        DataPalette packed = PaletteType.CHUNK.create();
        packed.palette.stateToId(0);
        boolean hasBlockCount = version.isNewerThanOrEquals(ClientVersion.V_1_14);
        int blockCount = 0;
        int previousId = 0;
        boolean previousWasBlock = false;
        for (int index = 0; index < PaletteType.CHUNK.getStorageSize(); index++) {
            int x = index & 15, y = index >> 8, z = (index >> 4) & 15;
            int id = section.getBlockId(x, y, z);
            if (id == 0) continue;
            if (id < 0 || id >= (1 << GlobalPalette.BITS_PER_ENTRY)) return section;
            packed.set(x, y, z, id);
            if (id != previousId) {
                previousWasBlock = !hasBlockCount || !WrappedBlockState.getByGlobalId(version, id, false).getType().isAir();
                previousId = id;
            }
            if (previousWasBlock) blockCount++;
        }
        boolean empty = blockCount == 0;
        if (hasBlockCount && section.isEmpty() != empty) return section;
        return share(new SharedChunkSection(version, packed, hasBlockCount ? blockCount : Integer.MAX_VALUE, 0, empty));
    }

    private static SharedChunkSection share(SharedChunkSection candidate) {
        synchronized (PACKED_SECTIONS) {
            WeakReference<SharedChunkSection> reference = PACKED_SECTIONS.get(candidate);
            SharedChunkSection existing = reference == null ? null : reference.get();
            if (existing != null) return existing;
        }

        SharedChunkSection.ContentKey contentKey = candidate.contentKey();
        synchronized (PACKED_SECTIONS) {
            WeakReference<SharedChunkSection> reference = SECTION_CONTENTS.get(contentKey);
            SharedChunkSection existing = reference == null ? null : reference.get();
            if (existing != null) return existing;

            // Both indexes have weak keys and values. A section becomes collectable with its columns.
            SharedChunkSection shared = candidate.detachedCopy(contentKey);
            WeakReference<SharedChunkSection> sharedReference = new WeakReference<>(shared);
            PACKED_SECTIONS.put(shared, sharedReference);
            SECTION_CONTENTS.put(shared.contentKey(), sharedReference);
            return shared;
        }
    }
}
