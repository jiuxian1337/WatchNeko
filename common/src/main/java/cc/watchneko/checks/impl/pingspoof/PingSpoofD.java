package cc.watchneko.checks.impl.pingspoof;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.data.TrackerData;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;

@CheckData(name = "PingSpoofD", description = "entity position staleness on attack (pending transaction gap)", decay = 0.05)
public final class PingSpoofD extends Check implements PacketCheck {
    private static final double VL_PER_FLAG = 0.5;

    private int basePendingAllowance;
    private int maxPingTicks;

    public PingSpoofD(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        super.onReload(config);
        this.basePendingAllowance = config.getIntElse(getConfigName() + ".base-pending-allowance", 2);
        this.maxPingTicks = config.getIntElse(getConfigName() + ".max-ping-ticks", 20);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        if (player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR || player.inVehicle()) {
            return;
        }

        WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
        if (interact.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        PacketEntity entity = player.compensatedEntities.getEntity(interact.getEntityId());
        if (entity == null || entity.isDead || entity.riding != null || entity.type != EntityTypes.PLAYER) {
            return;
        }

        TrackerData trackerData = player.compensatedEntities.getTrackedEntity(interact.getEntityId());
        if (trackerData == null) {
            return;
        }

        int pendingTransactions = Math.max(0, trackerData.getLastTransactionHung() - player.lastTransactionReceived.get());
        int pingTicks = Math.max(1, Math.min(maxPingTicks, (int) Math.ceil(Math.max(1, player.getTransactionPing()) / 50.0D)));
        int allowedPending = basePendingAllowance + pingTicks + 2;

        if (pendingTransactions >= allowedPending + 2) {
            buffer += VL_PER_FLAG;
            if (buffer >= 1.0D
                    && flagAndAlert("pending=" + pendingTransactions + " allowed=" + allowedPending
                    + " transPing=" + player.getTransactionPing() + "ms")) {
                player.mitigateDamage();
            }
            return;
        }

        rewardBufferAndVL();
    }

}
