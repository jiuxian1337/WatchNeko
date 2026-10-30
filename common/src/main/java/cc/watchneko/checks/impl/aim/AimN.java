package cc.watchneko.checks.impl.aim;

import cc.watchneko.utils.aim.AimTargetTraceUtil;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;

@CheckData(
        name = "AimN",
        description = "consistent target-angle lock"
)
public final class AimN extends Check implements RotationCheck {
    private double lastAngle;
    private double lastAngleDiff;

    public AimN(PlayerData player) {
        super(player);
    }

    @Override
    public void process(RotationUpdate update) {
        if (!player.actionManager.hasAttackedSince(2000L)) {
            buffer = 0.0;
            return;
        }

        PacketEntity target = AimTargetTraceUtil.getPlayerTarget(player);
        if (target == null || target.type != EntityTypes.PLAYER) {
            return;
        }

        if (player.actionManager.hasAttackedSince(1000L) || player.getDeltaXZ() <= 0.1) {
            return;
        }

        float deltaPitch = update.getDeltaXRotABS();
        float deltaYaw = update.getDeltaYRotABS();
        double angle = AimTargetTraceUtil.angleToCenter(player, update.getTo().getYaw(), AimTargetTraceUtil.getTargetBox(target));
        double angleDiff = Math.abs(angle - lastAngle);
        double angleDiffDiff = Math.abs(angleDiff - lastAngleDiff);

        if (deltaYaw > 3.5F && angleDiff <= 0.075) {
            if (++buffer > 5.0 && flagAndAlert(
                    "* Aimlock\np= " + deltaPitch
                            + "\ny= " + deltaYaw
                            + "\nang= " + angle
                            + "\nad= " + angleDiff
                            + "\nadd= " + angleDiffDiff)) {
                player.mitigateDamage();
            }
        } else {
            buffer = Math.max(0.0, buffer - 0.15);
        }

        lastAngle = angle;
        lastAngleDiff = angleDiff;
    }
}
