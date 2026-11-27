package cc.watchneko.checks.impl.crash;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.CheckData;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.inventory.inventory.NotImplementedMenu;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.item.HashedStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;

import java.util.Map;
import java.util.Optional;

@CheckData(name = "CrashS", description = "Window slot and component patch limits", experimental = true, setback = -1)
public final class CrashS extends ExperimentalCrashCheck {
    private int maxChangedSlots;
    private int maxHashedComponents;

    public CrashS(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        maxChangedSlots = setting(config, "max-changed-slots", 128, 8192);
        maxHashedComponents = setting(config, "max-hashed-components-per-stack", 128, 4096);
    }

    @Override
    void checkPacket(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.CLICK_WINDOW) return;
        var click = new WrapperPlayClientClickWindow(event);
        int windowId = click.getWindowId();
        int slotCount = knownSlotCount(windowId);
        if (windowId < 0 || !validClickSlot(click.getSlot(), slotCount)) {
            reject(event, "inventory-click-slot");
            return;
        }

        Map<Integer, Optional<HashedStack>> hashed = click.getHashedSlots();
        if (hashed != null) {
            if (!validChanges(hashed, slotCount) || exceedsComponentLimit(click.getCarriedHashedStack().orElse(null))
                    || hashed.values().stream().anyMatch(stack -> exceedsComponentLimit(stack.orElse(null)))) {
                reject(event, "inventory-hashed-changes");
            }
        } else if (click.getSlots().isPresent() && !validChanges(click.getSlots().get(), slotCount)) {
            reject(event, "inventory-slot-changes");
        }
    }

    static boolean validClickSlot(int slot, int count) {
        return slot == -999 || slot == -1 || slot >= 0 && (count < 0 || slot < count);
    }

    private boolean validChanges(Map<Integer, ?> slots, int count) {
        if (slots.size() > maxChangedSlots) return false;
        for (int slot : slots.keySet()) {
            if (slot < 0 || count >= 0 && slot >= count) return false;
        }
        return true;
    }

    private boolean exceedsComponentLimit(HashedStack stack) {
        if (stack == null) return false;
        var patch = stack.getComponents();
        return (long) patch.getAddedComponents().size() + patch.getRemovedComponents().size() > maxHashedComponents;
    }

    private int knownSlotCount(int windowId) {
        var inventory = player.inventory;
        if (!inventory.isPacketInventoryActive || inventory.getOpenWindowID() != windowId
                || inventory.menu instanceof NotImplementedMenu) return -1;
        if (windowId > 0 && player.openWindow.getContainerSequence(windowId) < 0) return -1;
        return inventory.menu.getSlots().size();
    }
}
