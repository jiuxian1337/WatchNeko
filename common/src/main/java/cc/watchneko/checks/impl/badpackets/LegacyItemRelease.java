package cc.watchneko.checks.impl.badpackets;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;

@CheckData(name = "LegacyItemRelease", description = "Repeated item releases from a known idle state",
        experimental = true, requiresExperimentalChecks = false, requiresExactPunishmentMatch = true, setback = -1)
public final class LegacyItemRelease extends Check implements PacketCheck {
    private final ItemUse use = new ItemUse();
    private volatile Settings settings;
    private Settings appliedSettings;
    private long lastTeleport;

    public LegacyItemRelease(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        settings = new Settings(config.getBooleanElse("LegacyItemRelease.enabled", true));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Settings current = settings;
        if (!current.enabled() || !isEnabled() || player.disableGrim || isExemptPermission()
                || player.getClientVersion().isNewerThan(ClientVersion.V_1_8)
                || PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9)
                || player.gamemode != GameMode.SURVIVAL && player.gamemode != GameMode.ADVENTURE
                || player.inVehicle() || player.packetStateData.lastPacketWasTeleport
                || player.getSetbackTeleportUtil().shouldBlockMovement()) {
            use.reset();
            return;
        }
        if (current != appliedSettings || lastTeleport != player.lastServerTeleport) {
            appliedSettings = current;
            lastTeleport = player.lastServerTeleport;
            use.reset();
        }
        if (event.isCancelled()) return;

        var type = event.getPacketType();
        if (isTickPacket(type)) {
            use.tick(player.packetStateData.isSlowedByUsingItem());
        } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT || type == PacketType.Play.Client.USE_ITEM) {
            // A legacy interaction can start item use even if the compensated stack is stale.
            use.possibleUse();
        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE || type == PacketType.Play.Client.CLICK_WINDOW
                || type == PacketType.Play.Client.CLOSE_WINDOW || type == PacketType.Play.Client.CLIENT_STATUS) {
            use.reset();
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
            if (action == DiggingAction.RELEASE_USE_ITEM && use.release()) {
                flagAndAlert("repeated release while idle");
            }
        }
    }

    private record Settings(boolean enabled) {}

    static final class ItemUse {
        private static final int REQUIRED_IDLE_TICKS = 5;
        private boolean possibleUse;
        private int idleTicks;
        private int unmatchedReleases;
        private long tick;
        private long lastUnmatched;

        void tick(boolean usingItem) {
            tick++;
            if (usingItem) possibleUse();
            if (!possibleUse && idleTicks < REQUIRED_IDLE_TICKS) idleTicks++;
        }

        void possibleUse() {
            possibleUse = true;
            unmatchedReleases = 0;
        }

        boolean release() {
            if (possibleUse) {
                possibleUse = false;
                idleTicks = REQUIRED_IDLE_TICKS;
                return false;
            }
            if (idleTicks < REQUIRED_IDLE_TICKS) return false;
            if (tick - lastUnmatched > 20) unmatchedReleases = 0;
            lastUnmatched = tick;
            if (++unmatchedReleases < 3) return false;
            unmatchedReleases = 0;
            return true;
        }

        void reset() {
            possibleUse = false;
            idleTicks = 0;
            unmatchedReleases = 0;
        }
    }
}
