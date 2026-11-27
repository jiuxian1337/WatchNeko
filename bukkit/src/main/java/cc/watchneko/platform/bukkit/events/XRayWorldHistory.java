package cc.watchneko.platform.bukkit.events;

import cc.watchneko.checks.impl.xray.XRaySettings;
import com.github.retrooper.packetevents.util.Vector3i;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Copies event provenance by position. World reads belong to the calling region, outside this lock. */
final class XRayWorldHistory {
    private static final int MAX_POSITIONS_PER_CHUNK = 4096;
    private static final long CLEANUP_INTERVAL = TimeUnit.SECONDS.toNanos(20);

    static final Evidence EMPTY = new Evidence(true, false, false, false, null, null);
    static final Evidence UNKNOWN = new Evidence(false, false, false, false, null, null);

    record Exposure(long blastId, @Nullable UUID player, Vector3i opening, long nanos) {}

    record Evidence(boolean known, boolean artificialAir, boolean placed, boolean seenVein,
                    @Nullable String removedFamily, @Nullable Exposure exposure) {}

    enum Outcome {
        CONFIRMED, UNCHANGED, UNKNOWN
    }

    record Change(Vector3i position, String previous, boolean conflict) {}

    private final long retention;
    private final Map<Long, ChunkHistory> chunks = new HashMap<>();
    private long nextChunkEpoch;
    private boolean closed;
    private long omittedBlastAt;
    private boolean omittedBlast;

    XRayWorldHistory(long retention) {
        this.retention = retention;
    }

    synchronized @Nullable Change begin(Vector3i position, String previous, long now) {
        Entry entry = writable(position, now);
        if (entry == null) return null;
        Change change = new Change(position, previous, entry.pending != null);
        entry.pending = change;
        return change;
    }

    synchronized Outcome finish(Change change, @Nullable String current, boolean accepted, boolean confirmed,
                                boolean artificialAir, boolean placed, @Nullable String removedFamily, long now) {
        Entry entry = entry(change.position);
        if (entry == null || entry.pending != change) return Outcome.UNKNOWN;
        entry.pending = null;
        if (change.conflict || current == null) {
            entry.set(current, false, false, null, now, true);
            return Outcome.UNKNOWN;
        }
        if (!accepted || !confirmed) {
            if (change.previous.equals(current)) return Outcome.UNCHANGED;
            entry.set(current, false, false, null, now, true);
            return Outcome.UNKNOWN;
        }
        entry.set(current, artificialAir, placed, removedFamily, now, false);
        return Outcome.CONFIRMED;
    }

    synchronized Evidence observe(Vector3i position, String material, long now) {
        ChunkHistory chunk = chunks.get(chunkKey(position.x >> 4, position.z >> 4));
        if (closed || (chunk != null && chunk.overflow)) return UNKNOWN;
        Entry entry = chunk == null ? null : chunk.positions.get(position);
        if (entry == null) return EMPTY;
        entry.expire(now, retention);
        if (entry.pending != null || entry.uncertain || (entry.material != null && !entry.material.equals(material))) {
            return UNKNOWN;
        }
        return new Evidence(true, entry.artificialAir, entry.placed, entry.seenVein, entry.removedFamily, entry.exposure);
    }

    synchronized boolean hasSeenVein(Vector3i position, String material) {
        Entry entry = entry(position);
        return entry != null && entry.seenVein && material.equals(entry.material);
    }

    synchronized void markVein(Vector3i position, String material, long now) {
        Entry entry = writable(position, now);
        if (entry == null || entry.uncertain || (entry.material != null && !entry.material.equals(material))
                || (entry.pending != null && !entry.pending.previous.equals(material))) return;
        entry.material = material;
        entry.seenVein = true;
    }

    synchronized void expose(Vector3i position, String material, Exposure exposure) {
        Entry entry = writable(position, exposure.nanos);
        if (entry == null || entry.pending != null || entry.uncertain || entry.placed
                || (entry.material != null && !entry.material.equals(material))) return;
        entry.material = material;
        if (entry.exposure == null) entry.exposure = exposure;
    }

    synchronized void cover(Vector3i position) {
        Entry entry = entry(position);
        if (entry != null) entry.exposure = null;
    }

    synchronized void omitBlast(long now) {
        omittedBlast = true;
        omittedBlastAt = now;
    }

    synchronized boolean knowsBlastCoverage(long now, XRaySettings.Blast settings) {
        return !omittedBlast || now - omittedBlastAt >= settings.retentionNanos();
    }

    synchronized void unloadChunk(int x, int z) {
        chunks.remove(chunkKey(x, z));
    }

    synchronized long chunkEpoch(long key, long now) {
        return closed ? 0 : chunks.computeIfAbsent(key, ignored -> new ChunkHistory(++nextChunkEpoch, now)).epoch;
    }

    synchronized boolean matchesChunk(long key, long epoch) {
        ChunkHistory chunk = chunks.get(key);
        return chunk != null && chunk.epoch == epoch && !chunk.overflow;
    }

    synchronized void close() {
        closed = true;
        chunks.clear();
    }

    private @Nullable Entry entry(Vector3i position) {
        ChunkHistory chunk = chunks.get(chunkKey(position.x >> 4, position.z >> 4));
        return chunk == null ? null : chunk.positions.get(position);
    }

    private @Nullable Entry writable(Vector3i position, long now) {
        if (closed) return null;
        long key = chunkKey(position.x >> 4, position.z >> 4);
        ChunkHistory chunk = chunks.computeIfAbsent(key, ignored -> new ChunkHistory(++nextChunkEpoch, now));
        if (chunk.overflow) return null;
        Entry current = chunk.positions.get(position);
        if (now - chunk.lastCleanup >= CLEANUP_INTERVAL || (current == null && chunk.positions.size() == MAX_POSITIONS_PER_CHUNK)) {
            chunk.positions.values().removeIf(entry -> entry.expire(now, retention));
            chunk.lastCleanup = now;
            current = chunk.positions.get(position);
        }
        if (current != null) return current;
        if (chunk.positions.size() >= MAX_POSITIONS_PER_CHUNK) {
            chunk.overflow = true;
            return null;
        }
        Entry entry = new Entry();
        chunk.positions.put(position, entry);
        return entry;
    }

    static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static final class ChunkHistory {
        private final long epoch;
        private final Map<Vector3i, Entry> positions = new HashMap<>();
        private long lastCleanup;
        private boolean overflow;

        ChunkHistory(long epoch, long now) {
            this.epoch = epoch;
            lastCleanup = now;
        }
    }

    private static final class Entry {
        private String material;
        private Change pending;
        private boolean artificialAir;
        private boolean placed;
        private boolean seenVein;
        private boolean uncertain;
        private String removedFamily;
        private Exposure exposure;
        private long changedAt;

        void set(@Nullable String current, boolean artificialAir, boolean placed, @Nullable String removedFamily,
                 long now, boolean uncertain) {
            material = current;
            this.artificialAir = artificialAir;
            this.placed = placed;
            this.removedFamily = removedFamily;
            this.uncertain = uncertain;
            changedAt = now;
            seenVein = false;
            exposure = null;
        }

        boolean expire(long now, long retention) {
            if (now - changedAt >= retention) {
                artificialAir = false;
                uncertain = false;
                removedFamily = null;
            }
            if (exposure != null && now - exposure.nanos >= retention) exposure = null;
            boolean empty = pending == null && !artificialAir && !placed && !seenVein && !uncertain
                    && removedFamily == null && exposure == null;
            if (empty) material = null;
            return empty;
        }
    }
}
