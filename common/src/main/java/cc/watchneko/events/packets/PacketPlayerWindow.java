package cc.watchneko.events.packets;

import cc.watchneko.WatchNekoAPI;
import cc.watchneko.checks.impl.inventory.ChestStealer;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.latency.CompensatedOpenWindow;
import cc.watchneko.utils.latency.CompensatedOpenWindow.ServerChange;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClientStatus;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClientStatus.Action;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenHorseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;

public class PacketPlayerWindow extends PacketListenerAbstract {

    public PacketPlayerWindow() {
        super(PacketListenerPriority.LOWEST);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        var type = event.getPacketType();
        boolean flying = WrapperPlayClientPlayerFlying.isFlying(type);
        if (!flying && type != PacketType.Play.Client.CLIENT_STATUS
                && type != PacketType.Play.Client.CLICK_WINDOW && type != PacketType.Play.Client.CLOSE_WINDOW) return;

        PlayerData player = WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player == null) return;

        if (flying) {
            if (!event.isCancelled() && isNearNetherPortal(player)) {
                player.openWindow.mayHaveClosed();
            }
        } else if (type == PacketType.Play.Client.CLIENT_STATUS) {
            if (new WrapperPlayClientClientStatus(event).getAction() == Action.OPEN_INVENTORY_ACHIEVEMENT) {
                player.openWindow.onPlayerInventoryOpened();
            }
        } else if (type == PacketType.Play.Client.CLICK_WINDOW) {
            if (event.isCancelled()) return;
            var click = new WrapperPlayClientClickWindow(event);
            int windowId = click.getWindowId();
            if (windowId == CompensatedOpenWindow.PLAYER_INVENTORY && canOpenInventoryWithoutStatus(player)) {
                player.openWindow.onPlayerInventoryOpened();
            } else {
                player.openWindow.onContainerClick(windowId);
            }
            ChestStealer chestStealer = player.checkManager.getCheck(ChestStealer.class);
            if (chestStealer.isConfiguredEnabled()) {
                int button = click.getButton();
                WindowClickType mode = click.getWindowClickType();
                ChestStealer.Click transfer = null;
                if (mode == WindowClickType.QUICK_MOVE && (button == 0 || button == 1)) {
                    transfer = new ChestStealer.Click(click.getSlot(), button, true);
                } else if (mode == WindowClickType.SWAP && button >= 0 && button < 9) {
                    transfer = new ChestStealer.Click(click.getSlot(), button, false);
                }
                chestStealer.onClientClick(player.openWindow.getContainerSequence(windowId), transfer, System.nanoTime());
            }
        } else {
            int windowId = new WrapperPlayClientCloseWindow(event).getWindowId();
            ChestStealer chestStealer = player.checkManager.getCheck(ChestStealer.class);
            if (!event.isCancelled() && chestStealer.isConfiguredEnabled()) {
                chestStealer.onClientClose(player.openWindow.getContainerSequence(windowId), System.nanoTime());
            }
            player.openWindow.onClientClose(windowId);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        var type = event.getPacketType();
        if (event.isCancelled() || (type != PacketType.Play.Server.OPEN_WINDOW && type != PacketType.Play.Server.OPEN_HORSE_WINDOW
                && type != PacketType.Play.Server.CLOSE_WINDOW && type != PacketType.Play.Server.RESPAWN)) return;

        PlayerData player = WatchNekoAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
        if (player == null) return;

        ServerChange change;
        if (type == PacketType.Play.Server.OPEN_WINDOW) {
            var open = new WrapperPlayServerOpenWindow(event);
            if (open.getContainerId() < 0) return;
            boolean legacyBeacon = player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8)
                    && ("minecraft:beacon".equals(open.getLegacyType()) || open.getType() == 8);
            change = player.openWindow.serverOpen(open.getContainerId(), legacyBeacon || isNearNetherPortal(player));
        } else if (type == PacketType.Play.Server.OPEN_HORSE_WINDOW) {
            int windowId = new WrapperPlayServerOpenHorseWindow(event).getWindowId();
            if (windowId < 0) return;
            change = player.openWindow.serverOpen(windowId, false);
        } else {
            change = player.openWindow.serverClose();
        }

        trackServerChange(event, player, change);
    }

    private void trackServerChange(PacketSendEvent event, PlayerData player, ServerChange change) {
        int previousTransaction = player.lastTransactionSent.get();
        player.sendTransaction();
        int leadingTransaction = player.lastTransactionSent.get();
        boolean hasLeadingTransaction = leadingTransaction != previousTransaction;
        if (hasLeadingTransaction) {
            player.latencyUtils.addRealTimeTask(leadingTransaction, () -> {
                if (!event.isCancelled()) {
                    player.openWindow.mayHaveApplied(change);
                }
            });
        }

        event.getTasksAfterSend().add(() -> {
            if (event.isCancelled()) return;
            if (!hasLeadingTransaction) {
                player.openWindow.mayHaveApplied(change);
            }
            // If this send is suppressed, the next transaction sent after the window can still prove it.
            int nextTransaction = player.lastTransactionSent.get() + 1;
            player.sendTransaction();
            int proof = Math.max(nextTransaction, player.lastTransactionSent.get());
            player.latencyUtils.addRealTimeTask(proof, () -> {
                if (event.getPacketType() == PacketType.Play.Server.CLOSE_WINDOW) {
                    player.openWindow.confirmServerClose(change);
                } else {
                    player.openWindow.confirm(change);
                }
            });
        });
    }

    private boolean canOpenInventoryWithoutStatus(PlayerData player) {
        return player.getClientVersion().isNewerThan(ClientVersion.V_1_8)
                || PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9);
    }

    private boolean isNearNetherPortal(PlayerData player) {
        if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_12_1) &&
                player.pointThreeEstimator.isNearNetherPortal) {
            return !player.compensatedEntities.self.inVehicle() && player.compensatedEntities.self.passengers.isEmpty();
        }
        return false;
    }
}
