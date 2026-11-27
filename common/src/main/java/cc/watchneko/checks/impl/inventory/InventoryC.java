package cc.watchneko.checks.impl.inventory;

import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.InventoryCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.BlockPlace;

@CheckData(name = "InventoryC", setback = 3, description = "Placed a block while inventory is open")
public class InventoryC extends InventoryCheck {

    public InventoryC(PlayerData player) {
        super(player);
    }

    public void onBlockPlace(final BlockPlace place) {
        if (player.openWindow.mustBeOpen()) {
            if (flagAndAlert()) {
                if (shouldModifyPackets()) {
                    place.resync();
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
