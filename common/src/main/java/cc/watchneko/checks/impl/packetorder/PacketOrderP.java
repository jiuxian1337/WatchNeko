/*
 * Based on TotemGuard's InventoryC and InventoryF checks.
 * Copyright (C) 2026 Bram and contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 * https://github.com/Bram1903/TotemGuard/tree/48655b552acd33db01b0f60056fa2e96abacc5a5
 */
package cc.watchneko.checks.impl.packetorder;

import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.latency.CompensatedOpenWindow;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

@CheckData(name = "PacketOrderP", description = "Action in the client tick an inventory closed", experimental = true, setback = -1)
public final class PacketOrderP extends Check implements PacketCheck {
    private final CloseTickState state = new CloseTickState();
    private long lastTeleport;

    public PacketOrderP(PlayerData player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!canObserve()) {
            state.invalidate();
            return;
        }

        PacketTypeCommon type = event.getPacketType();
        if (event.isCancelled()) {
            state.onCancelled(type);
            return;
        }

        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            if (!player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
                state.onMovement();
            }
        } else if (type == PacketType.Play.Client.CLOSE_WINDOW) {
            state.onClose(new WrapperPlayClientCloseWindow(event).getWindowId());
        } else if (type == PacketType.Play.Client.CLICK_WINDOW) {
            state.onClick(new WrapperPlayClientClickWindow(event).getWindowId());
        } else if (type == PacketType.Play.Client.ATTACK) {
            onAttack("attack");
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            if (new WrapperPlayClientInteractEntity(event).getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                onAttack("attack");
            }
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
            if (action == DiggingAction.START_DIGGING || action == DiggingAction.CHANGE_DESTROY_DIRECTION
                    || action == DiggingAction.STAB) {
                onAttack("dig");
            }
        }
    }

    private void onAttack(String action) {
        state.onAttack(action, player.openWindow.mustBeClosed(), player.openWindow.getConfirmedServerClose());
    }

    /** Called once a native or translated client tick-end packet has been processed. */
    public void onClientTickEnd() {
        if (!canObserve()) {
            state.invalidate();
            return;
        }

        String violation = state.onTickEnd(player.openWindow.mustBeOpen(), player.openWindow.getConfirmedServerClose());
        if (violation != null) {
            flagAndAlert(violation);
        }
    }

    public void onUncertainTick() {
        state.invalidate();
    }

    public void beginInventoryMitigation() {
        state.beginMitigation();
    }

    public void endInventoryMitigation() {
        state.endMitigation();
    }

    private boolean canObserve() {
        if (lastTeleport != player.lastServerTeleport) {
            lastTeleport = player.lastServerTeleport;
            return false;
        }
        return isEnabled() && player.isExperimentalChecks() && !player.disableGrim && !isExemptPermission()
                && player.supportsEndTick() && player.gamemode != GameMode.SPECTATOR && !player.inVehicle()
                && player.getSetbackTeleportUtil().hasAcceptedSpawnTeleport
                && !player.getSetbackTeleportUtil().blockOffsets
                && player.getSetbackTeleportUtil().pendingTeleports.isEmpty()
                && !player.packetStateData.lastPacketWasTeleport;
    }

    /** Packet-thread state; evidence is emitted only after a complete, observable client tick. */
    static final class CloseTickState {
        private boolean ready;
        private boolean clientClosed;
        private boolean openAtTickEnd;
        private boolean movementSeen;
        private boolean uncertain;
        private int playerInventoryCloses;
        private int pendingMitigations;
        private long confirmedCloseAtTickEnd;
        private String violation;

        void onClose(int windowId) {
            if (!ready || pendingMitigations != 0) return;
            clientClosed = true;
            if (windowId == CompensatedOpenWindow.PLAYER_INVENTORY && ++playerInventoryCloses > 1) {
                record("multiple player inventory closes");
            }
        }

        void onClick(int windowId) {
            if (ready && pendingMitigations == 0 && clientClosed
                    && windowId == CompensatedOpenWindow.PLAYER_INVENTORY) {
                record("player inventory click after close");
            }
        }

        void onAttack(String action, boolean certainlyClosed, long confirmedServerClose) {
            if (!ready || pendingMitigations != 0) return;
            if (clientClosed) {
                record(action + " after client close");
            } else if (openAtTickEnd && certainlyClosed && confirmedServerClose > confirmedCloseAtTickEnd) {
                record(action + " after server close");
            }
        }

        void onMovement() {
            if (!ready || pendingMitigations != 0) return;
            if (movementSeen) uncertain = true;
            movementSeen = true;
        }

        void onCancelled(PacketTypeCommon type) {
            if (type == PacketType.Play.Client.CLOSE_WINDOW || type == PacketType.Play.Client.CLICK_WINDOW
                    || type == PacketType.Play.Client.ATTACK || type == PacketType.Play.Client.INTERACT_ENTITY
                    || type == PacketType.Play.Client.PLAYER_DIGGING || type == PacketType.Play.Client.CLIENT_TICK_END
                    || WrapperPlayClientPlayerFlying.isFlying(type)) {
                invalidate();
            }
        }

        String onTickEnd(boolean certainlyOpen, long confirmedServerClose) {
            String result = ready && !uncertain && pendingMitigations == 0 ? violation : null;
            clearTick();
            ready = pendingMitigations == 0;
            openAtTickEnd = certainlyOpen;
            confirmedCloseAtTickEnd = confirmedServerClose;
            return result;
        }

        void invalidate() {
            clearTick();
            ready = false;
            openAtTickEnd = false;
        }

        void beginMitigation() {
            pendingMitigations++;
            invalidate();
        }

        void endMitigation() {
            if (pendingMitigations > 0) pendingMitigations--;
            invalidate();
        }

        private void record(String detail) {
            if (violation == null) violation = detail;
        }

        private void clearTick() {
            clientClosed = false;
            movementSeen = false;
            uncertain = false;
            playerInventoryCloses = 0;
            violation = null;
        }
    }
}
