package cc.watchneko.events.packets.worldreader;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.chunks.ChunkSectionPool;
import cc.watchneko.utils.chunks.Column;
import cc.watchneko.utils.latency.CompensatedWorld;
import cc.watchneko.utils.data.TeleportData;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.*;

public class BasePacketWorldReader extends PacketListenerAbstract {

    public BasePacketWorldReader() {
        super(PacketListenerPriority.HIGH);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.UNLOAD_CHUNK) {
            WrapperPlayServerUnloadChunk unloadChunk = new WrapperPlayServerUnloadChunk(event);
            PlayerData player = WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            unloadChunk(event, player, unloadChunk.getChunkX(), unloadChunk.getChunkZ());
        }

        // 1.7 and 1.8 only
        if (event.getPacketType() == PacketType.Play.Server.MAP_CHUNK_BULK) {
            PlayerData player = WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            handleMapChunkBulk(player, event);
        }

        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            PlayerData player = WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            handleMapChunk(player, event);
        }

        if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
            PlayerData player = WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            handleBlockChange(player, event);
        }

        if (event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            PlayerData player = WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            handleMultiBlockChange(player, event);
        }

        if (event.getPacketType() == PacketType.Play.Server.ACKNOWLEDGE_BLOCK_CHANGES) {
            PlayerData player = WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            WrapperPlayServerAcknowledgeBlockChanges changes = new WrapperPlayServerAcknowledgeBlockChanges(event);
            if (player.movementSimulation.isUnified()) {
                player.movementReceipts.after(event, () -> {}, receipt -> player.compensatedWorld.handlePredictionConfirmation(changes.getSequence()));
            } else {
                player.compensatedWorld.handlePredictionConfirmation(changes.getSequence());
            }
        }

        if (event.getPacketType() == PacketType.Play.Server.ACKNOWLEDGE_PLAYER_DIGGING) {
            PlayerData player = WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            WrapperPlayServerAcknowledgePlayerDigging ack = new WrapperPlayServerAcknowledgePlayerDigging(event);
            if (player.movementSimulation.isUnified()) {
                player.movementReceipts.after(event, () -> {}, receipt -> player.compensatedWorld.handleBlockBreakAck(
                        ack.getBlockPosition(), ack.getBlockId(), ack.getAction(), ack.isSuccessful()));
            } else {
                player.compensatedWorld.handleBlockBreakAck(ack.getBlockPosition(), ack.getBlockId(), ack.getAction(), ack.isSuccessful());
            }
        }

        if (event.getPacketType() == PacketType.Play.Server.CHANGE_GAME_STATE) {
            PlayerData player = WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            WrapperPlayServerChangeGameState newState = new WrapperPlayServerChangeGameState(event);

            var reason = newState.getReason();
            if (reason != WrapperPlayServerChangeGameState.Reason.BEGIN_RAINING
                    && reason != WrapperPlayServerChangeGameState.Reason.END_RAINING
                    && reason != WrapperPlayServerChangeGameState.Reason.RAIN_LEVEL_CHANGE) return;

            boolean raining = reason == WrapperPlayServerChangeGameState.Reason.BEGIN_RAINING
                    || reason == WrapperPlayServerChangeGameState.Reason.RAIN_LEVEL_CHANGE && newState.getValue() > 0.2f;
            Runnable updateWeather = () -> player.compensatedWorld.isRaining = raining;
            if (player.movementSimulation.isUnified()) {
                player.movementReceipts.after(event, () -> {}, receipt -> updateWeather.run());
            } else {
                player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), updateWeather);
            }
        }
    }

    public void handleMapChunkBulk(PlayerData player, PacketSendEvent event) {
        // Only exists in 1.7 and 1.8
        WrapperPlayServerChunkDataBulk chunkData = new WrapperPlayServerChunkDataBulk(event);
        for (int i = 0; i < chunkData.getChunks().length; i++) {
            addChunkToCache(event, player, chunkData.getChunks()[i], true, chunkData.getX()[i], chunkData.getZ()[i]);
        }
    }

    public void handleMapChunk(PlayerData player, PacketSendEvent event) {
        WrapperPlayServerChunkData chunkData = new WrapperPlayServerChunkData(event);
        addChunkToCache(event, player, chunkData.getColumn().getChunks(), chunkData.getColumn().isFullChunk(), chunkData.getColumn().getX(), chunkData.getColumn().getZ());
        event.setLastUsedWrapper(null);
    }

    public void addChunkToCache(PacketSendEvent event, PlayerData player, BaseChunk[] chunks, boolean isGroundUp, int chunkX, int chunkZ) {
        BaseChunk[] sections = ChunkSectionPool.shareSections(CompensatedWorld.blockVersion, chunks);
        if (player.movementSimulation.isUnified()) {
            player.compensatedWorld.trackServerColumn(event, chunkX, chunkZ, sections, isGroundUp);
            return;
        }
        double chunkCenterX = (chunkX << 4) + 8;
        double chunkCenterZ = (chunkZ << 4) + 8;
        boolean shouldPostTrans = Math.abs(player.x - chunkCenterX) < 16 && Math.abs(player.z - chunkCenterZ) < 16;

        for (TeleportData teleports : player.getSetbackTeleportUtil().pendingTeleports) {
            if (teleports.getFlags().getMask() != 0) {
                continue; // Worse that will happen is people will get an extra setback...
            }
            shouldPostTrans = shouldPostTrans || (Math.abs(teleports.getLocation().getX() - chunkCenterX) < 16 && Math.abs(teleports.getLocation().getZ() - chunkCenterZ) < 16);
        }

        if (shouldPostTrans) {
            event.getTasksAfterSend().add(player::sendTransaction); // Player is in this unloaded chunk
        }
        if (isGroundUp) {
            Column column = new Column(chunkX, chunkZ, sections, player.lastTransactionSent.get());
            player.compensatedWorld.addToCache(column, chunkX, chunkZ);
        } else {
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> {
                Column existingColumn = player.compensatedWorld.getChunk(chunkX, chunkZ);
                if (existingColumn == null) {
                    // Corrupting the player's empty chunk is actually quite meaningless
                    // You are able to set blocks inside it, and they do apply, it just always returns air despite what its data says
                    // So go ahead, corrupt the player's empty chunk and make it no longer all air, it doesn't matter
                    //
                    // LogUtil.warn("Invalid non-ground up continuous sent for empty chunk " + chunkX + " " + chunkZ + " for " + player.user.getProfile().getName() + "! This corrupts the player's empty chunk!");
                    return;
                }
                existingColumn.mergeChunks(sections);
            });
        }
    }

    protected void unloadChunk(PacketSendEvent event, PlayerData player, int x, int z) {
        if (player.movementSimulation.isUnified()) {
            player.compensatedWorld.trackServerColumn(event, x, z, null, true);
        } else {
            player.compensatedWorld.removeChunkLater(x, z);
        }
    }

    public void handleBlockChange(PlayerData player, PacketSendEvent event) {
        WrapperPlayServerBlockChange blockChange = new WrapperPlayServerBlockChange(event);
        int range = 16;

        Vector3i blockPosition = blockChange.getBlockPosition();
        if (player.movementSimulation.isUnified()) {
            player.compensatedWorld.trackServerBlocks(event,
                    java.util.List.of(new CompensatedWorld.SentBlock(blockPosition, blockChange.getBlockId())));
            return;
        }
        // Don't spam transactions (block changes are sent in batches)
        if (Math.abs(blockPosition.getX() - player.x) < range && Math.abs(blockPosition.getY() - player.y) < range && Math.abs(blockPosition.getZ() - player.z) < range &&
                player.lastTransSent + 2 < System.currentTimeMillis())
            player.sendTransaction();

        player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> player.compensatedWorld.updateBlock(blockPosition.getX(), blockPosition.getY(), blockPosition.getZ(), blockChange.getBlockId()));
    }

    public void handleMultiBlockChange(PlayerData player, PacketSendEvent event) {
        WrapperPlayServerMultiBlockChange multiBlockChange = new WrapperPlayServerMultiBlockChange(event);

        int range = 16;

        final var blocks = multiBlockChange.getBlocks();
        if (player.movementSimulation.isUnified()) {
            var changes = new java.util.ArrayList<CompensatedWorld.SentBlock>(blocks.length);
            for (var block : blocks) {
                changes.add(new CompensatedWorld.SentBlock(new Vector3i(block.getX(), block.getY(), block.getZ()), block.getBlockId()));
            }
            if (!changes.isEmpty()) player.compensatedWorld.trackServerBlocks(event, changes);
            return;
        }
        for (WrapperPlayServerMultiBlockChange.EncodedBlock blockChange : blocks) {
            // Don't send a transaction unless it's within 16 blocks of the player
            if (Math.abs(blockChange.getX() - player.x) < range && Math.abs(blockChange.getY() - player.y) < range && Math.abs(blockChange.getZ() - player.z) < range && player.lastTransSent + 2 < System.currentTimeMillis()) {
                player.sendTransaction();
                break;
            }
        }

        // Add a single runnable to prevent excessive memory use when there are lots of block changes
        player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> {
            for (WrapperPlayServerMultiBlockChange.EncodedBlock blockChange : blocks) {
                player.compensatedWorld.updateBlock(blockChange.getX(), blockChange.getY(), blockChange.getZ(), blockChange.getBlockId());
            }
        });
    }
}
