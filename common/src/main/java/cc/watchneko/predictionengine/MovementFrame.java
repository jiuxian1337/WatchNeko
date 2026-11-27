package cc.watchneko.predictionengine;

import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.PositionUpdate;
import cc.watchneko.utils.data.KnownInput;
import cc.watchneko.utils.enums.Pose;
import com.github.retrooper.packetevents.util.Vector3d;

/** Movement state observed at the connection boundary, before the physics candidates mutate player state. */
public record MovementFrame(long tick, int receipt, Vector3d from, Vector3d to, Vector3d startingVelocity,
                            Pose pose, KnownInput input, double fallDistance, boolean vehicle, boolean reportedPosition) {
    static MovementFrame capture(PlayerData player, PositionUpdate update, long tick, boolean reportedPosition) {
        return new MovementFrame(tick, player.getLastTransactionReceived(), update.getFrom(),
                new Vector3d(player.x, player.y, player.z),
                new Vector3d(player.clientVelocity.getX(), player.clientVelocity.getY(), player.clientVelocity.getZ()),
                player.pose, player.packetStateData.knownInput, player.fallDistance, player.inVehicle(), reportedPosition);
    }
}
