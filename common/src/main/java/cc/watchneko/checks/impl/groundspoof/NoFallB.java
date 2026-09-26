package cc.watchneko.checks.impl.groundspoof;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.checks.type.PostPredictionCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.PredictionComplete;

@CheckData(name = "NoFallB")
public class NoFallB extends Check implements PostPredictionCheck {

    private double lastFallDistance;
    private boolean ignoreTeleport;

    public NoFallB(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        this.ignoreTeleport = config.getBooleanElse(getConfigName() + ".ignore-teleport", false);
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        float fallDistance = player.bukkitPlayer.getFallDistance();

        if (lastFallDistance > 0 && fallDistance == 0 && !player.packetStateData.packetPlayerOnGround) {

            if (!player.packetStateData.lastPacketWasTeleport) {
                player.bukkitPlayer.setFallDistance((float) lastFallDistance);
                player.fallDistance = lastFallDistance;
                flagAndAlert("lfd=" + lastFallDistance + ", fd=" + fallDistance);
            } else if (!ignoreTeleport) {
                player.bukkitPlayer.setFallDistance((float) lastFallDistance);
                player.fallDistance = lastFallDistance;
            }
        }

        lastFallDistance = fallDistance;
    }
}
