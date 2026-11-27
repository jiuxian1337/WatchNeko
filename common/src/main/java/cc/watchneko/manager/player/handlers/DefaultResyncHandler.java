package cc.watchneko.manager.player.handlers;

import ac.grim.grimac.api.handler.ResyncHandler;
import cc.watchneko.WatchNekoAPI;
import cc.watchneko.platform.api.world.PlatformChunk;
import cc.watchneko.platform.api.world.PlatformWorld;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAcknowledgeBlockChanges;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;

@RequiredArgsConstructor
public class DefaultResyncHandler implements ResyncHandler {

    private final PlayerData player;

    private static void resyncPositions(PlayerData player, int minBlockX, int mY, int minBlockZ, int maxBlockX, int mxY, int maxBlockZ) {
        if (player.movementSimulation.isUnified()) {
            player.runSafely(() -> resyncSentStates(player, minBlockX, mY, minBlockZ, maxBlockX, mxY, maxBlockZ));
            return;
        }
        // Check the 4 corners of the player world for loaded chunks before calling event
        if (!player.compensatedWorld.isChunkLoaded(minBlockX >> 4, minBlockZ >> 4) || !player.compensatedWorld.isChunkLoaded(minBlockX >> 4, maxBlockZ >> 4)
                || !player.compensatedWorld.isChunkLoaded(maxBlockX >> 4, minBlockZ >> 4) || !player.compensatedWorld.isChunkLoaded(maxBlockX >> 4, maxBlockZ >> 4))
            return;

        if (player.platformPlayer == null) return;
        // TODO this is not technically thread safe
        final PlatformWorld world = player.platformPlayer.getWorld();

        // Takes 0.15ms or so to complete. Not bad IMO. Unsure how I could improve this other than sending packets async.
        // But that's on PacketEvents.
        WatchNekoAPI.INSTANCE.getScheduler().getRegionScheduler().execute(WatchNekoAPI.INSTANCE.getGrimPlugin(), world,
                minBlockX >> 4, minBlockZ >> 4, () -> {
                    // Player hasn't spawned, don't spam packets
                    if (!player.getSetbackTeleportUtil().hasAcceptedSpawnTeleport) return;

                    // Check the 4 corners of the BB for loaded chunks, don't freeze main thread to load chunks.
                    if (!world.isChunkLoaded(minBlockX >> 4, minBlockZ >> 4) || !world.isChunkLoaded(minBlockX >> 4, maxBlockZ >> 4)
                            || !world.isChunkLoaded(maxBlockX >> 4, minBlockZ >> 4) || !world.isChunkLoaded(maxBlockX >> 4, maxBlockZ >> 4))
                        return;

                    // This is based on Tuinity's code, thanks leaf. Now merged into paper.
                    // I have no idea how I could possibly get this more efficient...
                    final int minSection = player.compensatedWorld.getMinHeight() >> 4;
                    final int minBlock = minSection << 4;
                    final int maxBlock = player.compensatedWorld.getMaxHeight() - 1;

                    int minBlockY = Math.max(minBlock, mY);
                    int maxBlockY = Math.min(maxBlock, mxY);

                    int minChunkX = minBlockX >> 4;
                    int maxChunkX = maxBlockX >> 4;

                    int minChunkY = minBlockY >> 4;
                    int maxChunkY = maxBlockY >> 4;

                    int minChunkZ = minBlockZ >> 4;
                    int maxChunkZ = maxBlockZ >> 4;

                    for (int currChunkZ = minChunkZ; currChunkZ <= maxChunkZ; ++currChunkZ) {
                        int minZ = currChunkZ == minChunkZ ? minBlockZ & 15 : 0; // coordinate in chunk
                        int maxZ = currChunkZ == maxChunkZ ? maxBlockZ & 15 : 15; // coordinate in chunk

                        for (int currChunkX = minChunkX; currChunkX <= maxChunkX; ++currChunkX) {
                            int minX = currChunkX == minChunkX ? minBlockX & 15 : 0; // coordinate in chunk
                            int maxX = currChunkX == maxChunkX ? maxBlockX & 15 : 15; // coordinate in chunk

                            PlatformChunk chunk = world.getChunkAt(currChunkX, currChunkZ);

                            for (int currChunkY = minChunkY; currChunkY <= maxChunkY; ++currChunkY) {
                                int minY = currChunkY == minChunkY ? minBlockY & 15 : 0; // coordinate in chunk
                                int maxY = currChunkY == maxChunkY ? maxBlockY & 15 : 15; // coordinate in chunk

                                int totalBlocks = (maxX - minX + 1) * (maxZ - minZ + 1) * (maxY - minY + 1);
                                WrapperPlayServerMultiBlockChange.EncodedBlock[] encodedBlocks = new WrapperPlayServerMultiBlockChange.EncodedBlock[totalBlocks];

                                int blockIndex = 0;
                                // Alright, we are now in a chunk section
                                // This can be used to construct and send a multi block change
                                for (int currZ = minZ; currZ <= maxZ; ++currZ) {
                                    for (int currX = minX; currX <= maxX; ++currX) {
                                        for (int currY = minY; currY <= maxY; ++currY) {
                                            int blockId = chunk.getBlockID(currX, currY | (currChunkY << 4), currZ);
                                            encodedBlocks[blockIndex++] = new WrapperPlayServerMultiBlockChange.EncodedBlock(blockId, currX, currY | (currChunkY << 4), currZ);
                                        }
                                    }
                                }

                                WrapperPlayServerMultiBlockChange packet = new WrapperPlayServerMultiBlockChange(new Vector3i(currChunkX, currChunkY, currChunkZ), true, encodedBlocks);
                                player.runSafely(() -> player.user.sendPacket(packet));
                            }
                        }
                    }
                });
    }

