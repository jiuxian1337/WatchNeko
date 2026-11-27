package cc.watchneko.checks.impl.inventory;

import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.InventoryCheck;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

@CheckData(name = "InventoryF", setback = 3, description = "Sent a click window packet without a open inventory", experimental = true)
public class InventoryF extends InventoryCheck {

    public InventoryF(PlayerData player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9)
                || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) return;

        super.onPacketReceive(event);

        if (event.getPacketType() == PacketType.Play.Client.CLICK_WINDOW) {
            if (player.openWindow.mustBeClosed()) {
                if (flagAndAlert()) {
                    if (shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                    if (!isNoSetbackPermission()) {
                        closeInventory();
                    }
                }
            } else if (player.openWindow.mustBeOpen()) {
                reward();
            }
        }
    }
}
