package cc.watchneko.checks.impl.autoclicker;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.CombatPackets;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

@CheckData(name = "AutoclickerC", description = "Repeated combat click interval differences",
        experimental = true, requiresExperimentalChecks = false, requiresExactPunishmentMatch = true, setback = -1)
public final class AutoclickerC extends Check implements PacketCheck {
    private static final long COMBAT_GAP = TimeUnit.MILLISECONDS.toNanos(1_500);
    private final Cadence cadence = new Cadence();
    private volatile Settings settings;
    private Settings appliedSettings;
    private long lastTeleport;
    private long lastAttack;
    private boolean inCombat;

    public AutoclickerC(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        settings = new Settings(config.getBooleanElse("AutoclickerC.enabled", true));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Settings current = settings;
        if (!current.enabled() || !isEnabled() || player.disableGrim || isExemptPermission()
                || player.inVehicle() || player.gamemode == GameMode.SPECTATOR || player.gamemode == GameMode.CREATIVE
                || player.packetStateData.lastPacketWasTeleport) {
            endCombat();
            return;
        }
        if (current != appliedSettings || lastTeleport != player.lastServerTeleport) {
            appliedSettings = current;
            lastTeleport = player.lastServerTeleport;
            endCombat();
        }
        if (event.isCancelled()) return;

        var type = event.getPacketType();
        if (type == PacketType.Play.Client.PLAYER_DIGGING || type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
                || type == PacketType.Play.Client.USE_ITEM || type == PacketType.Play.Client.HELD_ITEM_CHANGE
                || type == PacketType.Play.Client.CLICK_WINDOW) {
            endCombat();
            return;
        }
        int targetId = CombatPackets.attackTarget(event);
        if (targetId >= 0) {
            PacketEntity target = player.compensatedEntities.getEntity(targetId);
            if (target == null || !target.canHit()) {
                endCombat();
                return;
            }
            long now = System.nanoTime();
            if (inCombat && now - lastAttack > COMBAT_GAP) endCombat();
            lastAttack = now;
            inCombat = true;
            return;
        }
        if (!inCombat || !CombatPackets.isMainHandSwing(event)) return;
        long now = System.nanoTime();
        if (now - lastAttack > COMBAT_GAP || !player.isTickingReliablyFor(3)
                || player.packetStateData.isSlowedByUsingItem()) {
            endCombat();
            return;
        }
        Match match = cadence.click(now);
        if (match != null) {
            flagAndAlert(String.format(Locale.ROOT, "%s, mean=%.1fms, delta-sd=%.2fms",
                    match.pattern(), match.mean(), match.deltaDeviation()));
        }
    }

    private void endCombat() {
        if (!inCombat) return;
        cadence.reset();
        inCombat = false;
    }

    private record Settings(boolean enabled) {}

    record Match(String pattern, double mean, double deltaDeviation, double upperEdge) {}

    static final class Cadence {
        private final ClickIntervals intervals = new ClickIntervals(64, 12, 325);
        private int matchingWindows;
        private Match previousMatch;

        Match click(long now) {
            if (intervals.add(now) == 0) {
                matchingWindows = 0;
                previousMatch = null;
            }
            double[] window = intervals.completedWindow();
            if (window == null) return null;
            Match match = evaluate(window);
            if (match == null) {
                matchingWindows = 0;
                previousMatch = null;
                return null;
            }
            if (previousMatch == null || !match.pattern().equals(previousMatch.pattern())
                    || Math.abs(match.upperEdge() - previousMatch.upperEdge()) > 6) {
                matchingWindows = 0;
            }
            previousMatch = match;
            if (++matchingWindows < 3) return null;
            matchingWindows = 0;
            return match;
        }

        private static Match evaluate(double[] window) {
            int differenceCount = window.length - 1;
            double total = window[0];
            double maximum = window[0];
            double minimum = window[0];
            double deltaSum = 0;
            double deltaSquares = 0;
            for (int i = 1; i < window.length; i++) {
                double interval = window[i];
                double delta = interval - window[i - 1];
                total += interval;
                maximum = Math.max(maximum, interval);
                minimum = Math.min(minimum, interval);
                deltaSum += delta;
                deltaSquares += delta * delta;
            }
            double mean = total / window.length;
            if (mean > 145) return null;

            double deltaDeviation = ClickIntervals.deviation(deltaSum, deltaSquares, differenceCount);
            if (deltaDeviation < 4 && maximum - minimum <= 24) {
                return new Match("stable-differences", mean, deltaDeviation, 0);
            }

            // A repeated upper edge is supporting evidence only when the adjacent differences
            // are also symmetric. Each evaluation sees exactly the same window for both tests.
            if (maximum > 190 || maximum - minimum < 30 || deltaDeviation < 5) return null;
            double meanDifference = deltaSum / differenceCount;
            double cubedResiduals = 0;
            int nearUpperEdge = 0;
            for (int i = 0; i < window.length; i++) {
                if (maximum - window[i] <= 4) nearUpperEdge++;
                if (i == 0) continue;
                double residual = (window[i] - window[i - 1] - meanDifference) / deltaDeviation;
                cubedResiduals += residual * residual * residual;
            }
            double skewness = cubedResiduals / differenceCount;
            if (nearUpperEdge < window.length / 4 || Math.abs(skewness) > 0.18) return null;
            return new Match("repeat-upper-edge", mean, deltaDeviation, maximum);
        }

        void reset() {
            intervals.reset();
            matchingWindows = 0;
            previousMatch = null;
        }
    }
}
