package cc.watchneko.checks.impl.groundspoof;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.checks.type.PostPredictionCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.PredictionComplete;

@CheckData(name = "NoFallB", setback = 0)
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
//        if (lastFallDistance > 0 && fallDistance == 0) alert("lfd=" + lastFallDistance + ", log=" + player.lastOnGround + ", fd=" + fallDistance + ", og=" + player.onGround);

        if (lastFallDistance > 0 && fallDistance == 0 && !player.lastOnGround && !player.isClimbing && !player.isGliding && !player.isFlying && !player.isInBed && !player.isSwimming && !player.isRiptidePose) {

            if (!player.packetStateData.lastPacketWasTeleport) {
                player.bukkitPlayer.setFallDistance((float) lastFallDistance);
                player.fallDistance = lastFallDistance;
                player.checkManager.getNoFall().flipPlayerGroundStatus = true;
                flagAndAlert("lfd=" + lastFallDistance + ", log=" + player.lastOnGround + ", fd=" + fallDistance + ", og=" + player.onGround);
            } else if (!ignoreTeleport) {
                player.bukkitPlayer.setFallDistance((float) lastFallDistance);
                player.fallDistance = lastFallDistance;
                player.checkManager.getNoFall().flipPlayerGroundStatus = true;
            }
        }

        lastFallDistance = fallDistance;
    }
}
