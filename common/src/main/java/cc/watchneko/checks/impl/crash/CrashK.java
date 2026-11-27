package cc.watchneko.checks.impl.crash;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;

@CheckData(name = "CrashK", description = "Creative inventory item and action limits", experimental = true, setback = -1)
public final class CrashK extends ExperimentalCrashCheck {
    private int maxAmount;
    private int maxEnchantment;
    private int maxName;
    private int maxLore;

    public CrashK(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        maxAmount = setting(config, "max-stack-amount", 99, 1024);
        maxEnchantment = setting(config, "max-enchantment-level", 255, 32767);
        maxName = setting(config, "max-name-bytes", 4096, 262144);
        maxLore = setting(config, "max-lore-bytes", 16384, 262144);
    }

    @Override
    void checkPacket(PacketReceiveEvent event) {
        var type = event.getPacketType();
        if (type == PacketType.Play.Client.SET_TEST_BLOCK || type == PacketType.Play.Client.TEST_INSTANCE_BLOCK_ACTION) {
            if (player.gamemode != null && player.gamemode != GameMode.CREATIVE) reject(event, "creative-only-action");
            return;
        }
        // CrashB already handles creative inventory actions from other game modes.
        if (type != PacketType.Play.Client.CREATIVE_INVENTORY_ACTION || player.gamemode != GameMode.CREATIVE) return;
        ItemStack item = new WrapperPlayClientCreativeInventoryAction(event).getItemStack();
        if (item == null || item.isEmpty()) return;
        if (item.getAmount() > maxAmount) {
            reject(event, "creative-stack-size count=" + item.getAmount());
            return;
        }
        for (var enchantment : item.getEnchantments()) {
            if (enchantment.getLevel() < 0 || enchantment.getLevel() > maxEnchantment) {
                reject(event, "creative-enchantment-level");
                return;
            }
        }
        boolean components = item.getVersion().isNewerThanOrEquals(ClientVersion.V_1_20_5);
        if (textOverLimit(item, components)) {
            reject(event, "creative-item-text-size");
            return;
        }
        NBTCompound block = components ? item.getComponentOr(ComponentTypes.BLOCK_ENTITY_DATA, null) : null;
        if (block == null && item.getNBT() != null) block = item.getNBT().getCompoundTagOrNull("BlockEntityTag");
        if (block == null || !player.getSetbackTeleportUtil().hasAcceptedSpawnTeleport) return;
        var x = block.getNumberTagOrNull("x");
        var z = block.getNumberTagOrNull("z");
        if (x != null && z != null && !player.compensatedWorld.isChunkLoaded(x.getAsInt() >> 4, z.getAsInt() >> 4)) {
            reject(event, "creative-block-data-outside-client-chunks");
        }
    }

    private boolean textOverLimit(ItemStack item, boolean components) {
        if (components && (CrashItemText.size(item.getComponentOr(ComponentTypes.CUSTOM_NAME, null), maxName) > maxName
                || CrashItemText.size(item.getComponentOr(ComponentTypes.ITEM_NAME, null), maxName) > maxName)) return true;
        var lore = components ? item.getComponentOr(ComponentTypes.LORE, null) : null;
        int remaining = maxLore;
        if (lore != null) {
            for (var line : lore.getLines()) {
                remaining -= CrashItemText.size(line, remaining);
                if (remaining < 0) return true;
            }
        }
        NBTCompound nbt = item.getNBT();
        NBTCompound display = nbt == null ? null : nbt.getCompoundTagOrNull("display");
        if (display == null) return false;
        if (utf8Bytes(display.getStringTagValueOrNull("Name")) > maxName) return true;
        var legacyLore = display.getStringListTagOrNull("Lore");
        if (legacyLore == null) return false;
        remaining = maxLore;
        for (NBTString line : legacyLore.getTags()) {
            remaining -= utf8Bytes(line.getValue());
            if (remaining < 0) return true;
        }
        return false;
    }
}
