package cc.watchneko.checks.impl.aim;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.impl.aim.AimTrajectoryTracker.Reports;
import cc.watchneko.checks.impl.aim.AimTrajectoryTracker.TargetAngles;
import cc.watchneko.checks.impl.aim.processor.AimProcessor;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.LogUtil;
import cc.watchneko.utils.anticheat.update.AttackAnchor;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import cc.watchneko.utils.math.MathUtil;
import cc.watchneko.utils.math.Vector3dm;
import cc.watchneko.utils.nmsutil.GetBoundingBox;
import cc.watchneko.utils.nmsutil.ReachUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AimTrajectorySampler {
    private static final double MAX_QUANTUM = 0.6144;
    private static final double LEGACY_COLLISION_BORDER = 0.1;

    private final PlayerData player;
    private final AimY periodicity;
    private final AimZ braking;
    private final AimTrajectoryTracker tracker = new AimTrajectoryTracker();
    private final List<SimpleCollisionBox> movementBoxes = new ArrayList<>(8);
    private int targetId;
    private PacketEntity target;
    private UUID world;
    private long lastTeleport;
    private int lastRespawnTick;
    private double lastEyeHeight = Double.NaN;
    private Settings periodSettings;
    private Settings brakeSettings;
    private boolean checkPeriod;
    private boolean checkBraking;

    public AimTrajectorySampler(PlayerData player, AimY periodicity, AimZ braking) {
        this.player = player;
        this.periodicity = periodicity;
        this.braking = braking;
        player.rotationSequence.addListener(tracker);
    }

    public void onUncertainTick() {
        if (!checkPeriod && !checkBraking) return;
        tracker.endSegment();
        if (checkPeriod && periodSettings.debugLog()) log("AimY", "uncertain-tick");
        if (checkBraking && brakeSettings.debugLog()) log("AimZ", "uncertain-tick");
    }

    public void onPacketReceive(PacketReceiveEvent event) {
        boolean movement = WrapperPlayClientPlayerFlying.isFlying(event.getPacketType());
        boolean interaction = event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY;
        if (!movement && !interaction) return;

        AttackAnchor attack = tracker.finishAttack(!interaction || event.isCancelled());
        if (!prepare()) return;
        if (interaction) {
            if (attack != null) acceptAttack(attack.entityId());
            return;
        }

        if (event.isCancelled() || player.packetStateData.lastPacketWasOnePointSeventeenDuplicate
                || player.packetStateData.cancelDuplicatePacket) {
            onUncertainTick();
            return;
        }
        if (target == null) return;
        if (!tracker.hasTargetForNextTick() || player.compensatedEntities.getEntity(targetId) != target || !validTarget(target)) {
            target = null;
            tracker.clearTarget();
            return;
        }

        double eyeHeight = player.getEyeHeight();
        if (Double.isFinite(lastEyeHeight) && eyeHeight != lastEyeHeight) tracker.endSegment();
        lastEyeHeight = eyeHeight;
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            onUncertainTick();
            return;
        }
        AimProcessor aim = player.checkManager.getRotationCheck(AimProcessor.class);
        double quantum = aim.modeY;
        if (!(quantum >= 0.0096 && quantum <= MAX_QUANTUM)) quantum = MAX_QUANTUM;
        Reports reports = tracker.recordFrame(yaw, pitch, projectTarget(eyeHeight, yaw, pitch), quantum,
                checkPeriod, checkBraking, System.nanoTime());
        report(reports);
    }

    private boolean prepare() {
        Settings currentPeriod = periodicity.settings();
        Settings currentBrake = braking.settings();
        boolean usePeriod = currentPeriod.enabled() && periodicity.isEnabled() && !periodicity.isExemptPermission();
        boolean useBrake = currentBrake.enabled() && braking.isEnabled() && !braking.isExemptPermission();
        if ((!usePeriod && !useBrake) || player.disableGrim || !player.isExperimentalChecks()
                || player.getClientVersion() != ClientVersion.V_1_8
                || PacketEvents.getAPI().getServerManager().getVersion().toClientVersion() != ClientVersion.V_1_8
                || player.gamemode != GameMode.SURVIVAL && player.gamemode != GameMode.ADVENTURE
                || player.inVehicle() || player.isFlying || player.isInBed || player.compensatedEntities.self.isDead
                || player.getSetbackTeleportUtil().shouldBlockMovement()) {
            deactivate();
            return false;
        }
        UUID currentWorld = player.getWorldUID();
        if (currentWorld == null) {
            deactivate();
            return false;
        }

        int respawnTick = player.getRespawnTick();
        boolean teleport = player.packetStateData.lastPacketWasTeleport;
        if (usePeriod != checkPeriod || useBrake != checkBraking || periodSettings != currentPeriod
                || brakeSettings != currentBrake || !currentWorld.equals(world)
                || lastTeleport != player.lastServerTeleport || respawnTick < lastRespawnTick || teleport) {
            reset();
        }
        checkPeriod = usePeriod;
        checkBraking = useBrake;
        periodSettings = currentPeriod;
        brakeSettings = currentBrake;
        world = currentWorld;
        lastTeleport = player.lastServerTeleport;
        lastRespawnTick = respawnTick;
        return !teleport;
    }

    private void deactivate() {
        if (checkPeriod || checkBraking) reset();
        checkPeriod = false;
        checkBraking = false;
    }

    private void reset() {
        tracker.reset();
        target = null;
        lastEyeHeight = Double.NaN;
    }

    private void acceptAttack(int entityId) {
        PacketEntity entity = player.compensatedEntities.getEntity(entityId);
        if (entityId == player.entityID || !validTarget(entity)) {
            if (target != null) tracker.clearTarget();
            target = null;
            return;
        }
        if (entity != target || entityId != targetId) {
            tracker.clearTarget();
            target = entity;
            targetId = entityId;
            lastEyeHeight = Double.NaN;
        }
        tracker.recordAttack();
    }

    private static boolean validTarget(PacketEntity entity) {
        return entity != null && entity.type == EntityTypes.PLAYER && entity.canHit() && !entity.isDead && entity.riding == null;
    }

    private TargetAngles projectTarget(double eyeHeight, float yaw, float pitch) {
        if (target.hasPendingMovement() || Math.abs(pitch) >= 89.5) return null;
        SimpleCollisionBox locations = target.getPossibleLocationBoxes();
        double dxMin = locations.minX - player.x;
        double dxMax = locations.maxX - player.x;
        double dzMin = locations.minZ - player.z;
        double dzMax = locations.maxZ - player.z;
        double eyeY = player.y + eyeHeight;
        double halfHeight = GetBoundingBox.getEntityDimensions(player, target)[1] / 2;
        double dyMin = locations.minY + halfHeight - eyeY;
        double dyMax = locations.maxY + halfHeight - eyeY;
        double centerX = (dxMin + dxMax) / 2;
        double centerZ = (dzMin + dzMax) / 2;
        double distance = Math.hypot(Math.hypot(centerX, centerZ), (dyMin + dyMax) / 2);
        if (!(distance >= 1.5 && distance <= 6)) return null;

        double nearestX = Math.max(0, Math.max(dxMin, -dxMax));
        double nearestZ = Math.max(0, Math.max(dzMin, -dzMax));
        double nearest = Math.hypot(nearestX, nearestZ);
        if (!(nearest > 0)) return null;
        double farthest = Math.hypot(Math.max(Math.abs(dxMin), Math.abs(dxMax)), Math.max(Math.abs(dzMin), Math.abs(dzMax)));
        double minPitch = Math.min(viewPitch(dyMax, nearest), viewPitch(dyMax, farthest));
        double maxPitch = Math.max(viewPitch(dyMin, nearest), viewPitch(dyMin, farthest));
        float centerYaw = viewYaw(centerX, centerZ);
        double first = MathUtil.getAngleDifference(viewYaw(dxMin, dzMin), centerYaw);
        double second = MathUtil.getAngleDifference(viewYaw(dxMin, dzMax), centerYaw);
        double third = MathUtil.getAngleDifference(viewYaw(dxMax, dzMin), centerYaw);
        double fourth = MathUtil.getAngleDifference(viewYaw(dxMax, dzMax), centerYaw);
        double minYaw = Math.min(Math.min(first, second), Math.min(third, fourth));
        double maxYaw = Math.max(Math.max(first, second), Math.max(third, fourth));

        Vector3dm eye = new Vector3dm(player.x, eyeY, player.z);
        Vector3dm look = ReachUtils.getLook(player, yaw, pitch);
        SimpleCollisionBox possible = target.getPossibleCollisionBoxes().expand(LEGACY_COLLISION_BORDER + player.getMovementThreshold());
        boolean couldHit = intersects(possible, eye, look, 6);
        boolean canReach = couldHit && couldReach(eye, look);
        return new TargetAngles(centerYaw, minYaw, maxYaw, minPitch, maxPitch, couldHit, canReach);
    }

    private boolean couldReach(Vector3dm eye, Vector3dm look) {
        movementBoxes.clear();
        if (!target.appendMovementBoxes(movementBoxes)) return false;
        double reach = player.compensatedEntities.self.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        for (SimpleCollisionBox box : movementBoxes) {
            if (intersects(box.expand(LEGACY_COLLISION_BORDER), eye, look, reach)) return true;
        }
        return false;
    }

    private static boolean intersects(SimpleCollisionBox box, Vector3dm eye, Vector3dm look, double range) {
        Vector3dm end = new Vector3dm(eye.getX() + look.getX() * range, eye.getY() + look.getY() * range,
                eye.getZ() + look.getZ() * range);
        return ReachUtils.isVecInside(box, eye) || ReachUtils.calculateIntercept(box, eye, end).first() != null;
    }

    private static float viewYaw(double x, double z) {
        return (float) (Math.toDegrees(Math.atan2(z, x)) - 90);
    }

    private static double viewPitch(double y, double horizontal) {
        return -Math.toDegrees(Math.atan2(y, horizontal));
    }

    private void report(Reports reports) {
        if (reports.period() != null && periodSettings.debugLog()) {
            log("AimY", reports.period().reason() + " " + reports.period().verbose());
        }
        boolean debugBraking = checkBraking && brakeSettings.debugLog();
        boolean debugCorroboration = checkPeriod && periodSettings.debugLog();
        if (reports.braking() != null && (debugBraking || debugCorroboration)) {
            String details = reports.braking().reason() + " " + reports.braking().verbose();
            if (debugBraking) log("AimZ", details);
            if (debugCorroboration) log("AimY", "braking " + details);
        }
        if (checkPeriod && reports.flagPeriod() != null) periodicity.flagAndAlert(reports.flagPeriod().verbose());
        if (checkBraking && reports.flagBraking() != null) braking.flagAndAlert(reports.flagBraking().verbose());
    }

    private void log(String check, String details) {
        LogUtil.info("[" + check + "] " + player.getName() + " " + details);
    }

    record Settings(boolean enabled, boolean debugLog) {
        static Settings read(ConfigManager config, String check) {
            return new Settings(config.getBooleanElse(check + ".enabled", false),
                    config.getBooleanElse(check + ".debug-log", false));
        }
    }
}
