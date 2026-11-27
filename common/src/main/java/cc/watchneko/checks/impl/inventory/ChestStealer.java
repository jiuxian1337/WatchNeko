package cc.watchneko.checks.impl.inventory;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.protocol.player.GameMode;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@CheckData(name = "ChestStealer", description = "Repeated rapid container transfers and closes",
        experimental = true, requiresExperimentalChecks = false, requiresExactPunishmentMatch = true, setback = -1)
public final class ChestStealer extends Check {
    private volatile Settings settings;
    private volatile boolean observing;
    private Settings appliedSettings;
    private final Tracker tracker = new Tracker();
    private UUID world;
    private long lastTeleport;

    public ChestStealer(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        settings = new Settings(config.getBooleanElse("ChestStealer.enabled", true));
    }

    public boolean isConfiguredEnabled() {
        return settings.enabled();
    }

    public boolean isObserving() {
        return isConfiguredEnabled() && observing;
    }

    public void onClientClick(long sequence, Click click, long now) {
        if (!updateContext()) return;
        tracker.click(sequence, click, now);
        observing = tracker.isObserving();
    }

    public void onClientClose(long sequence, long now) {
        if (!updateContext()) return;
        tracker.clientClose(sequence, now);
        observing = tracker.isObserving();
    }

    public void onServerClick(UUID eventWorld, Click click, Outcome outcome, long now) {
        if (!updateContext() || !world.equals(eventWorld)) return;
        tracker.serverClick(click, outcome, now);
        observing = tracker.isObserving();
    }

    public void onServerClose(UUID eventWorld, long now) {
        if (!updateContext() || !world.equals(eventWorld)) return;
        Match match = tracker.serverClose(now);
        observing = tracker.isObserving();
        if (match != null) {
            flagAndAlert(String.format(Locale.ROOT, "slots=%d, mean=%.1fms, deviation=%.1fms, close=%.1fms",
                    match.slots(), match.meanMillis(), match.deviationMillis(), match.closeMillis()));
        }
    }

    private boolean updateContext() {
        Settings current = settings;
        UUID currentWorld = player.getWorldUID();
        if (!current.enabled() || !isEnabled() || isExemptPermission()
                || player.disableGrim || currentWorld == null
                || (player.gamemode != GameMode.SURVIVAL && player.gamemode != GameMode.ADVENTURE)) {
            tracker.reset();
            observing = false;
            return false;
        }
        if (current != appliedSettings || !currentWorld.equals(world) || lastTeleport != player.lastServerTeleport) {
            tracker.reset();
            observing = false;
            appliedSettings = current;
            world = currentWorld;
            lastTeleport = player.lastServerTeleport;
        }
        return true;
    }

    public record Click(int slot, int button, boolean quickMove) {}

    public enum Outcome { TRANSFERRED, EMPTY, OTHER }

    private record Settings(boolean enabled) {}

    record Match(int slots, double meanMillis, double deviationMillis, double closeMillis) {}

    private record PendingClick(Click click, long nanos) {}

    static final class Tracker {
        private static final int MIN_DISTINCT_SLOTS = 5;
        private static final int MAX_SLOTS = 54;
        private static final int MAX_CLICKS = 64;
        private static final int REQUIRED_WINDOWS = 3;
        private static final long MAX_WINDOW_AGE = TimeUnit.SECONDS.toNanos(10);
        private static final long MAX_EVENT_DELAY = TimeUnit.SECONDS.toNanos(2);
        private static final long MAX_EVIDENCE_AGE = TimeUnit.SECONDS.toNanos(45);
        private static final double NANOS_PER_MILLI = TimeUnit.MILLISECONDS.toNanos(1);
        private Window window;
        private int suspiciousWindows;
        private long firstSuspiciousClose;

        boolean isObserving() {
            return window != null && !window.invalid;
        }

