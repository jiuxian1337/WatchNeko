/*
 * Inspired by TotemGuard's AutoTotemA and AutoTotemB checks.
 * Copyright (C) 2026 Bram and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 * https://github.com/Bram1903/TotemGuard/tree/48655b552acd33db01b0f60056fa2e96abacc5a5
 */
package cc.watchneko.checks.impl.inventory;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@CheckData(name = "AutoTotem", description = "Repeated totem replenishment timing", experimental = true, setback = -1)
public final class AutoTotem extends Check implements PacketCheck {
    private static final long NEARBY_PACKET_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
    private volatile Settings settings;
    private volatile boolean observing;
    private Settings appliedSettings;
    private Tracker tracker;
    private UUID world;
    private long lastTeleport;
    private long lastPacketTime;
    private PacketTypeCommon lastPacketType;
    private boolean hasTotemStatus;
    private long lastTotemStatus;

    public AutoTotem(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        int sampleSize = Math.max(5, Math.min(40, config.getIntElse("AutoTotem.sample-size", 8)));
        int rapidPairs = Math.max(2, Math.min(sampleSize, config.getIntElse("AutoTotem.rapid-pairs", 4)));
        double deviation = config.getDoubleElse("AutoTotem.max-deviation-ms", 15);
        if (!Double.isFinite(deviation)) deviation = 15;
        settings = new Settings(config.getBooleanElse("AutoTotem.enabled", true), sampleSize, rapidPairs,
                TimeUnit.MILLISECONDS.toNanos(Math.max(1, Math.min(50, config.getIntElse("AutoTotem.rapid-gap-ms", 5)))),
                TimeUnit.MILLISECONDS.toNanos(Math.max(250, Math.min(5000, config.getIntElse("AutoTotem.max-replenish-ms", 1500)))),
                Math.max(1, Math.min(100, deviation)));
    }

    public boolean isConfiguredEnabled() {
        return settings.enabled();
    }

    public boolean isObserving() {
        return observing;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!observing || !updateContext()) return;

        PacketTypeCommon type = event.getPacketType();
        long now = System.nanoTime();
        tracker.expire(now);
        observing = tracker.isActive();
        if (!observing) return;
        boolean grouped = lastPacketType != null && lastPacketType != PacketType.Play.Client.CLICK_WINDOW
                && now >= lastPacketTime && now - lastPacketTime <= NEARBY_PACKET_NANOS;
        lastPacketTime = now;
        lastPacketType = type;

        Input input;
        if (type == PacketType.Play.Client.CLICK_WINDOW) {
            var click = new WrapperPlayClientClickWindow(event);
            InputKind kind = InputKind.OTHER;
            int button = click.getButton();
            if (click.getWindowClickType() == WrapperPlayClientClickWindow.WindowClickType.PICKUP) {
                if (button == 0) kind = InputKind.LEFT;
                if (button == 1) kind = InputKind.RIGHT;
            } else if (click.getWindowClickType() == WrapperPlayClientClickWindow.WindowClickType.SWAP) {
                if (button == 40) kind = InputKind.OFFHAND_SWAP;
                else if (button >= 0 && button < 9) kind = InputKind.HOTBAR_SWAP;
            }
            input = new Input(kind, click.getWindowId(), click.getSlot(), button, now, grouped);
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING
                && new WrapperPlayClientPlayerDigging(event).getAction() == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
            input = new Input(InputKind.HAND_SWAP, -1, -1, -1, now, grouped);
        } else {
            return;
        }

