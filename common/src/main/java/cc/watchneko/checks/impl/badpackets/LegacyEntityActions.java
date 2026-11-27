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
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;

@CheckData(name = "LegacyEntityActions", description = "Repeated opposite movement actions within a legacy client tick",
        experimental = true, requiresExperimentalChecks = false, requiresExactPunishmentMatch = true, setback = -1)
public final class LegacyEntityActions extends Check implements PacketCheck {
    private final Transitions transitions = new Transitions();
    private volatile Settings settings;
    private Settings appliedSettings;
    private long lastTeleport;

    public LegacyEntityActions(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        settings = new Settings(config.getBooleanElse("LegacyEntityActions.enabled", true));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Settings current = settings;
        if (!current.enabled() || !isEnabled() || player.disableGrim || isExemptPermission()
                || player.getClientVersion().isNewerThan(ClientVersion.V_1_8)
                || PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9)
                || player.inVehicle() || player.packetStateData.lastPacketWasTeleport
                || player.getSetbackTeleportUtil().shouldBlockMovement()) {
            transitions.reset();
            return;
        }
        if (current != appliedSettings || lastTeleport != player.lastServerTeleport) {
            appliedSettings = current;
            lastTeleport = player.lastServerTeleport;
            transitions.reset();
        }
        if (event.isCancelled()) return;
        var type = event.getPacketType();
        if (isTickPacket(type)) {
            if (transitions.finishTick()) flagAndAlert("opposite sprint or sneak actions in one tick");
        } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
            var action = new WrapperPlayClientEntityAction(event);
            if (action.getEntityId() != player.entityID) return;
            switch (action.getAction()) {
                case START_SPRINTING -> transitions.sprint(true);
                case STOP_SPRINTING -> transitions.sprint(false);
                case START_SNEAKING -> transitions.sneak(true);
                case STOP_SNEAKING -> transitions.sneak(false);
                default -> {
                }
            }
        }
    }

    private record Settings(boolean enabled) {}

    static final class Transitions {
        private int sprint = -1;
        private int sneak = -1;
        private boolean conflicting;
        private int suspiciousTicks;
        private int quietTicks;

        void sprint(boolean enabled) {
            int next = enabled ? 1 : 0;
            conflicting |= sprint != -1 && sprint != next;
            sprint = next;
        }

        void sneak(boolean enabled) {
            int next = enabled ? 1 : 0;
            conflicting |= sneak != -1 && sneak != next;
            sneak = next;
        }

        boolean finishTick() {
            boolean report = false;
            if (conflicting) {
                quietTicks = 0;
                if (++suspiciousTicks >= 3) {
                    suspiciousTicks = 0;
                    report = true;
                }
            } else if (suspiciousTicks != 0 && ++quietTicks >= 20) {
                suspiciousTicks = 0;
            }
            sprint = -1;
            sneak = -1;
            conflicting = false;
            return report;
        }

        void reset() {
            sprint = -1;
            sneak = -1;
            conflicting = false;
            suspiciousTicks = 0;
            quietTicks = 0;
        }
    }
}
