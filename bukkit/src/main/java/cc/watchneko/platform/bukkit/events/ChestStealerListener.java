package cc.watchneko.platform.bukkit.events;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.checks.impl.inventory.ChestStealer;
import cc.watchneko.checks.impl.inventory.ChestStealer.Click;
import cc.watchneko.checks.impl.inventory.ChestStealer.Outcome;
import cc.watchneko.platform.api.Platform;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChestStealerListener implements Listener {
    private final ConcurrentHashMap<UUID, Transfer> pending = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // The previous click has applied when Bukkit calls the next click or close on this player.
        finish(player, pending.remove(player.getUniqueId()));

        PlayerData data = data(player);
        if (data == null) return;
        ChestStealer check = data.checkManager.getCheck(ChestStealer.class);
        if (!check.isObserving()) return;

        long now = System.nanoTime();
        UUID world = player.getWorld().getUID();
        int slot = event.getRawSlot();
        Click click = switch (event.getClick()) {
            case SHIFT_LEFT -> new Click(slot, 0, true);
            case SHIFT_RIGHT -> new Click(slot, 1, true);
            case NUMBER_KEY -> new Click(slot, event.getHotbarButton(), false);
            default -> null;
        };
        Inventory top = event.getInventory();
        if (click == null || event.isCancelled() || slot < 0 || slot >= top.getSize() || !isStorage(top)) {
            report(data, check, world, click, Outcome.OTHER, now);
            return;
        }

        ItemStack current = event.getCurrentItem();
        if (isEmpty(current)) {
            report(data, check, world, click, Outcome.EMPTY, now);
            return;
        }
        InventoryAction action = event.getAction();
        boolean transfer = click.quickMove() ? action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                : click.button() >= 0 && click.button() < 9
                && (action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD);
        if (!transfer || data.platformPlayer == null) {
            report(data, check, world, click, Outcome.OTHER, now);
            return;
        }

        ItemStack item = current.clone();
        int before = destinationAmount(player.getInventory(), item, click);
        Location storageLocation = WatchNekoAPI.INSTANCE.getPlatform() == Platform.FOLIA ? top.getLocation() : null;
        Transfer observation = new Transfer(data, check, world, top, storageLocation, click, item, before, now);
        UUID playerId = player.getUniqueId();
        pending.put(playerId, observation);
        WatchNekoAPI.INSTANCE.getScheduler().getEntityScheduler().run(data.platformPlayer,
                WatchNekoAPI.INSTANCE.getGrimPlugin(), () -> {
                    if (pending.remove(playerId, observation)) finish(player, observation);
                }, () -> pending.remove(playerId, observation));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        finish(player, pending.remove(player.getUniqueId()));
        PlayerData data = data(player);
        if (data == null) return;
        ChestStealer check = data.checkManager.getCheck(ChestStealer.class);
        if (!check.isObserving()) return;

        UUID world = player.getWorld().getUID();
        long now = System.nanoTime();
        data.runSafely(() -> check.onServerClose(world, now));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    private static void finish(Player player, Transfer transfer) {
        if (transfer == null) return;
        Outcome outcome = Outcome.OTHER;
        Location location = transfer.storageLocation();
        if (player.isOnline() && transfer.world().equals(player.getWorld().getUID())
                && (location == null || Bukkit.isOwnedByCurrentRegion(location))) {
            ItemStack item = transfer.item();
            Click click = transfer.click();
            int removed = item.getAmount() - similarAmount(transfer.storage().getItem(click.slot()), item);
            int gained = destinationAmount(player.getInventory(), item, click) - transfer.destinationBefore();
            if (removed > 0 && gained == removed) outcome = Outcome.TRANSFERRED;
        }
        report(transfer.data(), transfer.check(), transfer.world(), transfer.click(), outcome, transfer.eventNanos());
    }

    private static int destinationAmount(PlayerInventory inventory, ItemStack item, Click click) {
        if (!click.quickMove()) return similarAmount(inventory.getItem(click.button()), item);
        int amount = 0;
        for (int slot = 0; slot < 36; slot++) {
            amount += similarAmount(inventory.getItem(slot), item);
        }
        return amount;
    }

    private static int similarAmount(ItemStack stack, ItemStack item) {
        return !isEmpty(stack) && stack.getType() == item.getType() && stack.isSimilar(item) ? stack.getAmount() : 0;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getAmount() <= 0 || stack.getType().name().equals("AIR");
    }

    private static boolean isStorage(Inventory inventory) {
        return switch (inventory.getType().name()) {
            case "CHEST", "ENDER_CHEST", "BARREL", "SHULKER_BOX", "HOPPER", "DISPENSER", "DROPPER" -> true;
            default -> false;
        };
    }

    private static PlayerData data(Player player) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        return user == null ? null : WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
    }

    private static void report(PlayerData data, ChestStealer check, UUID world, Click click, Outcome outcome, long now) {
        data.runSafely(() -> check.onServerClick(world, click, outcome, now));
    }

    private record Transfer(PlayerData data, ChestStealer check, UUID world, Inventory storage, Location storageLocation,
                            Click click, ItemStack item, int destinationBefore, long eventNanos) {}
}