    // TODO (Cross-platform) make this use player.resyncHandler instead
    private static void resyncPosition(PlayerData player, int x, int y, int z, int sequence) {
        if (player.movementSimulation.isUnified()) {
            player.runSafely(() -> {
                if (!player.getSetbackTeleportUtil().hasAcceptedSpawnTeleport) return;
                int state = player.compensatedWorld.getServerSentBlockId(x, y, z);
                if (state < 0) return;
                player.user.sendPacket(new WrapperPlayServerBlockChange(new Vector3i(x, y, z), state));
                if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_19)) {
                    player.user.sendPacket(new WrapperPlayServerAcknowledgeBlockChanges(sequence));
                }
            });
            return;
        }
        if (player.platformPlayer == null) return;

        final int chunkX = x >> 4;
        final int chunkZ = z >> 4;
        if (!player.compensatedWorld.isChunkLoaded(chunkX, chunkZ)) return;

        // TODO this is not technically thread safe, but to trigger race condition requires
        // 0. Client to flag a Blockbreak check (to trigger calling this method)
        // 1. Get World (netty thread accessing main/region thread resource)
        // 2. main/region thread world changes
        // 3. Scheduler executes on wrong region thread (old world)
        // In other words they need to flag a blockbreak check at just the right moment while transitioning between worlds
        // In the future we should replace with completable-future for getting player world that runs on the region thread
        final PlatformWorld world = player.platformPlayer.getWorld();

        WatchNekoAPI.INSTANCE.getScheduler().getRegionScheduler().execute(WatchNekoAPI.INSTANCE.getGrimPlugin(), world, chunkX, chunkZ, () -> {
            if (!player.platformPlayer.isOnline() || !player.getSetbackTeleportUtil().hasAcceptedSpawnTeleport)
                return;
            if (player.platformPlayer.getPosition().distance(new Vector3d(x, y, z)) >= 64)
                return;
            if (!world.isChunkLoaded(chunkX, chunkZ)) return; // Don't load chunks sync

            final int blockId = world.getChunkAt(chunkX, chunkZ).getBlockID(x & 15, y, z & 15);

            player.user.sendPacket(new WrapperPlayServerBlockChange(new Vector3i(x, y, z), blockId));
            if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_19)) { // Via will handle this for us pre-1.19
                player.user.sendPacket(new WrapperPlayServerAcknowledgeBlockChanges(sequence)); // Make 1.19 clients apply the changes
            }
        });
    }

    private static void resyncSentStates(PlayerData player, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (!player.getSetbackTeleportUtil().hasAcceptedSpawnTeleport) return;
        minY = Math.max(minY, player.compensatedWorld.getMinHeight());
        maxY = Math.min(maxY, player.compensatedWorld.getMaxHeight() - 1);
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!player.compensatedWorld.isChunkLoaded(chunkX, chunkZ)) continue;
                for (int section = minY >> 4; section <= maxY >> 4; section++) {
                    var blocks = new ArrayList<WrapperPlayServerMultiBlockChange.EncodedBlock>();
                    for (int x = Math.max(minX, chunkX << 4); x <= Math.min(maxX, (chunkX << 4) + 15); x++) {
                        for (int z = Math.max(minZ, chunkZ << 4); z <= Math.min(maxZ, (chunkZ << 4) + 15); z++) {
                            for (int y = Math.max(minY, section << 4); y <= Math.min(maxY, (section << 4) + 15); y++) {
                                int state = player.compensatedWorld.getServerSentBlockId(x, y, z);
                                if (state >= 0) blocks.add(new WrapperPlayServerMultiBlockChange.EncodedBlock(state, x, y, z));
                            }
                        }
                    }
                    if (!blocks.isEmpty()) {
                        player.user.sendPacket(new WrapperPlayServerMultiBlockChange(new Vector3i(chunkX, section, chunkZ),
                                true, blocks.toArray(WrapperPlayServerMultiBlockChange.EncodedBlock[]::new)));
                    }
                }
            }
        }
    }

    @Override
    public void resyncPosition(int x, int y, int z, int sequence) {
        resyncPosition(player, x, y, z, sequence);
    }

    @Override
    public void resync(int minBlockX, int minBlockY, int minBlockZ, int maxBlockX, int maxBlockY, int maxBlockZ) {
        resyncPositions(player, minBlockX, minBlockY, minBlockZ, maxBlockX, maxBlockY, maxBlockZ);
    }
}
