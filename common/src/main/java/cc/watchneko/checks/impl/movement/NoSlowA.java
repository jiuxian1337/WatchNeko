package cc.watchneko.checks.impl.movement;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PostPredictionCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

@CheckData(name = "NoSlowA (Prediction)", configName = "NoSlowA", description = "Was not slowed while using an item", setback = 0)
public class NoSlowA extends Check implements PostPredictionCheck {
    // The player sends that they switched items the next tick if they switch from an item that can be used
    // to another item that can be used.  What the fuck mojang.  Affects 1.8 (and most likely 1.7) clients.
    public boolean didSlotChangeLastTick = false;
    double offsetToFlag;
    double bestOffset = 1;

    public NoSlowA(PlayerData player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) return;

        // If the player was using an item for certain, and their predicted velocity had a flipped item
        if (player.packetStateData.isSlowedByUsingItem()) {
            // 1.8 users are not slowed the first tick they use an item, strangely
            if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8) && didSlotChangeLastTick) {
                didSlotChangeLastTick = false;
                return;
            }

            if (buffer >= 2) {
                ItemStack spigotItem = player.platformPlayer.getInventory().getItemInHand();
                ItemStack grimItem = player.inventory.getHeldItem();
                if (flagAndAlertWithSetback("bo=" + bestOffset + ", si=" + spigotItem + ", gi=" + grimItem)) {
                    player.mitigateDamage();
                    buffer = 1;
                }
            }

            if (bestOffset > offsetToFlag) {
                buffer++;
            } else {
                reward();
                buffer = Math.max(0, buffer - 0.25);
            }
        } else {
            buffer = 0;
        }

        bestOffset = 1;
    }

    public void handlePredictionAnalysis(double offset) {
        bestOffset = Math.min(bestOffset, offset);
    }

    @Override
    public void onReload(ConfigManager config) {
        offsetToFlag = config.getDoubleElse(getConfigName() + ".threshold", 0.001);
    }
}