        void click(long sequence, Click click, long now) {
            if (sequence <= 0) {
                reset();
                return;
            }
            if (window == null || sequence != window.sequence) window = new Window(sequence, now);
            if (window.invalid) return;
            if (click == null || click.slot() < 0 || click.slot() >= MAX_SLOTS || window.closed
                    || now - window.startedAt > MAX_WINDOW_AGE || ++window.clicks > MAX_CLICKS) {
                invalidateWindow();
                return;
            }
            window.pending.addLast(new PendingClick(click, now));
        }

        void clientClose(long sequence, long now) {
            if (window == null) return;
            if (sequence != window.sequence || window.closed || now - window.startedAt > MAX_WINDOW_AGE) {
                reset();
                return;
            }
            window.closed = true;
            window.closedAt = now;
        }

        void serverClick(Click click, Outcome outcome, long now) {
            if (!isObserving() || now - window.startedAt < 0) return;
            PendingClick next = window.pending.pollFirst();
            if (next == null || !next.click().equals(click) || now - next.nanos() < 0
                    || now - next.nanos() > MAX_EVENT_DELAY || outcome == Outcome.OTHER) {
                invalidateWindow();
                return;
            }
            if (outcome == Outcome.EMPTY || window.slots.get(click.slot())) {
                window.misses++;
                return;
            }
            if (!window.slots.isEmpty()) {
                double gapMillis = (next.nanos() - window.lastTransfer) / NANOS_PER_MILLI;
                // Batched inputs can make ordinary clicks look artificially short and regular.
                if (gapMillis < 8 || gapMillis > 250) {
                    invalidateWindow();
                    return;
                }
                window.sumMillis += gapMillis;
                window.sumSquaredMillis += gapMillis * gapMillis;
            }
            window.slots.set(click.slot());
            window.lastTransfer = next.nanos();
        }

        Match serverClose(long now) {
            if (window == null || now - window.startedAt < 0) return null;
            Window completed = window;
            window = null;
            Match match = completed.match(now);
            if (match == null) {
                suspiciousWindows = 0;
                return null;
            }
            if (suspiciousWindows == 0 || now - firstSuspiciousClose > MAX_EVIDENCE_AGE) {
                suspiciousWindows = 0;
                firstSuspiciousClose = now;
            }
            if (++suspiciousWindows < REQUIRED_WINDOWS) return null;
            suspiciousWindows = 0;
            return match;
        }

        private void invalidateWindow() {
            window.invalid = true;
            window.pending.clear();
            suspiciousWindows = 0;
        }

        void reset() {
            window = null;
            suspiciousWindows = 0;
        }

        private static final class Window {
            private final long sequence;
            private final long startedAt;
            private final ArrayDeque<PendingClick> pending = new ArrayDeque<>();
            private final BitSet slots = new BitSet(MAX_SLOTS);
            private long lastTransfer;
            private long closedAt;
            private boolean closed;
            private boolean invalid;
            private int clicks;
            private int misses;
            private double sumMillis;
            private double sumSquaredMillis;

            private Window(long sequence, long startedAt) {
                this.sequence = sequence;
                this.startedAt = startedAt;
            }

            private Match match(long now) {
                int distinctSlots = slots.cardinality();
                if (invalid || !closed || !pending.isEmpty() || now - closedAt < 0
                        || now - closedAt > MAX_EVENT_DELAY || distinctSlots < MIN_DISTINCT_SLOTS || misses > 1) return null;
                double closeMillis = (closedAt - lastTransfer) / NANOS_PER_MILLI;
                double mean = sumMillis / (distinctSlots - 1);
                double deviation = Math.sqrt(Math.max(0, sumSquaredMillis / (distinctSlots - 1) - mean * mean));
                if (closeMillis < 0 || closeMillis > 125 || mean > 105 || deviation > mean * 0.14) return null;
                return new Match(distinctSlots, mean, deviation, closeMillis);
            }
        }
    }
}
