package cc.watchneko.checks.impl.inventory;

import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.InventoryCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.PredictionComplete;
import cc.watchneko.utils.data.VectorData;
import cc.watchneko.utils.data.VectorData.MoveVectorData;
import cc.watchneko.utils.data.VehicleData;

import java.util.StringJoiner;

@CheckData(name = "InventoryD", setback = 1, decay = 0.25)
public class InventoryD extends InventoryCheck {
    private int horseJumpVerbose;

    public InventoryD(PlayerData player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked() ||
                predictionComplete.getData().isTeleport() ||
                player.getSetbackTeleportUtil().blockOffsets ||
                player.packetStateData.lastPacketWasTeleport ||
                player.packetStateData.isSlowedByUsingItem() ||
                System.currentTimeMillis() - player.lastBlockPlaceUseItem < 50L) {
            return;
        }

        if (player.openWindow.mustBeOpen()) {
            boolean inVehicle = player.inVehicle();
            boolean isJumping, isMoving;

            if (inVehicle) {
                VehicleData vehicle = player.vehicleData;
                isJumping = (vehicle.horseJumping || vehicle.nextHorseJump > 0) && horseJumpVerbose++ >= 1;
                isMoving = vehicle.nextVehicleForward != 0 || vehicle.nextVehicleHorizontal != 0;
            } else {
                MoveVectorData move = findMovement(player.predictedVelocity);
                isJumping = player.predictedVelocity.isJump();
                isMoving = move != null && (move.x != 0 || move.z != 0);
            }

            if (!isMoving && !isJumping) {
                reward();
                return;
            }

            if (flag()) {
                if (!isNoSetbackPermission())
                    closeInventory();

                StringJoiner joiner = new StringJoiner(" ");
                if (isMoving) joiner.add("moving");
                if (isJumping) joiner.add("jumping");
                if (inVehicle) joiner.add("inVehicle");
                alert(joiner.toString());
            }
        } else {
            horseJumpVerbose = 0;
        }
    }

    private MoveVectorData findMovement(VectorData vectorData) {
        if (vectorData instanceof MoveVectorData) {
            return (MoveVectorData) vectorData;
        }
        while (vectorData != null) {
            vectorData = vectorData.lastVector;
            if (vectorData instanceof MoveVectorData) {
                return (MoveVectorData) vectorData;
            }
        }
        return null;
    }
}