        // PacketEvents runs post tasks after all packet listeners have had a chance to cancel the packet.
        event.getPostTasks().add(() -> {
            if (!updateContext()) return;
            if (event.isCancelled()) tracker.clearInteractions();
            else tracker.onInput(input);
            observing = tracker.isActive();
        });
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.RESPAWN) {
            event.getPostTasks().add(() -> {
                if (!event.isCancelled()) player.runSafely(this::reset);
            });
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_STATUS
                && settings.enabled() && isEnabled() && player.isExperimentalChecks()
                && !player.disableGrim && !isExemptPermission()) {
            var status = new WrapperPlayServerEntityStatus(event);
            if (status.getEntityId() != player.entityID || status.getStatus() != 35) return;
            long now = System.nanoTime();
            event.getPostTasks().add(() -> {
                if (!event.isCancelled()) player.runSafely(() -> onTotemStatus(now));
            });
        }
    }

    /** Called on the packet thread after an actual offhand totem resurrection. */
    public void onTotemPop(UUID eventWorld, long now) {
        if (!updateContext() || !world.equals(eventWorld)) return;
        tracker.onPop(now);
        if (hasTotemStatus) tracker.onStatus(lastTotemStatus);
        observing = true;
        lastPacketTime = 0;
        lastPacketType = null;
    }

    private void onTotemStatus(long now) {
        if (!updateContext()) return;
        hasTotemStatus = true;
        lastTotemStatus = now;
        tracker.onStatus(now);
    }

    /** Called on the packet thread with a Bukkit event captured on the player's region thread. */
    public void onServerAction(UUID eventWorld, InputKind kind, int slot, int button, Action action,
                               boolean cancelled, long token, long now) {
        if (!observing || !updateContext() || !world.equals(eventWorld)) return;
        tracker.onServerAction(kind, slot, button, action, cancelled, token, now);
        observing = tracker.isActive();
    }

    public void onInventoryBoundary(UUID eventWorld) {
        if (!observing || !updateContext() || !world.equals(eventWorld)) return;
        tracker.clearInteractions();
    }

    /** Confirms the server-side offhand after the inventory event has finished. */
    public void onVerified(UUID eventWorld, long token, boolean hasTotem, long now) {
        if (!observing || !updateContext() || !world.equals(eventWorld)) return;
        Result result = tracker.onVerified(token, hasTotem, now);
        observing = tracker.isActive();
        if (result != null) {
            flagAndAlert(String.format(Locale.ROOT,
                    "mode=%s, samples=%d, rapid=%d/%d, mean=%.1fms, std=%.1fms, pattern=%s",
                    result.mode(), result.samples(), result.rapid(), result.pairs(), result.meanMs(),
                    result.deviationMs(), result.pattern()));
        }
    }

    private boolean updateContext() {
        Settings current = settings;
        if (!current.enabled() || !isEnabled() || !player.isExperimentalChecks() || player.disableGrim
                || isExemptPermission() || player.getClientVersion().isOlderThan(ClientVersion.V_1_11)
                || player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR
                || player.packetStateData.lastPacketWasTeleport || player.getSetbackTeleportUtil().blockOffsets
                || !player.getSetbackTeleportUtil().pendingTeleports.isEmpty()) {
            reset();
            return false;
        }

        UUID currentWorld = player.getWorldUID();
        if (currentWorld == null) {
            reset();
            return false;
        }
        if (!currentWorld.equals(world) || lastTeleport != player.lastServerTeleport || appliedSettings != current) {
            reset();
            world = currentWorld;
            lastTeleport = player.lastServerTeleport;
            appliedSettings = current;
            tracker = new Tracker(current);
        }
        return true;
    }

    private void reset() {
        observing = false;
        world = null;
        tracker = null;
        lastPacketType = null;
        lastPacketTime = 0;
        hasTotemStatus = false;
    }

    public enum InputKind { LEFT, RIGHT, OFFHAND_SWAP, HOTBAR_SWAP, HAND_SWAP, OTHER }

    public enum Action { PICKUP, CURSOR_PLACE, INVENTORY_SWAP, HAND_SWAP, OTHER }

    enum Mode { CURSOR, INVENTORY_SWAP, HAND_SWAP }

    record Settings(boolean enabled, int sampleSize, int rapidPairs, long rapidGapNanos,
                    long maxReplenishNanos, double maxDeviationMs) {}

    record Input(InputKind kind, int window, int slot, int button, long nanos, boolean grouped) {}

    record Candidate(long token, Mode mode, long popNanos, long inputNanos, long eventNanos,
                     long clickGapNanos, boolean grouped) {}

    record Sample(long replenishNanos, long clickGapNanos) {}

    record Result(Mode mode, int samples, int rapid, int pairs, double meanMs, double deviationMs, String pattern) {}

    /** Packet and server events are correlated on the player's packet thread. */
    static final class Tracker {
        private static final int MAX_PENDING_INPUTS = 16;
        private static final long MAX_CONFIRMATION_NANOS = TimeUnit.SECONDS.toNanos(3);
        private static final long MAX_WINDOW_AGE_NANOS = TimeUnit.MINUTES.toNanos(15);
        private final Settings settings;
        private final ArrayDeque<Input> pending = new ArrayDeque<>();
        private final EnumMap<Mode, SampleWindow> windows = new EnumMap<>(Mode.class);
        private boolean active;
        private boolean statusSeen;
        private long popNanos;
        private long statusNanos;
        private Input pickup;
        private Candidate candidate;

        Tracker(Settings settings) {
            this.settings = settings;
            for (Mode mode : Mode.values()) windows.put(mode, new SampleWindow());
        }

        boolean isActive() {
            return active;
        }

        void onPop(long now) {
            clearSession();
            active = true;
            popNanos = now;
        }

        void onStatus(long now) {
            if (active && !statusSeen && now >= popNanos && now - popNanos <= settings.maxReplenishNanos()) {
                statusSeen = true;
                statusNanos = now;
            }
        }

        void expire(long now) {
            if (!active) return;
            if (candidate != null) {
                if (now < candidate.eventNanos() || now - candidate.eventNanos() > MAX_CONFIRMATION_NANOS) {
                    clearSession();
                }
            } else if (!pending.isEmpty()) {
                long first = pending.peekFirst().nanos();
                if (now < first || now - first > MAX_CONFIRMATION_NANOS) clearSession();
            } else if (now < (statusSeen ? statusNanos : popNanos)
                    || now - (statusSeen ? statusNanos : popNanos) > settings.maxReplenishNanos()) {
                clearSession();
            }
        }

        void onInput(Input input) {
            if (!active || !statusSeen || input.nanos() < statusNanos) return;
            if (input.nanos() - statusNanos > settings.maxReplenishNanos() || candidate != null) {
                clearSession();
                return;
            }
            if (input.kind() == InputKind.OTHER) {
                clearInteractions();
                return;
            }
            if (pending.size() >= MAX_PENDING_INPUTS) {
                clearSession();
                return;
            }
            pending.addLast(input);
        }

        void onServerAction(InputKind kind, int slot, int button, Action action,
                            boolean cancelled, long token, long eventNanos) {
            if (!active || eventNanos < popNanos) return;
            if (candidate != null || !statusSeen || eventNanos - statusNanos > MAX_CONFIRMATION_NANOS) {
                clearSession();
                return;
            }
            Input first = pending.peekFirst();
            if (first == null || !matches(first, kind, slot, button) || first.nanos() > eventNanos) {
                clearInteractions();
                return;
            }
            int matches = 0;
            for (Input input : pending) {
                if (input.nanos() <= eventNanos && matches(input, kind, slot, button)) matches++;
            }
            if (matches != 1) {
                clearInteractions();
                return;
            }
            pending.removeFirst();
            if (cancelled || action == Action.OTHER) {
                pickup = null;
                return;
            }
            if (action == Action.PICKUP && (kind == InputKind.LEFT || kind == InputKind.RIGHT)) {
                pickup = first;
                return;
            }

            Mode mode;
            long gap = -1;
            boolean grouped = first.grouped();
            if (action == Action.CURSOR_PLACE && (kind == InputKind.LEFT || kind == InputKind.RIGHT)) {
                mode = Mode.CURSOR;
                if (pickup != null && pickup.window() == first.window() && pickup.nanos() <= first.nanos()) {
                    gap = first.nanos() - pickup.nanos();
                    grouped |= pickup.grouped();
                }
            } else if (action == Action.INVENTORY_SWAP
                    && (kind == InputKind.OFFHAND_SWAP || kind == InputKind.HOTBAR_SWAP)) {
                mode = Mode.INVENTORY_SWAP;
            } else if (action == Action.HAND_SWAP && kind == InputKind.HAND_SWAP) {
                mode = Mode.HAND_SWAP;
            } else {
                pickup = null;
                return;
            }

            // A later click may change the offhand before the server-side verification runs.
            if (!pending.isEmpty()) {
                clearSession();
                return;
            }
            candidate = new Candidate(token, mode, statusNanos, first.nanos(), eventNanos, gap, grouped);
            pickup = null;
        }

        Result onVerified(long token, boolean hasTotem, long now) {
            Candidate current = candidate;
            if (!active || current == null || current.token() != token) return null;
            clearSession();
            long reaction = current.inputNanos() - current.popNanos();
            if (!hasTotem || current.grouped() || now < current.eventNanos()
                    || now - current.eventNanos() > MAX_CONFIRMATION_NANOS
                    || reaction < 0 || reaction > settings.maxReplenishNanos()) return null;
            return windows.get(current.mode()).add(current.mode(), new Sample(reaction, current.clickGapNanos()), now, settings);
        }

        void clearInteractions() {
            pending.clear();
            pickup = null;
        }

        private void clearSession() {
            active = false;
            statusSeen = false;
            clearInteractions();
            candidate = null;
        }

        private static boolean matches(Input input, InputKind kind, int slot, int button) {
            return input.kind() == kind && input.slot() == slot && input.button() == button;
        }

        private static final class SampleWindow {
            private final List<Sample> samples = new ArrayList<>();
            private long started;

            Result add(Mode mode, Sample sample, long now, Settings settings) {
                if (!samples.isEmpty() && (now < started || now - started > MAX_WINDOW_AGE_NANOS)) samples.clear();
                if (samples.isEmpty()) started = now;
                samples.add(sample);
                if (samples.size() < settings.sampleSize()) return null;

                double meanMs = samples.stream().mapToDouble(s -> s.replenishNanos() / 1_000_000.0).average().orElse(0);
                double squared = 0;
                int pairs = 0;
                int rapid = 0;
                for (Sample s : samples) {
                    double difference = s.replenishNanos() / 1_000_000.0 - meanMs;
                    squared += difference * difference;
                    if (s.clickGapNanos() >= 0) {
                        pairs++;
                        if (s.clickGapNanos() <= settings.rapidGapNanos()) rapid++;
                    }
                }
                double deviation = Math.sqrt(squared / samples.size());
                int count = samples.size();
                samples.clear();
                boolean fast = mode == Mode.CURSOR && rapid >= settings.rapidPairs();
                boolean consistent = deviation <= settings.maxDeviationMs();
                if (!fast && !consistent) return null;
                String pattern = fast && consistent ? "rapid+consistent" : fast ? "rapid" : "consistent";
                return new Result(mode, count, rapid, pairs, meanMs, deviation, pattern);
            }
        }
    }
}
