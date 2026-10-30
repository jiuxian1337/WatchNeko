package cc.watchneko.checks.impl.aim;

import cc.watchneko.analysis.aim.trajectory.*;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.impl.aim.processor.AimProcessor;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import java.util.ArrayList;
import java.util.List;

@CheckData(
        name = "AimV",
        configName = "AimV",
        decay = 0.85
)
public class AimV extends Check implements PacketCheck {
    private final DetectionContext context;
    private final List<AimDetectionStrategy> detectionStrategies;
    public ArrayList<Rotation> rotations = new ArrayList<>();

    public AimV(PlayerData player) {
        super(player);
        detectionStrategies = List.of(
                new AccelerationDetection(),
                new AimPathAnalysis(),
                new CorrelationAnalysis(),
                new CoordinationDetection(),
                new FrictionDetection()
        );
        context = new DetectionContext(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) return;
        if (player.packetStateData.lastPacketWasTeleport) {
            resetDetectionStrategies();
            return;
        }
        if (!shouldModifyPackets()) {
            return;
        }

        final float movedYaw = player.getYaw() - player.getLastYaw();
        final float movedPitch = player.getPitch() - player.getLastPitch();
        rotations.add(new Rotation(movedYaw, movedPitch));
        if (rotations.size() > 100) {
            rotations.remove(0);
        }
        if (!player.actionManager.hasAttackedSince(2000L)) {
            resetDetectionStrategies();
            return;
        }
        if (player.getTarget() == null) {
            resetDetectionStrategiesChangeTarget();
            return;
        }
        if (player.getLastTarget() != player.getTarget()) {
            resetDetectionStrategiesChangeTarget();
            return;
        }
        if (player.getTarget().type != EntityTypes.PLAYER) {
            resetDetectionStrategiesChangeTarget();
            return;
        }

        updateDetectionContext(player);
        executeDetectionStrategies(player);
        saveLastRotationValues();
    }

    private void updateDetectionContext(PlayerData profile) {
        context.setRotations(rotations);
        AimProcessor processor = profile.checkManager.getRotationCheck(AimProcessor.class);
        context.setDeltaYaw(processor.deltaYaw % 360F);
        context.setDeltaPitch(processor.deltaPitch);
        if (profile.getTarget() != null) {
            context.setOptimalYaw(processor.optimalYaw);
        }
        final float movedYaw = player.getYaw() - player.getLastYaw();
        final float movedPitch = player.getPitch() - player.getLastPitch();
        context.updateMovementData(profile.getYaw(), profile.getPitch(), time());
        context.updateRotation(new Rotation(profile.getYaw(), profile.getPitch()));
        context.updateRotationDelta(new Rotation(movedYaw, movedPitch));
    }

    private void executeDetectionStrategies(PlayerData profile) {
        for (AimDetectionStrategy strategy : detectionStrategies) {
            strategy.detect(profile, context);
        }
    }

    private void resetDetectionStrategies() {
        for (AimDetectionStrategy strategy : detectionStrategies) {
            strategy.reset();
        }
    }

    private void resetDetectionStrategiesChangeTarget() {
        for (AimDetectionStrategy strategy : detectionStrategies) {
            strategy.changeTarget();
        }
    }

    private void saveLastRotationValues() {
        context.setLastDeltaYaw(context.getDeltaYaw());
        context.setLastDeltaPitch(context.getDeltaPitch());
        context.setLastOptimalYaw(context.getOptimalYaw());
    }
}
