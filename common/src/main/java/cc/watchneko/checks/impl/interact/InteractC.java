package cc.watchneko.checks.impl.interact;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import cc.watchneko.utils.data.packetentity.dragon.PacketEntityEnderDragonPart;
import cc.watchneko.utils.nmsutil.InteractVisibilityUtil;
import cc.watchneko.utils.nmsutil.ReachUtils;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity.InteractAction;

@CheckData(name = "InteractC", description = "right-clicked an entity through blocks", decay = 0.05)
public final class InteractC extends Check implements PacketCheck {
    private int cancelVL;

    public InteractC(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        super.onReload(config);
        cancelVL = config.getIntElse(getConfigName() + ".cancelvl", 0);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY || event.isCancelled()
                || !isEnabled() || player.disableGrim) {
            return;
        }
        if (player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR || player.inVehicle()) {
            return;
        }

        var interact = new WrapperPlayClientInteractEntity(event);
        InteractAction action = interact.getAction();
        if (action != InteractAction.INTERACT && action != InteractAction.INTERACT_AT) {
            return;
        }

        PacketEntity entity = player.compensatedEntities.getEntity(interact.getEntityId());
        if (entity == null || entity == player.compensatedEntities.self || entity instanceof PacketEntityEnderDragonPart
                || entity.riding != null || !entity.canHit()) {
            return;
        }
        // The entity mirror does not track the metadata-defined dimensions of interaction entities.
        if (entity.type == EntityTypes.INTERACTION) {
            return;
        }

        SimpleCollisionBox targetBox = entity.getPossibleCollisionBoxes();
        double interactionRange = player.compensatedEntities.self.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        // Reach owns out-of-range packets. This also expands the box for legacy picking and position uncertainty.
        if (ReachUtils.getMinReachToBox(player, targetBox) > interactionRange) {
            return;
        }

        if (InteractVisibilityUtil.isEntityVisible(player, targetBox)) {
            reward();
            return;
        }

        if (flagAndAlert("action=" + action + ", entity=" + interact.getEntityId() + ", type=" + entity.type.getName().getKey())
                && cancelVL >= 0 && violations >= cancelVL && shouldModifyPackets() && !isNoModifyPacketPermission()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
