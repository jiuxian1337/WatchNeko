package cc.watchneko.checks.impl.xray;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.checks.Check;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.util.Vector3i;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Associates server-confirmed block changes with accepted digging requests on the player's packet thread. */
public final class XRayProcessor {
    private static final long DIG_EXPIRY = TimeUnit.SECONDS.toNanos(10);
    private static final long CONFIRMATION_EXPIRY = TimeUnit.SECONDS.toNanos(3);
    private static final int MAX_PENDING = 128;

    private final PlayerData player;
    private final XRayA blast;
    private final XRayB path;
    private final XRayC smallVeins;
    private final Map<Vector3i, Long> digging = new LinkedHashMap<>();
    private final ArrayDeque<PendingBreak> pending = new ArrayDeque<>();
    private XRaySettings settings;
    private UUID world;
    private long lastTeleport;

    public XRayProcessor(PlayerData player, XRayA blast, XRayB path, XRayC smallVeins) {
        this.player = player;
        this.blast = blast;
        this.path = path;
        this.smallVeins = smallVeins;
    }

    public void onDig(XRaySettings generation, Vector3i position, DiggingAction action, boolean accepted, long now) {
        if (!updateContext() || settings != generation) return;
        drain(now);
        digging.remove(position);
        if (accepted && action != DiggingAction.CANCELLED_DIGGING) {
            if (digging.size() == MAX_PENDING) digging.remove(digging.keySet().iterator().next());
            digging.put(position, now);
        }
    }

    public void capture(PendingBreak change) {
        if (!updateContext() || settings != change.settings) return;
        if (world != null && !world.equals(change.world)) {
            reset();
        }
        world = change.world;
        drain(change.nanos);
        Long request = digging.remove(change.position);
        change.matched = request != null && change.nanos - request >= 0 && change.nanos - request <= DIG_EXPIRY;
        if (pending.size() == MAX_PENDING) {
            clearPending();
            interruptSampling();
        }
        change.queued = true;
        pending.addLast(change);
    }

    public void complete(PendingBreak change, @Nullable MiningSample sample, boolean uncertain) {
        if (!updateContext() || !change.queued || change.completed) return;
        change.sample = sample;
        change.uncertain = uncertain;
        change.completed = true;
        drain(System.nanoTime());
    }

    public void reset() {
        world = null;
        digging.clear();
        clearPending();
        blast.resetSampling();
        path.resetSampling();
        smallVeins.resetSampling();
    }

    private void clearPending() {
        for (PendingBreak change : pending) change.queued = false;
        pending.clear();
    }

    private void drain(long now) {
        while (!pending.isEmpty()) {
            PendingBreak change = pending.peekFirst();
            boolean expired = now - change.nanos >= CONFIRMATION_EXPIRY;
            if (!change.completed && !expired) return;
            pending.removeFirst();
            change.queued = false;
            if (expired || change.uncertain || (change.sample != null && !change.matched)) {
                interruptSampling();
                continue;
            }
            if (change.sample == null) continue;
            if (enabled(blast, settings.blast().targets())) {
                blast.observe(settings.blast(), world, change.position, change.nanos, change.sample);
            }
            if (enabled(path, settings.path().targets())) {
                path.observe(settings.path(), world, change.position, change.nanos, change.sample);
            }
            if (enabled(smallVeins, settings.smallVeins().targets())) {
                smallVeins.observe(settings.smallVeins(), world, change.position, change.nanos, change.sample);
            }
        }
    }

    private void interruptSampling() {
        path.interruptPath();
        smallVeins.interruptStreak();
    }

    private boolean updateContext() {
        XRaySettings current = WatchNekoAPI.INSTANCE.getConfigManager().getXRaySettings();
        if (settings != current || lastTeleport != player.lastServerTeleport) {
            reset();
            settings = current;
            lastTeleport = player.lastServerTeleport;
        }
        if (current != null && !player.disableGrim
                && (player.gamemode == GameMode.SURVIVAL || player.gamemode == GameMode.ADVENTURE)
                && (enabled(blast, current.blast().targets()) || enabled(path, current.path().targets())
                || enabled(smallVeins, current.smallVeins().targets()))) {
            return true;
        }
        if (world != null || !digging.isEmpty()) reset();
        return false;
    }

    private static boolean enabled(Check check, XRaySettings.Targets targets) {
        return targets.enabled() && check.isEnabled() && !check.isExemptPermission();
    }

    public static final class PendingBreak {
        private final XRaySettings settings;
        private final UUID world;
        private final Vector3i position;
        private final long nanos;
        private boolean queued;
        private boolean matched;
        private boolean completed;
        private boolean uncertain;
        private MiningSample sample;

        public PendingBreak(XRaySettings settings, UUID world, Vector3i position, long nanos) {
            this.settings = settings;
            this.world = world;
            this.position = position;
            this.nanos = nanos;
        }
    }
}
