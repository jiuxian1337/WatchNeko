package cc.watchneko.checks.impl.inventory;

import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.InventoryCheck;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;

@CheckData(name = "InventoryB", setback = 3, description = "Started digging blocks while inventory is open")
public class InventoryB extends InventoryCheck {
    public InventoryB(PlayerData player) {
        super(player);
    }

    public void handle(PacketReceiveEvent event, WrapperPlayClientPlayerDigging wrapper) {
        if (wrapper.getAction() != DiggingAction.START_DIGGING) return;

        if (player.openWindow.mustBeOpen()) {
            if (flagAndAlert()) {
                if (shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
                if (!isNoSetbackPermission()) {
                    closeInventory();
                }
            }
        } else if (player.openWindow.mustBeClosed()) {
            reward();
        }
    }
}
