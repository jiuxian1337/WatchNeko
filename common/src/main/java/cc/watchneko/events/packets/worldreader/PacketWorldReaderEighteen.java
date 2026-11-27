package cc.watchneko.events.packets.worldreader;

import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.chunks.ChunkSectionPool;
import cc.watchneko.utils.latency.CompensatedWorld;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.HeightmapType;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.protocol.world.chunk.reader.impl.ChunkReader_v1_18;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionTypes;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.netty.buffer.ByteBuf;

public class PacketWorldReaderEighteen extends BasePacketWorldReader {

    private static final ChunkReader_v1_18 CHUNK_READER_V_1_18 = new ChunkReader_v1_18();
    private static final boolean PRE_1_21_5 = PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_21_5);

    // Mojang decided to include lighting in this packet.  It's inefficient to read it, so we replace PacketEvents logic.
    @Override
    public void handleMapChunk(PlayerData player, PacketSendEvent event) {
        PacketWrapper<?> wrapper = new PacketWrapper<>(event);

        int x = wrapper.readInt();
        int z = wrapper.readInt();

        // Skip past heightmaps
        if (PRE_1_21_5)
            wrapper.readNBT();
        else
            wrapper.readMap(HeightmapType::read, PacketWrapper::readLongArray);

        if (player.movementSimulation.isUnified()) {
            int start = ((ByteBuf) wrapper.getBuffer()).readerIndex();
            BaseChunk[] shared = readSharedSections(event, wrapper);
            if (shared != null) {
                addChunkToCache(event, player, shared, true, x, z);
                event.setLastUsedWrapper(null);
                return;
            }
            ((ByteBuf) wrapper.getBuffer()).readerIndex(start);
        }

        // Retain the standard reader for formats we cannot scan safely.
        BaseChunk[] chunks = CHUNK_READER_V_1_18.read(
                DimensionTypes.OVERWORLD, null, null, true, false, false,
                event.getUser().getTotalWorldHeight() >> 4,
                wrapper.readVarInt(), // Length of chunk data length (arrayLength) to pass to the new ChunkReader method
                wrapper
        );

        addChunkToCache(event, player, chunks, true, x, z);

        event.setLastUsedWrapper(null); // Prevent PacketEvents from using this incomplete wrapper later
    }

    private BaseChunk[] readSharedSections(PacketSendEvent event, PacketWrapper<?> wrapper) {
        ByteBuf buffer = (ByteBuf) wrapper.getBuffer();
        try {
            int length = wrapper.readVarInt();
            if (length < 0 || length > buffer.readableBytes()) return null;
            ByteBuf sections = buffer.readSlice(length);
            PacketWrapper<?> input = PacketWrapper.createUniversalPacketWrapper(sections, wrapper.getServerVersion());
            BaseChunk[] chunks = new BaseChunk[event.getUser().getTotalWorldHeight() >> 4];
            for (int i = 0; i < chunks.length; i++) {
                int count = input.readShort();
                int fluids = wrapper.getServerVersion().isNewerThanOrEquals(ServerVersion.V_26_1) ? input.readShort() : 0;
                DataPalette data = DataPalette.read(input, PaletteType.CHUNK);
                if (!skipBiomes(input, sections)) return null;
                chunks[i] = ChunkSectionPool.shareModernPalette(CompensatedWorld.blockVersion, data, count, fluids);
            }
            return sections.isReadable() ? null : chunks;
        } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
            return null;
        }
    }

    private boolean skipBiomes(PacketWrapper<?> input, ByteBuf buffer) {
        int bits = input.readUnsignedByte();
        if (bits > 32) return false;
        if (bits == 0) {
            input.readVarInt();
        } else if (bits <= PaletteType.BIOME.getMaxBitsPerEntryForMap()) {
            int entries = input.readVarInt();
            if (entries < 0 || entries > (1 << bits)) return false;
            for (int i = 0; i < entries; i++) input.readVarInt();
        }
        int words;
        if (PRE_1_21_5) words = input.readVarInt();
        else if (bits == 0) words = 0;
        else {
            int perWord = 64 / bits;
            words = (PaletteType.BIOME.getStorageSize() + perWord - 1) / perWord;
        }
        if (words < 0 || words > buffer.readableBytes() / Long.BYTES) return false;
        buffer.skipBytes(words * Long.BYTES);
        return true;
    }
}
