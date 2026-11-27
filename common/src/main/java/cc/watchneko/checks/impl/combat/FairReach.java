package cc.watchneko.checks.impl.combat;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.LogUtil;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import cc.watchneko.utils.data.TrackerData;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import cc.watchneko.utils.nmsutil.GetBoundingBox;
import cc.watchneko.utils.nmsutil.ReachUtils;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Optional PvP policy based on CultAC's FairReach from Grim 3.0. WatchNeko uses its existing
 * transaction markers and a bounded envelope of target positions for the capped view.
 */
@CheckData(name = "FairReach", description = "player hit beyond the configured PvP lag compensation")
public final class FairReach extends Check implements PacketCheck {
    private static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final Int2ObjectOpenHashMap<Target> targets = new Int2ObjectOpenHashMap<>();
    private Settings settings;
    private long lastReportNanos = System.nanoTime() - REPORT_INTERVAL_NANOS;

    public FairReach(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        boolean enabled = config.getBooleanElse("FairReach.enabled", true);
        boolean blockHits = config.getBooleanElse("FairReach.block-impossible-hits", false);
        int maxCompensation = Math.max(0, config.getIntElse("FairReach.max-ping-compensation-ms", 300));
        double leniency = config.getDoubleElse("FairReach.reach-leniency", 0.1);
        if (!Double.isFinite(leniency) || leniency < 0) leniency = 0.1;

        if (settings != null && (!enabled || maxCompensation != settings.maxCompensationMillis())) {
            targets.clear();
        }
        settings = new Settings(enabled, blockHits, maxCompensation, leniency);
    }

    @Override
    public boolean isEnabled() {
        return settings.enabled();
    }

    @Override
    public boolean shouldModifyPackets() {
        return isEnabled() && settings.blockHits() && !player.disableGrim && !isExemptPermission()
                && !player.noModifyPacketPermission && !isNoModifyPacketPermission();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        var type = event.getPacketType();
        if ((type != PacketType.Play.Client.INTERACT_ENTITY && type != PacketType.Play.Client.ATTACK)
                || !isEnabled() || event.isCancelled() || player.disableGrim || isExemptPermission()) return;
        if (player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR
                || player.inVehicle() || player.getSetbackTeleportUtil().shouldBlockMovement()) return;

        int entityId;
        if (type == PacketType.Play.Client.ATTACK) {
            entityId = new WrapperPlayClientAttack(event).getEntityId();
        } else {
            var interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return;
            entityId = interact.getEntityId();
        }

        Target target = targets.get(entityId);
        if (target == null || target.tracker != player.compensatedEntities.getTrackedEntity(entityId)) return;
        PacketEntity entity = player.compensatedEntities.getEntity(entityId);
        if (entity == null || entity.type != EntityTypes.PLAYER || entity.riding != null || !entity.canHit()) return;

        long now = System.nanoTime();
        var bounds = target.history.cappedBounds(now, player.lastTransactionReceived.get());
        if (bounds == null) return;

        double reach = player.compensatedEntities.self.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE) + settings.leniency();
        var cappedBox = new SimpleCollisionBox(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
        GetBoundingBox.expandBoundingBoxByEntityDimensions(cappedBox, player, entity);
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) {
            cappedBox.expand(0.03125);
        }

        double cappedDistance = ReachUtils.getMinReachToBox(player, cappedBox);
        if (!(cappedDistance > reach) || ReachUtils.getMinReachToBox(player, entity.getPossibleCollisionBoxes()) > reach) return;

        boolean blocked = shouldModifyPackets();
        if (blocked) {
            event.setCancelled(true);
        }
        // This is a match policy, so neither observing nor blocking contributes to cheat violations.
        if (now - lastReportNanos >= REPORT_INTERVAL_NANOS) {
            lastReportNanos = now;
            LogUtil.info(String.format(Locale.ROOT, "[FairReach] %s %s target=%d distance=%.3f limit=%.3f cap=%dms",
                    player.getName(), blocked ? "blocked" : "would block", entityId, cappedDistance, reach, settings.maxCompensationMillis()));
        }
    }

    public void trackPosition(PacketSendEvent event, int entityId, TrackerData tracker, int receipt, boolean relativeMove) {
        if (!isEnabled() || tracker.getEntityType() != EntityTypes.PLAYER || entityId == player.entityID) return;

        Target existing = targets.get(entityId);
        final Target target;
        if (existing == null || existing.tracker != tracker) {
            target = new Target(tracker, settings.maxCompensationMillis());
            targets.put(entityId, target);
        } else {
            target = existing;
        }

        boolean unresolved = hasRelativeTeleportPosition(event);
        double x = tracker.getX(), y = tracker.getY(), z = tracker.getZ();
        event.getTasksAfterSend().add(() -> {
            if (targets.get(entityId) != target) return;
            if (event.isCancelled() || unresolved) {
                target.history.clear();
                target.hasPosition = false;
            } else if (!relativeMove) {
                // Start a fresh history after a teleport or position sync.
                target.history.clear();
                target.hasPosition = true;
            } else if (target.hasPosition) {
                target.history.record(System.nanoTime(), receipt, x, y, z);
            }
        });
    }

    public void forgetTarget(int entityId) {
        targets.remove(entityId);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.RESPAWN) {
            targets.clear();
        }
    }

    private static boolean hasRelativeTeleportPosition(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_TELEPORT) return false;
        RelativeFlag flags = new WrapperPlayServerEntityTeleport(event).getRelativeFlags();
        return flags != null && (flags.has(RelativeFlag.X) || flags.has(RelativeFlag.Y) || flags.has(RelativeFlag.Z));
    }

    private record Settings(boolean enabled, boolean blockHits, int maxCompensationMillis, double leniency) {}

    private static final class Target {
        private final TrackerData tracker;
        private final FairReachHistory history;
        private boolean hasPosition = true;

        private Target(TrackerData tracker, int maxCompensationMillis) {
            this.tracker = tracker;
            this.history = new FairReachHistory(maxCompensationMillis);
        }
    }
}
