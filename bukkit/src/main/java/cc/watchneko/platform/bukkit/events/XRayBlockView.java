package cc.watchneko.platform.bukkit.events;

import cc.watchneko.checks.impl.xray.MiningGeometry;
import cc.watchneko.checks.impl.xray.MiningSample;
import cc.watchneko.checks.impl.xray.XRaySettings;
import com.github.retrooper.packetevents.util.Vector3i;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/** A short-lived view used within a single region callback. Missing cells stay unknown. */
final class XRayBlockView {
    record MaterialState(String material, boolean occluding) {}

    record Cell(String material, String family, boolean occluding, XRayWorldHistory.Evidence evidence) {
        boolean air() {
            return material.equals("air") || material.equals("cave_air") || material.equals("void_air");
        }

        boolean water() {
            return material.equals("water") || material.equals("stationary_water");
        }

        boolean lava() {
            return material.equals("lava") || material.equals("stationary_lava");
        }

        boolean opening() {
            return air() || water() || lava();
        }
    }

    record EnclosedOre(String material, Map<Vector3i, String> neighbors) {}

    private final XRayWorldHistory history;
    private final long nanos;
    private final int minY;
    private final int maxY;
    private final Function<Vector3i, @Nullable MaterialState> reader;
    private final Map<Vector3i, @Nullable Cell> cells = new HashMap<>();
    private final Map<Long, Long> chunkEpochs = new HashMap<>();

    XRayBlockView(XRayWorldHistory history, long nanos, int minY, int maxY, Function<Vector3i, @Nullable MaterialState> reader) {
        this.history = history;
        this.nanos = nanos;
        this.minY = minY;
        this.maxY = maxY;
        this.reader = reader;
    }

    XRayWorldHistory history() {
        return history;
    }

    long nanos() {
        return nanos;
    }

    boolean inWorld(Vector3i position) {
        return position.y >= minY && position.y < maxY;
    }

    @Nullable Cell cell(Vector3i position) {
        if (!inWorld(position)) return null;
        Cell cached = cells.get(position);
        if (cached != null || cells.containsKey(position)) return cached;
        MaterialState state = reader.apply(position);
        if (state != null) {
            long key = XRayWorldHistory.chunkKey(position.x >> 4, position.z >> 4);
            chunkEpochs.computeIfAbsent(key, ignored -> history.chunkEpoch(key, nanos));
        }
        Cell result = state == null ? null : new Cell(state.material(), XRaySettings.oreFamily(state.material()),
                state.occluding(), history.observe(position, state.material(), nanos));
        cells.put(position, result);
        return result;
    }

    Map<Long, Long> chunkEpochs() {
        return Map.copyOf(chunkEpochs);
    }

    static boolean stillObserved(XRayWorldHistory history, Map<Long, Long> epochs, LongPredicate readable) {
        for (var chunk : epochs.entrySet()) {
            if (!history.matchesChunk(chunk.getKey(), chunk.getValue()) || !readable.test(chunk.getKey())) return false;
        }
        return true;
    }

    MiningSample.Environment environment(Vector3i ore, XRaySettings.Environment settings) {
        int air = 0;
        int water = 0;
        int lava = 0;
        int radius = settings.radius();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Vector3i position = MiningGeometry.offset(ore, x, y, z);
                    if (!inWorld(position)) continue;
                    Cell cell = cell(position);
                    if (cell == null || !cell.evidence.known()) return MiningSample.Environment.UNKNOWN;
                    if (cell.evidence.artificialAir()) continue;
                    if (cell.material.equals("cave_air")) air += settings.caveAirMultiplier();
                    else if (cell.air()) air++;
                    else if (cell.water()) water++;
                    else if (cell.lava()) lava++;
                }
            }
        }
        if (air >= settings.airCount() || water >= settings.waterCount() || lava >= settings.lavaCount()) {
            return MiningSample.Environment.OPEN;
        }
        return MiningSample.Environment.ENCLOSED;
    }

    @Nullable MiningSample.AirSample artificialAir(Vector3i center, int radius) {
        int artificial = 0;
        int total = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Vector3i position = MiningGeometry.offset(center, x, y, z);
                    if (!inWorld(position)) continue;
                    Cell cell = cell(position);
                    if (cell == null || !cell.evidence.known()) return null;
                    if (cell.air() && cell.evidence.artificialAir()) artificial++;
                    total++;
                }
            }
        }
        return new MiningSample.AirSample(artificial, total);
    }

    Map<Vector3i, EnclosedOre> enclosedNeighbors(Set<Vector3i> removed, Predicate<String> tracksOre) {
        Set<Vector3i> examined = new HashSet<>();
        Map<Vector3i, EnclosedOre> enclosed = new HashMap<>();
        for (Vector3i position : removed) {
            Cell source = cell(position);
            if (source == null || !source.occluding || !source.evidence.known()) continue;
            for (Vector3i neighbor : MiningGeometry.faces(position)) {
                if (removed.contains(neighbor) || !examined.add(neighbor)) continue;
                EnclosedOre ore = enclosed(neighbor, tracksOre);
                if (ore != null) enclosed.put(neighbor, ore);
            }
        }
        return enclosed;
    }

    private @Nullable EnclosedOre enclosed(Vector3i position, Predicate<String> tracksOre) {
        Cell ore = cell(position);
        if (ore == null || !ore.evidence.known() || ore.evidence.placed() || !tracksOre.test(ore.material())) return null;
        Map<Vector3i, String> neighbors = new HashMap<>();
        for (Vector3i neighbor : MiningGeometry.faces(position)) {
            Cell cover = cell(neighbor);
            if (cover == null || !cover.evidence.known() || !cover.occluding()) return null;
            neighbors.put(neighbor, cover.material());
        }
        return new EnclosedOre(ore.material(), Map.copyOf(neighbors));
    }

    @Nullable Vector3i confirmExposure(Vector3i position, EnclosedOre enclosed, Set<Vector3i> removed) {
        Cell ore = cell(position);
        if (ore == null || !ore.material.equals(enclosed.material) || !ore.evidence.known() || ore.evidence.placed()) return null;
        Vector3i opening = null;
        for (var neighbor : enclosed.neighbors.entrySet()) {
            Cell state = cell(neighbor.getKey());
            if (state == null) return null;
            if (removed.contains(neighbor.getKey()) && state.opening()) {
                opening = neighbor.getKey();
            } else if (!state.evidence.known() || !state.occluding() || !neighbor.getValue().equals(state.material())) {
                return null;
            }
        }
        return opening;
    }
}
