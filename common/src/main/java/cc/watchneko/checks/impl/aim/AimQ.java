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
import cc.watchneko.utils.math.Vec2f;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;

import java.util.List;
import java.util.stream.Collectors;

@CheckData(
        name = "AimQ",
        description = "offset target rotation matching"
)
public final class AimQ extends Check implements RotationCheck {
    private final List<Float> pitchMatches = new EvictingList<>(150);
    private final List<Float> yawMatches = new EvictingList<>(150);

    public AimQ(PlayerData player) {
        super(player);
    }

    @Override
    public void process(RotationUpdate update) {
        PacketEntity target = AimTargetTraceUtil.getPlayerTarget(player);
        if (target == null || target.type != EntityTypes.PLAYER) {
            return;
        }

        if (!player.actionManager.hasAttackedSince(50L) || player.getDeltaXZ() <= 0.1) {
            return;
        }

        Vec2f rotation = AimTargetTraceUtil.offsetRotations(
                player.lastX,
                player.lastY,
                player.lastZ,
                update.getFrom().getYaw(),
                update.getFrom().getPitch(),
                AimTargetTraceUtil.getTargetBox(target)
        );

        if (update.getDeltaYRotABS() > 0.0F) {
            yawMatches.add(Math.abs(MathUtil.getAngleDifference(rotation.x(), update.getTo().getYaw())));
        }
        if (update.getDeltaXRotABS() > 0.0F) {
            pitchMatches.add(Math.abs(MathUtil.getAngleDifference(rotation.y(), update.getTo().getPitch())));
        }

        if (yawMatches.size() == 150) {
            List<Float> close = yawMatches.stream().filter(delta -> delta <= 1.5F).collect(Collectors.toList());
            if (close.size() >= 110 && flagAndAlert("* Rotation offset (common, yaw)\navg= " + MathUtil.getAverage(close) + "\nrate= " + close.size())) {
                player.mitigateDamage();
            }
            yawMatches.clear();
        }

        if (pitchMatches.size() == 150) {
            List<Float> close = pitchMatches.stream().filter(delta -> delta <= 2.0F).collect(Collectors.toList());
            if (close.size() >= 110 && flagAndAlert("* Rotation offset (common, pitch)\navg= " + MathUtil.getAverage(close) + "\nrate= " + close.size())) {
                player.mitigateDamage();
            }
            pitchMatches.clear();
        }
    }
}
