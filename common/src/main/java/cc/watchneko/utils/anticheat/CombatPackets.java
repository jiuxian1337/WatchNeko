package cc.watchneko.utils.anticheat;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;

public final class CombatPackets {
    private CombatPackets() {}

    /** Returns the target ID of an incoming attack, or -1 for other packets. */
    public static int attackTarget(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            return new WrapperPlayClientAttack(event).getEntityId();
        }
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            var interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                return interact.getEntityId();
            }
        }
        return -1;
    }

    public static boolean isMainHandSwing(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.ANIMATION
                && new WrapperPlayClientAnimation(event).getHand() == InteractionHand.MAIN_HAND;
    }
}
