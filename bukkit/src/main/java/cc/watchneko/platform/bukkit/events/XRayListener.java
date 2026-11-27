package cc.watchneko.platform.bukkit.events;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.checks.impl.xray.MiningGeometry;
import cc.watchneko.checks.impl.xray.MiningSample;
import cc.watchneko.checks.impl.xray.XRayProcessor;
import cc.watchneko.checks.impl.xray.XRaySettings;
import cc.watchneko.platform.api.Platform;
import cc.watchneko.platform.bukkit.world.BukkitPlatformWorld;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class XRayListener implements Listener {
    private static final int MAX_BLAST_BLOCKS = 2048;
    private final boolean folia = WatchNekoAPI.INSTANCE.getPlatform() == Platform.FOLIA;
    private final boolean supportsWorldMinHeight = PacketEvents.getAPI().getServerManager().getVersion()
            .isNewerThanOrEquals(ServerVersion.V_1_17);
    private final Map<UUID, XRayWorldHistory> worlds = new HashMap<>();
    private final AtomicLong blastIds = new AtomicLong();
    private XRaySettings generation;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.isAsynchronous()) return;
        Block block = event.getBlock();
        World world = block.getWorld();
        Context context = context(world);
        if (context == null) return;
        long now = System.nanoTime();
        Vector3i position = position(block);
        Player player = event.getPlayer();
        PlayerData data = player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE
                ? playerData(player) : null;
        var ticket = data == null ? null : new XRayProcessor.PendingBreak(context.settings, context.worldId, position, now);
        if (data != null) data.runSafely(() -> data.checkManager.getXRay().capture(ticket));

        XRayBlockView before = view(world, context, now);
        XRayBlockView.Cell source = before.cell(position);
        if (source == null || source.opening()) {
            deliver(data, ticket, null, true);
            return;
        }
        XRaySettings settings = context.settings;
        OreSnapshot ore = settings.tracksFamily(source.family()) ? captureOre(before, settings, position, source, player.getUniqueId(), now) : null;
        MiningSample.AirSample nearbyAir = data != null && settings.path().targets().enabled() && settings.path().fakeCave().enabled()
                ? before.artificialAir(position, settings.path().fakeCave().radius()) : null;
        Map<Vector3i, XRayBlockView.EnclosedOre> enclosed = settings.path().targets().enabled() && source.occluding()
                ? before.enclosedNeighbors(Set.of(position), settings.path().targets()::includes) : Map.of();
        XRayWorldHistory.Change change = context.history.begin(position, source.material(), now);
        if (change == null) {
            deliver(data, ticket, null, true);
            return;
        }
        Map<Long, Long> chunkEpochs = before.chunkEpochs();
        UUID miner = player.getUniqueId();
        nextTick(world, position, () -> {
            if (!current(context)) return;
            boolean sameChunks = sameChunks(world, context, chunkEpochs);
            XRayBlockView after = view(world, context, System.nanoTime());
            XRayBlockView.Cell result = after.cell(position);
            boolean removed = result != null && result.opening();
            MiningSample.Ore discovery;
            XRayWorldHistory.Outcome outcome;
            synchronized (context.history) {
                boolean seen = ore != null && (ore.previouslyMined || context.history.hasSeenVein(position, source.material()));
                outcome = context.history.finish(change, material(result), !event.isCancelled(), removed,
                        removed && result.air(), false, ore == null || source.evidence().placed() ? null : source.family(), now);
                discovery = outcome == XRayWorldHistory.Outcome.CONFIRMED && sameChunks && ore != null
                        ? recordDiscovery(context.history, ore, position, seen, now) : null;
            }
            if (outcome != XRayWorldHistory.Outcome.CONFIRMED) {
                deliver(data, ticket, null, outcome == XRayWorldHistory.Outcome.UNKNOWN);
                return;
            }
            if (!sameChunks || !source.evidence().known()) {
                deliver(data, ticket, null, true);
                return;
            }
            recordExposures(after, context.history, enclosed, Set.of(position), 0, miner, now);
            deliver(data, ticket, new MiningSample(source.material(), discovery, nearbyAir), false);
        });
    }

    private static OreSnapshot captureOre(XRayBlockView before, XRaySettings settings, Vector3i position,
                                          XRayBlockView.Cell source, UUID miner, long now) {
        boolean[] seen = {source.evidence().seenVein()};
        boolean[] placed = {source.evidence().placed()};
        MiningGeometry.Vein vein = MiningGeometry.scanVein(position, settings.smallVeins().distance(),
                settings.smallVeins().maxScanBlocks(), candidate -> {
                    if (!before.inWorld(candidate)) return MiningGeometry.OreCell.OTHER;
                    XRayBlockView.Cell cell = before.cell(candidate);
                    if (cell == null || !cell.evidence().known()) return MiningGeometry.OreCell.UNKNOWN;
                    if (source.family().equals(cell.evidence().removedFamily())) seen[0] = true;
                    if (!source.family().equals(cell.family())) return MiningGeometry.OreCell.OTHER;
                    seen[0] |= cell.evidence().seenVein();
                    placed[0] |= cell.evidence().placed();
                    return MiningGeometry.OreCell.ORE;
                });
        Map<Vector3i, String> members = new HashMap<>();
        for (Vector3i member : vein.members()) members.put(member, before.cell(member).material());
        boolean independent = vein.complete() && !placed[0] && !seen[0];
        var blast = MiningSample.Environment.UNKNOWN;
        var path = MiningSample.Environment.UNKNOWN;
        var small = MiningSample.Environment.UNKNOWN;
        if (independent) {
            var environment = new HashMap<XRaySettings.Environment, MiningSample.Environment>();
            blast = environment(before, settings.blast().targets(), position, source, environment);
            path = environment(before, settings.path().targets(), position, source, environment);
            small = environment(before, settings.smallVeins().targets(), position, source, environment);
        }
        BlastMatch match = blast == MiningSample.Environment.ENCLOSED ? blastMatch(before, position, vein, settings.blast(), now) : null;
        XRayWorldHistory.Exposure exposure = source.evidence().exposure();
        MiningSample.Reveal reveal = exposure != null && miner.equals(exposure.player())
                ? new MiningSample.Reveal(exposure.opening(), exposure.nanos()) : null;
        return new OreSnapshot(vein, members, seen[0], placed[0], blast, path, small, match, reveal);
    }

    private static MiningSample.Environment environment(XRayBlockView before, XRaySettings.Targets targets,
            Vector3i position, XRayBlockView.Cell ore, Map<XRaySettings.Environment, MiningSample.Environment> cache) {
        if (!targets.includes(ore.material())) return MiningSample.Environment.UNKNOWN;
        return cache.computeIfAbsent(targets.environment(), limits -> before.environment(position, limits));
    }

    static @Nullable BlastMatch blastMatch(XRayBlockView before, Vector3i position, MiningGeometry.Vein vein,
                                           XRaySettings.Blast config, long now) {
        if (!before.history().knowsBlastCoverage(now, config)) return null;
        long bestDistance = (long) config.matchRadius() * config.matchRadius();
        long bestBlast = 0;
        for (Vector3i member : vein.members()) {
            long distance = MiningGeometry.distanceSquared(position, member);
            if (distance > bestDistance) continue;
            XRayWorldHistory.Exposure exposure = before.cell(member).evidence().exposure();
            if (exposure == null || exposure.blastId() == 0 || now - exposure.nanos() < 0
                    || now - exposure.nanos() >= config.retentionNanos()) continue;
            if (distance < bestDistance || bestBlast == 0 || exposure.blastId() < bestBlast) {
                bestDistance = distance;
                bestBlast = exposure.blastId();
            }
        }
        return new BlastMatch(bestBlast, bestBlast != 0 && bestDistance == 0);
    }

    static MiningSample.Ore recordDiscovery(XRayWorldHistory history, OreSnapshot capture, Vector3i seed, boolean seen, long now) {
        if (!seen) {
            for (var member : capture.members.entrySet()) {
                if (history.hasSeenVein(member.getKey(), member.getValue())) {
                    seen = true;
                    break;
                }
            }
        }
        MiningSample.Discovery type = MiningSample.Discovery.UNKNOWN;
        if (seen) {
            type = MiningSample.Discovery.PREVIOUS;
        } else if (capture.vein.complete() && !capture.placed) {
            type = MiningSample.Discovery.FIRST;
            for (var member : capture.members.entrySet()) {
                if (!member.getKey().equals(seed)) history.markVein(member.getKey(), member.getValue(), now);
            }
        }
        BlastMatch blast = capture.match;
        int faceSize = capture.vein.complete() ? capture.vein.faceConnectedSize(seed) : -1;
        return new MiningSample.Ore(type, capture.vein.members().size(), faceSize, capture.blastEnvironment,
                capture.pathEnvironment, capture.smallEnvironment, blast != null, blast == null ? 0 : blast.id,
                blast != null && blast.exact, capture.reveal);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityBlast(EntityExplodeEvent event) {
        if (!event.isAsynchronous()) blast(event.getLocation().getWorld(), event.blockList(), event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBlast(BlockExplodeEvent event) {
        if (!event.isAsynchronous()) blast(event.getBlock().getWorld(), event.blockList(), event);
    }

    private void blast(World world, List<Block> blocks, Cancellable event) {
        if (blocks.isEmpty()) return;
        Context context = context(world);
        if (context == null) return;
        long now = System.nanoTime();
        if (blocks.size() > MAX_BLAST_BLOCKS) {
            context.history.omitBlast(now);
            return;
        }
        Set<Vector3i> affected = new HashSet<>();
        for (Block block : blocks) affected.add(position(block));
        XRayBlockView before = view(world, context, now);
        Map<Vector3i, XRayBlockView.EnclosedOre> enclosed = context.settings.blast().targets().enabled()
                ? before.enclosedNeighbors(affected, context.settings::tracksOre) : Map.of();
        List<BlastedBlock> changes = new ArrayList<>();
        for (Vector3i position : affected) {
            XRayBlockView.Cell cell = before.cell(position);
            if (cell == null || cell.opening()) continue;
            XRayWorldHistory.Change change = context.history.begin(position, cell.material(), now);
            if (change != null) {
                String family = context.settings.tracksFamily(cell.family()) && !cell.evidence().placed() ? cell.family() : null;
                changes.add(new BlastedBlock(change, family));
            }
        }
        if (changes.isEmpty()) return;
        Map<Long, Long> chunkEpochs = before.chunkEpochs();
        long blastId = blastIds.incrementAndGet();
        nextTick(world, changes.get(0).change.position(), () -> {
            if (!current(context)) return;
            XRayBlockView after = view(world, context, System.nanoTime());
            Set<Vector3i> removed = new HashSet<>();
            for (BlastedBlock block : changes) {
                Vector3i position = block.change.position();
                XRayBlockView.Cell result = after.cell(position);
                boolean opened = result != null && result.opening();
                var outcome = context.history.finish(block.change, material(result), !event.isCancelled(), opened,
                        opened && result.air(), false, block.family, now);
                if (outcome == XRayWorldHistory.Outcome.CONFIRMED) removed.add(position);
            }
            if (sameChunks(world, context, chunkEpochs)) {
                recordExposures(after, context.history, enclosed, removed, blastId, null, now);
            }
        });
    }

    static void recordExposures(XRayBlockView after, XRayWorldHistory history,
            Map<Vector3i, XRayBlockView.EnclosedOre> enclosed, Set<Vector3i> removed, long blastId, @Nullable UUID miner, long now) {
        if (removed.isEmpty()) return;
        for (var candidate : enclosed.entrySet()) {
            Vector3i opening = after.confirmExposure(candidate.getKey(), candidate.getValue(), removed);
            if (opening != null) {
                history.expose(candidate.getKey(), candidate.getValue().material(),
                        new XRayWorldHistory.Exposure(blastId, miner, opening, now));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.isAsynchronous() || !event.canBuild()) return;
        World world = event.getBlock().getWorld();
        Context context = context(world);
        if (context == null) return;
        XRayBlockView before = view(world, context, System.nanoTime());
        List<BlockState> replaced = event instanceof BlockMultiPlaceEvent multi
                ? multi.getReplacedBlockStates() : List.of(event.getBlockReplacedState());
        for (BlockState old : replaced) {
            Vector3i position = new Vector3i(old.getX(), old.getY(), old.getZ());
            XRayBlockView.Cell placed = before.cell(position);
            if (placed == null) continue;
            scheduleReplacement(world, context, position, XRaySettings.materialName(old.getType().name()),
                    placed.material(), before.nanos(), event, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!event.isAsynchronous()) move(event.getBlock().getWorld(), event.getBlocks(), event.getDirection(), event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!event.isAsynchronous()) move(event.getBlock().getWorld(), event.getBlocks(), event.getDirection(), event);
    }

    private void move(World world, List<Block> blocks, BlockFace direction, Cancellable event) {
        if (blocks.isEmpty()) return;
        Context context = context(world);
        if (context == null) return;
        XRayBlockView before = view(world, context, System.nanoTime());
        Map<Vector3i, String> sources = new HashMap<>();
        Map<Vector3i, String> destinations = new HashMap<>();
        for (Block block : blocks) {
            Vector3i source = position(block);
            XRayBlockView.Cell cell = before.cell(source);
            if (cell == null) continue;
            sources.put(source, cell.material());
            destinations.put(MiningGeometry.offset(source, direction.getModX(), direction.getModY(), direction.getModZ()), cell.material());
        }
        for (var source : sources.entrySet()) {
            if (!destinations.containsKey(source.getKey())) {
                scheduleReplacement(world, context, source.getKey(), source.getValue(), null, before.nanos(), event, 3);
            }
        }
        for (var destination : destinations.entrySet()) {
            XRayBlockView.Cell previous = before.cell(destination.getKey());
            if (previous == null) continue;
            scheduleReplacement(world, context, destination.getKey(), previous.material(), destination.getValue(),
                    before.nanos(), event, 3);
        }
    }

    private void scheduleReplacement(World world, Context context, Vector3i position, String previous,
                                     @Nullable String expected, long now, Cancellable event, int delay) {
        var change = context.history.begin(position, previous, now);
        if (change == null) return;
        WatchNekoAPI api = WatchNekoAPI.INSTANCE;
        api.getScheduler().getRegionScheduler().runDelayed(api.getGrimPlugin(), new BukkitPlatformWorld(world),
                position.x >> 4, position.z >> 4, () -> {
                    if (!current(context)) return;
                    XRayBlockView.Cell result = view(world, context, System.nanoTime()).cell(position);
                    boolean confirmed = result != null && (expected == null ? !previous.equals(result.material())
                            : expected.equals(result.material()));
                    boolean movedOrPlacedOre = confirmed && expected != null && context.settings.tracksFamily(result.family());
                    boolean opened = confirmed && expected == null && result.air();
                    String removed = confirmed && expected == null
                            && context.settings.tracksFamily(XRaySettings.oreFamily(previous)) ? XRaySettings.oreFamily(previous) : null;
                    var outcome = context.history.finish(change, material(result), !event.isCancelled(), confirmed,
                            opened, movedOrPlacedOre, removed, now);
                    if (outcome != XRayWorldHistory.Outcome.UNCHANGED) {
                        for (Vector3i neighbor : MiningGeometry.faces(position)) context.history.cover(neighbor);
                    }
                }, delay);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        resetPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        resetPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        resetPlayer(event.getEntity());
    }

    private void resetPlayer(Player player) {
        XRaySettings settings = WatchNekoAPI.INSTANCE.getConfigManager().getXRaySettings();
        if (settings == null || !settings.enabled()) return;
        PlayerData data = playerData(player);
        if (data != null) data.runSafely(() -> data.checkManager.getXRay().reset());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public synchronized void onChunkUnload(ChunkUnloadEvent event) {
        XRayWorldHistory history = worlds.get(event.getWorld().getUID());
        if (history != null) history.unloadChunk(event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public synchronized void onWorldUnload(WorldUnloadEvent event) {
        XRayWorldHistory history = worlds.remove(event.getWorld().getUID());
        if (history != null) history.close();
    }

    private synchronized @Nullable Context context(World world) {
        XRaySettings settings = WatchNekoAPI.INSTANCE.getConfigManager().getXRaySettings();
        if (generation != settings) {
            worlds.values().forEach(XRayWorldHistory::close);
            worlds.clear();
            generation = settings;
        }
        if (settings == null || !settings.enabled()) return null;
        UUID id = world.getUID();
        XRayWorldHistory history = worlds.computeIfAbsent(id, ignored -> new XRayWorldHistory(settings.historyNanos()));
        return new Context(settings, id, history);
    }

    private synchronized boolean current(Context context) {
        return context.settings == WatchNekoAPI.INSTANCE.getConfigManager().getXRaySettings()
                && worlds.get(context.worldId) == context.history;
    }

    private XRayBlockView view(World world, Context context, long now) {
        int min = supportsWorldMinHeight ? world.getMinHeight() : 0;
        return new XRayBlockView(context.history, now, min, world.getMaxHeight(), position -> readBlock(world, position, folia));
    }

    static @Nullable XRayBlockView.MaterialState readBlock(World world, Vector3i position, boolean folia) {
        if (!canReadChunk(world, position.x >> 4, position.z >> 4, folia)) return null;
        var material = world.getBlockAt(position.x, position.y, position.z).getType();
        return new XRayBlockView.MaterialState(XRaySettings.materialName(material.name()), material.isOccluding());
    }

    private boolean sameChunks(World world, Context context, Map<Long, Long> epochs) {
        return XRayBlockView.stillObserved(context.history, epochs,
                key -> canReadChunk(world, (int) (key >> 32), (int) key, folia));
    }

    private static boolean canReadChunk(World world, int x, int z, boolean folia) {
        return (!folia || Bukkit.isOwnedByCurrentRegion(world, x, z)) && world.isChunkLoaded(x, z);
    }

    private static void nextTick(World world, Vector3i anchor, Runnable task) {
        WatchNekoAPI api = WatchNekoAPI.INSTANCE;
        api.getScheduler().getRegionScheduler().run(api.getGrimPlugin(), new BukkitPlatformWorld(world),
                anchor.x >> 4, anchor.z >> 4, task);
    }

    private static @Nullable PlayerData playerData(Player player) {
        var user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        return user == null ? null : WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
    }

    private static void deliver(@Nullable PlayerData data, @Nullable XRayProcessor.PendingBreak ticket,
                                @Nullable MiningSample sample, boolean uncertain) {
        if (data != null && ticket != null) data.runSafely(() -> {
            if (WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(data.user) == data) {
                data.checkManager.getXRay().complete(ticket, sample, uncertain);
            }
        });
    }

    private static Vector3i position(Block block) {
        return new Vector3i(block.getX(), block.getY(), block.getZ());
    }

    private static @Nullable String material(@Nullable XRayBlockView.Cell cell) {
        return cell == null ? null : cell.material();
    }

    private record Context(XRaySettings settings, UUID worldId, XRayWorldHistory history) {}

    private record BlastedBlock(XRayWorldHistory.Change change, @Nullable String family) {}

    record BlastMatch(long id, boolean exact) {}

    record OreSnapshot(MiningGeometry.Vein vein, Map<Vector3i, String> members, boolean previouslyMined, boolean placed,
                       MiningSample.Environment blastEnvironment, MiningSample.Environment pathEnvironment,
                       MiningSample.Environment smallEnvironment, @Nullable BlastMatch match, @Nullable MiningSample.Reveal reveal) {}
}
