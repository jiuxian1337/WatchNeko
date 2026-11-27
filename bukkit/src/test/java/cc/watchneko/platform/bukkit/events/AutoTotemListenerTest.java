package cc.watchneko.platform.bukkit.events;

import cc.watchneko.checks.impl.inventory.AutoTotem.Action;
import cc.watchneko.checks.impl.inventory.AutoTotem.InputKind;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoTotemListenerTest {
    @Test
    void identifiesOffhandResurrectionsAndLegacyHandPriority() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        EntityResurrectEvent event = mock(EntityResurrectEvent.class);
        ItemStack totem = totem();
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInOffHand()).thenReturn(totem);

        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        assertTrue(AutoTotemListener.usedOffhandTotem(event, player));
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        assertFalse(AutoTotemListener.usedOffhandTotem(event, player));

        when(event.getHand()).thenThrow(new NoSuchMethodError());
        assertTrue(AutoTotemListener.usedOffhandTotem(event, player));
        when(inventory.getItemInMainHand()).thenReturn(totem);
        assertFalse(AutoTotemListener.usedOffhandTotem(event, player));
    }

    @Test
    void identifiesCursorPickupAndPlacementInTheActualOffhandSlot() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        ItemStack totem = totem();
        when(player.getInventory()).thenReturn(inventory);

        when(event.getCurrentItem()).thenReturn(totem);
        when(event.getAction()).thenReturn(InventoryAction.PICKUP_ALL);
        assertEquals(Action.PICKUP, AutoTotemListener.inventoryAction(event, player, InputKind.LEFT, 0));

        when(event.getCurrentItem()).thenReturn(null);
        when(event.getCursor()).thenReturn(totem);
        when(event.getAction()).thenReturn(InventoryAction.PLACE_ALL);
        when(event.getClickedInventory()).thenReturn(inventory);
        when(event.getSlot()).thenReturn(40);
        assertEquals(Action.CURSOR_PLACE, AutoTotemListener.inventoryAction(event, player, InputKind.LEFT, 0));
        when(event.getSlot()).thenReturn(39);
        assertEquals(Action.OTHER, AutoTotemListener.inventoryAction(event, player, InputKind.LEFT, 0));
    }

    @Test
    void classifiesOffhandAndHotbarKeySwapsWithoutTreatingThemAsCursorClicks() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        ItemStack totem = totem();
        when(player.getInventory()).thenReturn(inventory);
        when(event.getAction()).thenReturn(InventoryAction.HOTBAR_SWAP);
        when(event.getCurrentItem()).thenReturn(totem);
        assertEquals(Action.INVENTORY_SWAP,
                AutoTotemListener.inventoryAction(event, player, InputKind.OFFHAND_SWAP, 40));
        when(event.getClick()).thenReturn(ClickType.UNKNOWN);
        when(event.getAction()).thenReturn(InventoryAction.UNKNOWN);
        assertEquals(Action.INVENTORY_SWAP,
                AutoTotemListener.inventoryAction(event, player, InputKind.OFFHAND_SWAP, 40));

        when(event.getCurrentItem()).thenReturn(null);
        when(event.getAction()).thenReturn(InventoryAction.HOTBAR_SWAP);
        when(event.getClickedInventory()).thenReturn(inventory);
        when(event.getSlot()).thenReturn(40);
        when(inventory.getItem(2)).thenReturn(totem);
        assertEquals(Action.INVENTORY_SWAP,
                AutoTotemListener.inventoryAction(event, player, InputKind.HOTBAR_SWAP, 2));
        when(inventory.getItemInOffHand()).thenReturn(totem);
        assertEquals(Action.OTHER, AutoTotemListener.inventoryAction(event, player, InputKind.HOTBAR_SWAP, 2));
    }

    @Test
    void mapsBukkitClicksToTheirPacketActions() {
        assertEquals(InputKind.LEFT, AutoTotemListener.inputKind(ClickType.LEFT));
        assertEquals(InputKind.RIGHT, AutoTotemListener.inputKind(ClickType.RIGHT));
        assertEquals(InputKind.OFFHAND_SWAP, AutoTotemListener.inputKind(ClickType.SWAP_OFFHAND));
        assertEquals(InputKind.OFFHAND_SWAP, AutoTotemListener.inputKind(ClickType.UNKNOWN));
        assertEquals(InputKind.HOTBAR_SWAP, AutoTotemListener.inputKind(ClickType.NUMBER_KEY));
        assertEquals(InputKind.OTHER, AutoTotemListener.inputKind(ClickType.SHIFT_LEFT));
    }

    private static ItemStack totem() {
        ItemStack totem = mock(ItemStack.class);
        when(totem.getType()).thenReturn(Material.TOTEM_OF_UNDYING);
        when(totem.getAmount()).thenReturn(1);
        return totem;
    }
}
