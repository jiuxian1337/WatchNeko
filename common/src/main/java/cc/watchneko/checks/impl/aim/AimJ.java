package cc.watchneko.checks.impl.aim;

import cc.watchneko.utils.aim.AimTargetTraceUtil;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import cc.watchneko.utils.lists.EvictingList;
import cc.watchneko.utils.math.MathUtil;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;

import java.util.List;

@CheckData(
        name = "AimJ",
        description = "stable optimal-yaw deviation",
        experimental = true
)
public final class AimJ extends Check implements RotationCheck {
    private final List<Double> differenceSamples = new EvictingList<>(25);
    private final List<Float> yawSamples = new EvictingList<>(25);

    public AimJ(PlayerData player) {
        super(player);
    }

    @Override
    public void process(RotationUpdate update) {
        PacketEntity target = AimTargetTraceUtil.getPlayerTarget(player);
        if (target == null || target.type != EntityTypes.PLAYER || !player.actionManager.hasAttackedSince(300L)) {
            return;
        }

        final float deltaYaw = update.getDeltaYRotABS();
        final float rotationYaw = update.getTo().getYaw();
        final float fixedRotYaw = (rotationYaw % 360F + 360F) % 360F;
        final double optimalYaw = (AimTargetTraceUtil.directionToCenter(player, AimTargetTraceUtil.getTargetBox(target)) % 360.0 + 360.0) % 360.0;
        final double difference = Math.abs(MathUtil.getAngleDifference(fixedRotYaw, (float) optimalYaw));

        if (deltaYaw > 3f) {
            differenceSamples.add(difference);
            yawSamples.add(deltaYaw);
        }

        if (differenceSamples.size() == 25) {
            final double average = MathUtil.getAverageDouble(differenceSamples);
            final double deviation = MathUtil.getStandardDeviation(differenceSamples);
            final double averageYaw = MathUtil.getAverage(yawSamples);

            // Only treat this as suspicious when the player keeps fairly large yaw movement
            // while staying unnaturally tight and stable around the target angle.
            if (average > 0.2 && average < 3.5 && deviation < 2.4 && averageYaw > 6.0) {
                if (++buffer > 8 && flagAndAlert(String.format("dev=%.2f, avg=%.2f, yaw=%.2f, buf=%.2f", deviation, average, averageYaw, buffer))) {
                    player.mitigateDamage();
                }
            } else {
                buffer = Math.max(0.0, buffer - 1.25);
            }
        }
    }
}
