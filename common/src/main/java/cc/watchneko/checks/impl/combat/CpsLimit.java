package cc.watchneko.checks.impl.combat;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.CombatPackets;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import cc.watchneko.utils.math.GrimMath;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@CheckData(name = "CPSLimit", description = "Optional PvP click rate damage rule", setback = -1)
public final class CpsLimit extends Check implements PacketCheck {
    private static final long COMBAT_GAP = TimeUnit.MILLISECONDS.toNanos(1_200);
    private final ClickRate rate = new ClickRate();
    private volatile Settings settings;
    private volatile Reduction reduction;
    private Settings appliedSettings;
    private long lastTeleport;
    private long lastAttack;
    private boolean inCombat;

    public CpsLimit(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        double multiplier = config.getDoubleElse("CPSLimit.damage-multiplier", 0.25);
        settings = new Settings(config.getBooleanElse("CPSLimit.enabled", true),
                GrimMath.clamp(config.getIntElse("CPSLimit.max-cps", 20), 1, 80),
                multiplier >= 0 && multiplier <= 1 ? multiplier : 0.25,
                TimeUnit.MILLISECONDS.toNanos(GrimMath.clamp(config.getIntElse("CPSLimit.duration-ms", 250), 50, 1000)));
        reduction = null;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Settings current = settings;
        if (!current.enabled() || player.disableGrim || isExemptPermission()
                || player.gamemode != GameMode.SURVIVAL && player.gamemode != GameMode.ADVENTURE
                || player.inVehicle() || player.packetStateData.lastPacketWasTeleport
                || player.getSetbackTeleportUtil().shouldBlockMovement()) {
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
                || type == PacketType.Play.Client.USE_ITEM || type == PacketType.Play.Client.CLICK_WINDOW
                || type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            endCombat();
            return;
        }
        int targetId = CombatPackets.attackTarget(event);
        if (targetId >= 0) {
            PacketEntity target = player.compensatedEntities.getEntity(targetId);
            if (target == null || target.type != EntityTypes.PLAYER || !target.canHit()) {
                endCombat();
                return;
            }
            long now = System.nanoTime();
            if (inCombat && now - lastAttack > COMBAT_GAP) endCombat();
            lastAttack = now;
            inCombat = true;
            if (rate.exceeds(now, current.maxCps())) extendReduction(current, now);
            return;
        }
        if (!inCombat || !CombatPackets.isMainHandSwing(event)) return;
        long now = System.nanoTime();
        if (now - lastAttack > COMBAT_GAP || player.packetStateData.isSlowedByUsingItem()) {
            endCombat();
            return;
        }

        if (rate.swing(now, current.maxCps())) extendReduction(current, now);
    }

    private void extendReduction(Settings current, long now) {
        UUID world = player.getWorldUID();
        if (world != null) reduction = new Reduction(current, world, now + current.durationNanos());
    }

    public double damageMultiplier(UUID world, long now) {
        Reduction active = reduction;
        if (active == null || active.settings() != settings || !world.equals(active.world())
                || now - active.expiresAt() >= 0 || player.disableGrim || isExemptPermission()) return 1;
        return active.settings().multiplier();
    }

    private void endCombat() {
        if (!inCombat) return;
        inCombat = false;
        reduction = null;
        rate.reset();
    }

    private record Settings(boolean enabled, int maxCps, double multiplier, long durationNanos) {}

    private record Reduction(Settings settings, UUID world, long expiresAt) {}

    static final class ClickRate {
        private static final int MAX_SAMPLES = 128;
        private static final long WINDOW = TimeUnit.SECONDS.toNanos(1);
        private static final long MIN_SPAN = TimeUnit.MILLISECONDS.toNanos(600);
        private final ArrayDeque<Long> clicks = new ArrayDeque<>();

        boolean swing(long now, int limit) {
            if (!clicks.isEmpty() && now - clicks.peekLast() < 0) reset();
            if (clicks.size() == MAX_SAMPLES) clicks.removeFirst();
            clicks.addLast(now);
            return exceeds(now, limit);
        }

        boolean exceeds(long now, int limit) {
            while (!clicks.isEmpty() && now - clicks.peekFirst() >= WINDOW) clicks.removeFirst();
            return clicks.size() > limit && now - clicks.peekFirst() >= MIN_SPAN;
        }

        void reset() {
            clicks.clear();
        }
    }
}
