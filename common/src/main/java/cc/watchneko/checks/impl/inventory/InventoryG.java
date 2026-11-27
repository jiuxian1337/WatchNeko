package cc.watchneko.checks.impl.inventory;

import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.InventoryCheck;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;

@CheckData(name = "InventoryG", setback = 3, description = "Sent a entity action packet while inventory is open", experimental = true)
public class InventoryG extends InventoryCheck {

    public InventoryG(PlayerData player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (player.packetStateData.lastPacketWasTeleport) return;
        super.onPacketReceive(event);

        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction wrapper = new WrapperPlayClientEntityAction(event);
            WrapperPlayClientEntityAction.Action action = wrapper.getAction();

            if (action == WrapperPlayClientEntityAction.Action.STOP_SNEAKING
                    || action == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) {
                return;
            }

            if (player.openWindow.mustBeOpen()) {
                if (flagAndAlert() && !isNoSetbackPermission()) {
                    closeInventory();
                }
            } else if (player.openWindow.mustBeClosed()) {
                reward();
            }
        }
    }
}
