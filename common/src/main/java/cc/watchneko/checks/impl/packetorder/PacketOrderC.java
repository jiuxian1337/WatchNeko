package cc.watchneko.checks.impl.packetorder;

import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.data.packetentity.PacketEntity;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

@CheckData(name = "PacketOrderC")
public class PacketOrderC extends Check implements PacketCheck {
    // 1.7 players do not send INTERACT_AT, so we cannot check them
    private final boolean exempt =
            player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_7_10)
                    // 26+的客户端不会发送interact 所以这里做skip掉
            || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1);
    private boolean sentInteractAt = false;
    private int requiredEntity;
    private InteractionHand requiredHand;
    private boolean requiredSneaking;

    public PacketOrderC(final PlayerData player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (exempt) {
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            final WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);

            final PacketEntity entity = player.compensatedEntities.entityMap.get(packet.getEntityId());

            // For armor stands, vanilla clients send:
            //  - when renaming the armor stand or in spectator mode: INTERACT_AT + INTERACT
            //  - in all other cases: only INTERACT
            // Just exempt armor stands to be safe
            if (entity != null && entity.type == EntityTypes.ARMOR_STAND) return;

            final boolean sneaking = packet.isSneaking().orElse(false);

            switch (packet.getAction()) {
                // INTERACT_AT then INTERACT
                case INTERACT:
                    if (!sentInteractAt) {
                        if (flagAndAlert("Skipped Interact-At") && shouldModifyPackets()) {
                            event.setCancelled(true);
                            player.onPacketCancel();
                        }
                    } else if (packet.getEntityId() != requiredEntity || packet.getHand() != requiredHand || sneaking != requiredSneaking) {
                        String verbose = "requiredEntity=" + requiredEntity + ", entity=" + packet.getEntityId()
                                + ", requiredHand=" + requiredHand + ", hand=" + packet.getHand()
                                + ", requiredSneaking=" + requiredSneaking + ", sneaking=" + sneaking;
                        if (flagAndAlert(verbose) && shouldModifyPackets()) {
                            event.setCancelled(true);
                            player.onPacketCancel();
                        }
                    }

                    sentInteractAt = false;
                    break;
                case INTERACT_AT:
                    if (sentInteractAt) {
                        if (flagAndAlert("Skipped Interact") && shouldModifyPackets()) {
                            event.setCancelled(true);
                            player.onPacketCancel();
                        }
                    }

                    requiredHand = packet.getHand();
                    requiredEntity = packet.getEntityId();
                    requiredSneaking = sneaking;
                    sentInteractAt = true;
                    break;
            }
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            if (sentInteractAt) {
                sentInteractAt = false;
                flagAndAlert("Skipped Interact (Tick)");
            }
        }
    }
}
