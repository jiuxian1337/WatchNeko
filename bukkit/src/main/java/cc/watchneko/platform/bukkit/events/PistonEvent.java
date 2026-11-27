package cc.watchneko.platform.bukkit.events;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.platform.bukkit.utils.convert.BukkitConversionUtils;
import cc.watchneko.platform.bukkit.world.BukkitPlatformWorld;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import cc.watchneko.utils.data.PistonData;
import cc.watchneko.utils.latency.CompensatedWorld;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PistonEvent implements Listener {

    private static final double MAX_HORIZONTAL_DISTANCE = 24.0;
    private static final double MAX_VERTICAL_DISTANCE = 64.0;
    private final Material SLIME_BLOCK = Material.getMaterial("SLIME_BLOCK");
    private final Material HONEY_BLOCK = Material.getMaterial("HONEY_BLOCK");

    // accuracy isn't that important, it's close enough and performant
    private static boolean isCloseEnough(Vector3i vectorA, Vector3d vectorB) {
        return Math.abs(vectorA.getX() - vectorB.getX()) <= MAX_HORIZONTAL_DISTANCE
                && Math.abs(vectorA.getY() - vectorB.getY()) <= MAX_VERTICAL_DISTANCE
                && Math.abs(vectorA.getZ() - vectorB.getZ()) <= MAX_HORIZONTAL_DISTANCE;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonPushEvent(BlockPistonExtendEvent event) {
        boolean hasSlimeBlock = false;
        boolean hasHoneyBlock = false;

        List<SimpleCollisionBox> boxes = new ArrayList<>();
        for (Block block : event.getBlocks()) {
            boxes.add(new SimpleCollisionBox(0, 0, 0, 1, 1, 1, true)
                    .offset(block.getX(),
                            block.getY(),
                            block.getZ()));
            boxes.add(new SimpleCollisionBox(0, 0, 0, 1, 1, 1, true)
                    .offset(block.getX() + event.getDirection().getModX(),
                            block.getY() + event.getDirection().getModY(),
                            block.getZ() + event.getDirection().getModZ()));

            // Support honey block like this because ViaVersion replacement
            if (block.getType() == SLIME_BLOCK) {
                hasSlimeBlock = true;
            }

            if (block.getType() == HONEY_BLOCK) {
                hasHoneyBlock = true;
            }
        }

        Block piston = event.getBlock();

        // Add bounding box of the actual piston head pushing
        boxes.add(new SimpleCollisionBox(0, 0, 0, 1, 1, 1, true)
                .offset(piston.getX() + event.getDirection().getModX(),
                        piston.getY() + event.getDirection().getModY(),
                        piston.getZ() + event.getDirection().getModZ()));

        final BlockFace blockFace = BukkitConversionUtils.fromBukkitFace(event.getDirection());
        recordMotion(piston, blockFace, true, event.getBlocks(), boxes, hasSlimeBlock, hasHoneyBlock);
    }

    // For some unknown reason, bukkit handles this stupidly
    // Calls the event once without blocks
    // Calls it again with blocks -
    // This wouldn't be an issue if it didn't flip the direction of the event
    // What a stupid system, again I can stand mojang doing stupid stuff but not other mod makers
    //
    // This gives too much of a lenience when retracting
    // But as this is insanely gitchy due to bukkit I don't care.
    // The lenience is never actually given because of collisions hitting the piston base
    // Blocks outside the piston head give only as much lenience as needed
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetractEvent(BlockPistonRetractEvent event) {
        boolean hasSlimeBlock = false;
        boolean hasHoneyBlock = false;

        List<SimpleCollisionBox> boxes = new ArrayList<>();
        BlockFace face = BukkitConversionUtils.fromBukkitFace(event.getDirection());

        // The event was called without blocks and is therefore in the right direction
        if (event.getBlocks().isEmpty()) {
            Block piston = event.getBlock();

            // Add bounding box of the actual piston head pushing
            boxes.add(new SimpleCollisionBox(0, 0, 0, 1, 1, 1, true)
                    .offset(piston.getX() + face.getModX(),
                            piston.getY() + face.getModY(),
                            piston.getZ() + face.getModZ()));
        }

        for (Block block : event.getBlocks()) {
            boxes.add(new SimpleCollisionBox(0, 0, 0, 1, 1, 1, true)
                    .offset(block.getX(), block.getY(), block.getZ()));
            boxes.add(new SimpleCollisionBox(0, 0, 0, 1, 1, 1, true)
                    .offset(block.getX() + face.getModX(), block.getY() + face.getModY(), block.getZ() + face.getModZ()));

            // Support honey block like this because ViaVersion replacement
            if (block.getType() == SLIME_BLOCK) {
                hasSlimeBlock = true;
            }

            if (block.getType() == HONEY_BLOCK) {
                hasHoneyBlock = true;
            }
        }

        recordMotion(event.getBlock(), face, false, event.getBlocks(), boxes, hasSlimeBlock, hasHoneyBlock);
    }

    private static void recordMotion(Block piston, BlockFace face, boolean extending, List<Block> sources,
                                     List<SimpleCollisionBox> boxes, boolean slime, boolean honey) {
        Vector3i base = new Vector3i(piston.getX(), piston.getY(), piston.getZ());
        UUID worldId = piston.getWorld().getUID();
        BukkitPlatformWorld world = new BukkitPlatformWorld(piston.getWorld());
        List<CompensatedWorld.SentBlock> moved = sources.stream().map(block -> {
            var pos = new Vector3i(block.getX(), block.getY(), block.getZ());
            return new CompensatedWorld.SentBlock(pos, world.getBlockAt(pos.x, pos.y, pos.z).getGlobalId());
        }).toList();
        for (PlayerData player : WatchNekoAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            int transaction = player.lastTransactionSent.get();
            player.latencyUtils.addRealTimeTaskAsync(transaction, () -> {
                if (player.platformPlayer == null || !worldId.equals(player.platformPlayer.getWorld().getUID())
                        || !player.compensatedWorld.isChunkLoaded(base.x >> 4, base.z >> 4)
                        || !isCloseEnough(base, player.compensatedEntities.self.trackedServerPosition.getPos())) return;
                player.compensatedWorld.activePistons.add(new PistonData(base, face, boxes, transaction, extending, slime, honey));
                if (player.movementSimulation.isUnified()) {
                    player.compensatedWorld.blockMotion.captureStructure(base, extending, moved);
                }
            });
        }
    }
}
