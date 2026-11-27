package cc.watchneko.platform.bukkit.events;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.checks.impl.inventory.AutoTotem;
import cc.watchneko.checks.impl.inventory.AutoTotem.Action;
import cc.watchneko.checks.impl.inventory.AutoTotem.InputKind;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Server-side confirmation for the packet observations in {@link AutoTotem}. */
public final class AutoTotemListener implements Listener {
    private final AtomicLong nextToken = new AtomicLong();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player) || !usedOffhandTotem(event, player)) return;
        Context context = context(player, false);
        if (context == null) return;

        UUID world = player.getWorld().getUID();
        long now = System.nanoTime();
        context.player().runSafely(() -> context.check().onTotemPop(world, now));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Context context = context(player, true);
        if (context == null) return;

        InputKind kind = inputKind(event.getClick());
        int button = switch (kind) {
            case LEFT -> 0;
            case RIGHT -> 1;
            case OFFHAND_SWAP -> 40;
            case HOTBAR_SWAP -> event.getHotbarButton();
            default -> -1;
        };
        Action action = inventoryAction(event, player, kind, button);
        boolean cancelled = event.isCancelled();
        long token = nextToken.incrementAndGet();
        long now = System.nanoTime();
        int rawSlot = event.getRawSlot();
        UUID world = player.getWorld().getUID();
        context.player().runSafely(() -> context.check().onServerAction(world, kind, rawSlot, button,
                action, cancelled, token, now));
        if (!cancelled && (action == Action.CURSOR_PLACE || action == Action.INVENTORY_SWAP)) {
            verifyOffhand(context, player, world, token);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        Context context = context(player, true);
        if (context == null) return;

        boolean replenished = isTotem(event.getOffHandItem()) && !isTotem(player.getInventory().getItemInOffHand());
        Action action = replenished ? Action.HAND_SWAP : Action.OTHER;
        boolean cancelled = event.isCancelled();
        long token = nextToken.incrementAndGet();
        long now = System.nanoTime();
        UUID world = player.getWorld().getUID();
        context.player().runSafely(() -> context.check().onServerAction(world, InputKind.HAND_SWAP, -1, -1,
                action, cancelled, token, now));
        if (!cancelled && replenished) verifyOffhand(context, player, world, token);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) inventoryBoundary(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) inventoryBoundary(player);
    }

    private void inventoryBoundary(Player player) {
        Context context = context(player, true);
        if (context == null) return;
        UUID world = player.getWorld().getUID();
        context.player().runSafely(() -> context.check().onInventoryBoundary(world));
    }

    private void verifyOffhand(Context context, Player player, UUID world, long token) {
        if (context.player().platformPlayer == null) return;
        WatchNekoAPI.INSTANCE.getScheduler().getEntityScheduler().run(context.player().platformPlayer,
                WatchNekoAPI.INSTANCE.getGrimPlugin(), () -> {
                    boolean confirmed = player.isOnline() && world.equals(player.getWorld().getUID())
                            && isTotem(player.getInventory().getItemInOffHand());
                    long now = System.nanoTime();
                    context.player().runSafely(() -> context.check().onVerified(world, token, confirmed, now));
                }, null);
    }

    private static Context context(Player player, boolean requireObservation) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        PlayerData data = user == null ? null : WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
        if (data == null) return null;
        AutoTotem check = data.checkManager.getCheck(AutoTotem.class);
        if (!check.isConfiguredEnabled() || requireObservation && !check.isObserving()) return null;
        return new Context(data, check);
    }

    static boolean usedOffhandTotem(EntityResurrectEvent event, Player player) {
        if (!isTotem(player.getInventory().getItemInOffHand())) return false;
        try {
            return event.getHand() == EquipmentSlot.OFF_HAND;
        } catch (NoSuchMethodError ignored) {
            // Older Bukkit versions select the main hand before the offhand.
            return !isTotem(player.getInventory().getItemInMainHand());
        }
    }

    static InputKind inputKind(ClickType click) {
        return switch (click) {
            case LEFT -> InputKind.LEFT;
            case RIGHT -> InputKind.RIGHT;
            case SWAP_OFFHAND -> InputKind.OFFHAND_SWAP;
            case NUMBER_KEY -> InputKind.HOTBAR_SWAP;
            // Older Bukkit versions have no SWAP_OFFHAND value. Packet matching and offhand
            // verification still have to confirm that an UNKNOWN click actually swapped hands.
            case UNKNOWN -> InputKind.OFFHAND_SWAP;
            default -> InputKind.OTHER;
        };
    }

    static Action inventoryAction(InventoryClickEvent event, Player player, InputKind kind, int button) {
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        InventoryAction action = event.getAction();
        boolean offhandSlot = event.getClickedInventory() instanceof PlayerInventory && event.getSlot() == 40;

        if ((kind == InputKind.LEFT || kind == InputKind.RIGHT) && isTotem(current) && !isTotem(cursor)
                && (action == InventoryAction.PICKUP_ALL || action == InventoryAction.PICKUP_SOME
                || action == InventoryAction.PICKUP_HALF || action == InventoryAction.PICKUP_ONE)) {
            return Action.PICKUP;
        }
        if ((kind == InputKind.LEFT || kind == InputKind.RIGHT) && offhandSlot && isTotem(cursor)
                && !isTotem(current) && (action == InventoryAction.PLACE_ALL || action == InventoryAction.PLACE_SOME
                || action == InventoryAction.PLACE_ONE || action == InventoryAction.SWAP_WITH_CURSOR)) {
            return Action.CURSOR_PLACE;
        }
        boolean swapAction = action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD
                || kind == InputKind.OFFHAND_SWAP && event.getClick() == ClickType.UNKNOWN && action == InventoryAction.UNKNOWN;
        if (swapAction && !isTotem(player.getInventory().getItemInOffHand())) {
            if (kind == InputKind.OFFHAND_SWAP && isTotem(current)) return Action.INVENTORY_SWAP;
            if (kind == InputKind.HOTBAR_SWAP && offhandSlot && button >= 0 && button < 9
                    && isTotem(player.getInventory().getItem(button))) return Action.INVENTORY_SWAP;
        }
        return Action.OTHER;
    }

    private static boolean isTotem(ItemStack item) {
        if (item == null || item.getAmount() <= 0) return false;
        String type = item.getType().name();
        return type.equals("TOTEM_OF_UNDYING") || type.equals("TOTEM");
    }

    private record Context(PlayerData player, AutoTotem check) {}
}
