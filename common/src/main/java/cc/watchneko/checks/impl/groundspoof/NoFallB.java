package cc.watchneko.checks.impl.groundspoof;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PostPredictionCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.PredictionComplete;
import cc.watchneko.utils.math.Vector3dm;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

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
        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_17) && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_17)) {
            float fallDistance = player.bukkitPlayer.getFallDistance();
//        if (lastFallDistance > 0 && fallDistance == 0) alert("lfd=" + lastFallDistance + ", log=" + player.lastOnGround + ", fd=" + fallDistance + ", og=" + player.onGround);

            if (lastFallDistance > 0 && fallDistance == 0
                    && !player.lastOnGround && !player.isClimbing
                    && !player.isGliding && !player.isFlying
                    && !player.isInBed && !player.isSwimming
                    && !player.isRiptidePose && !player.wasTouchingWater
                    && !player.wasWasTouchingWater && !player.wasTouchingLava
                    && !player.slightlyTouchingLava && !player.slightlyTouchingWater
                    // CobWeb
                    && player.stuckSpeedMultiplier.equals(new Vector3dm(1, 1, 1))) {

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
}
