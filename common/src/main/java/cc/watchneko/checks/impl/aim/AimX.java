package cc.watchneko.checks.impl.aim;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.aim.AimTargetTraceUtil;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import cc.watchneko.utils.math.MathUtil;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.GameMode;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@CheckData(name = "AimX", description = "Repeated proportional turns toward the compensated target",
        experimental = true, requiresExperimentalChecks = false, requiresExactPunishmentMatch = true, setback = -1)
public final class AimX extends Check implements RotationCheck {
    private static final double MAX_TARGET_SPREAD = Math.sin(Math.toRadians(1.5));
    private final Correlation correlation = new Correlation();
    private volatile Settings settings;
    private Settings appliedSettings;
    private PacketEntity lastTarget;
    private UUID world;
    private long lastTeleport;

    public AimX(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        settings = new Settings(config.getBooleanElse("AimX.enabled", true));
    }

    @Override
    public void process(RotationUpdate update) {
        Settings current = settings;
        if (!current.enabled() || !isEnabled() || isExemptPermission()
                || player.disableGrim || player.inVehicle() || player.isFlying || player.isGliding
                || player.gamemode != GameMode.SURVIVAL && player.gamemode != GameMode.ADVENTURE
                || player.packetStateData.lastPacketWasTeleport || player.packetStateData.horseInteractCausedForcedRotation
                || !player.actionManager.hasAttackedSince(1_000)) {
            lastTarget = null;
            correlation.reset();
            return;
        }

        UUID currentWorld = player.getWorldUID();
        PacketEntity target = AimTargetTraceUtil.getPlayerTarget(player);
        if (current != appliedSettings || currentWorld == null || !currentWorld.equals(world)
                || target != lastTarget || player.lastServerTeleport != lastTeleport) {
            correlation.reset();
            lastTarget = target;
            world = currentWorld;
            appliedSettings = current;
            lastTeleport = player.lastServerTeleport;
        }
        if (world == null || target == null || target.type != EntityTypes.PLAYER || target.riding != null
                || target.hasPendingMovement() || player.packetStateData.lastPacketWasOnePointSeventeenDuplicate
                || update.isCinematic() || update.isCinematic2() || !player.isTickingReliablyFor(3)) return;

        // Rotation callbacks run before applying this packet's position update. Use the previous
        // player position and the entity positions available at this point on the same connection.
        SimpleCollisionBox locations = target.getPossibleLocationBoxes();
        double distance = AimTargetTraceUtil.horizontalCenterDistance(player, locations);
        if (!hasNarrowTarget(locations, distance)) return;
        double targetYaw = AimTargetTraceUtil.directionToCenter(player, locations);
        double expected = MathUtil.getAngleDifference((float) targetYaw, update.getFrom().getYaw());
        double observed = MathUtil.getAngleDifference(update.getTo().getYaw(), update.getFrom().getYaw());
        Match match = correlation.sample(expected, observed, System.nanoTime());
        if (match != null) {
            flagAndAlert(String.format(Locale.ROOT, "r=%.3f, gain=%.2f, error=%.2fdeg",
                    match.correlation(), match.gain(), match.residual()));
        }
    }

    static boolean hasNarrowTarget(SimpleCollisionBox locations, double distance) {
        double radius = Math.hypot(locations.maxX - locations.minX, locations.maxZ - locations.minZ) / 2;
        return distance >= 1 && distance <= 6 && radius <= distance * MAX_TARGET_SPREAD;
    }

    private record Settings(boolean enabled) {}

    record Match(double correlation, double gain, double residual) {}

    static final class Correlation {
        private static final int WINDOW_SIZE = 40;
        private static final long MAX_GAP = TimeUnit.MILLISECONDS.toNanos(750);
        private static final long MAX_WINDOW_AGE = TimeUnit.SECONDS.toNanos(10);
        private int samples;
        private int positive;
        private int matchingWindows;
        private long startedAt;
        private long lastSample;
        private double expectedSum;
        private double observedSum;
        private double expectedSquares;
        private double observedSquares;
        private double products;

        Match sample(double expected, double observed, long now) {
            if (!Double.isFinite(expected) || !Double.isFinite(observed)) {
                reset();
                return null;
            }
            if (Math.abs(expected) < 1.5 || Math.abs(expected) > 35) return null;
            if ((samples != 0 || matchingWindows != 0) && (now - lastSample <= 0 || now - lastSample > MAX_GAP)
                    || samples != 0 && now - startedAt > MAX_WINDOW_AGE) reset();
            if (samples == 0) startedAt = now;
            lastSample = now;
            if (expected > 0) positive++;
            expectedSum += expected;
            observedSum += observed;
            expectedSquares += expected * expected;
            observedSquares += observed * observed;
            products += expected * observed;
            if (++samples < WINDOW_SIZE) return null;

            Match match = evaluate();
            clearSamples();
            if (match == null) {
                matchingWindows = 0;
            } else if (++matchingWindows >= 3) {
                matchingWindows = 0;
                return match;
            }
            return null;
        }

        private Match evaluate() {
            if (positive < WINDOW_SIZE / 5 || samples - positive < WINDOW_SIZE / 5) return null;
            double expectedVariance = expectedSquares - expectedSum * expectedSum / samples;
            double observedVariance = observedSquares - observedSum * observedSum / samples;
            if (expectedVariance < samples * 4 || observedVariance < samples) return null;
            double covariance = products - expectedSum * observedSum / samples;
            double pearson = Math.min(1, covariance / Math.sqrt(expectedVariance * observedVariance));
            double gain = covariance / expectedVariance;
            double meanSquaredError = (observedSquares - 2 * gain * products + gain * gain * expectedSquares) / samples;
            double residual = Math.sqrt(Math.max(0, meanSquaredError));
            if (pearson < 0.985 || gain < 0.45 || gain > 1.15 || residual > 0.85) return null;
            return new Match(pearson, gain, residual);
        }

        void reset() {
            clearSamples();
            matchingWindows = 0;
        }

        private void clearSamples() {
            samples = 0;
            positive = 0;
            expectedSum = 0;
            observedSum = 0;
            expectedSquares = 0;
            observedSquares = 0;
            products = 0;
        }
    }
}
