package cc.watchneko.checks.impl.interact;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import cc.watchneko.utils.data.packetentity.dragon.PacketEntityEnderDragonPart;
import cc.watchneko.utils.nmsutil.InteractVisibilityUtil;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;

@CheckData(name = "InteractB", description = "entity attack visibility check", decay = 0.05)
public final class InteractB extends Check implements PacketCheck {
    private int cancelVL;

    public InteractB(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        super.onReload(config);
        this.cancelVL = config.getIntElse(getConfigName() + ".cancelvl", 0);
    }

    private boolean shouldCancel() {
        return cancelVL >= 0 && violations >= cancelVL;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        if (player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR || player.inVehicle()) {
            return;
        }

        final WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
        if (interact.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }

        final PacketEntity entity = player.compensatedEntities.getEntity(interact.getEntityId());
        if (entity == null || entity instanceof PacketEntityEnderDragonPart || entity.isDead || entity.riding != null || !entity.canHit()) {
            return;
        }

        if (InteractVisibilityUtil.isEntityVisible(player, entity.getPossibleCollisionBoxes())) {
            reward();
            return;
        }

        if (flagAndAlert("entity=" + interact.getEntityId() + ", type=" + entity.type.getName().getKey())
                && shouldCancel()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
