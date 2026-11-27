package cc.watchneko.checks.impl.breaking;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.BlockBreak;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.util.Vector3i;

@CheckData(name = "LegacyFastBreak", description = "Repeated premature legacy block dig transitions",
        experimental = true, requiresExperimentalChecks = false, requiresExactPunishmentMatch = true, setback = -1)
public final class LegacyFastBreak extends Check implements PacketCheck {
    private final DigTiming timing = new DigTiming();
    private volatile Settings settings;
    private Settings appliedSettings;
    private long lastTeleport;

    public LegacyFastBreak(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        settings = new Settings(config.getBooleanElse("LegacyFastBreak.enabled", true));
    }

    public boolean isConfiguredEnabled() {
        return settings.enabled() && isEnabled();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!updateContext() || event.isCancelled()) return;
        if (isTickPacket(event.getPacketType())) {
            timing.tick();
        } else if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            timing.reset();
        }
    }

    public void onDig(BlockBreak block, boolean accepted, boolean nonInstant) {
        if (!updateContext()) return;
        if (!accepted) {
            timing.reset();
            return;
        }
        boolean report = switch (block.action) {
            case START_DIGGING -> timing.start(block.position, nonInstant);
            case FINISHED_DIGGING -> timing.finish(block.position, nonInstant);
            default -> {
                timing.reset();
                yield false;
            }
        };
        if (report) {
            flagAndAlert(block.action == DiggingAction.START_DIGGING
                    ? "restart immediately after completion" : "started and finished in the same tick");
        }
    }

    private boolean updateContext() {
        Settings current = settings;
        if (current != appliedSettings || lastTeleport != player.lastServerTeleport) {
            appliedSettings = current;
            lastTeleport = player.lastServerTeleport;
            timing.reset();
        }
        if (!isConfiguredEnabled() || player.disableGrim || isExemptPermission()
                || player.getClientVersion().isNewerThan(ClientVersion.V_1_8) || player.gamemode != GameMode.SURVIVAL
                || player.inVehicle() || player.packetStateData.lastPacketWasTeleport
                || player.getSetbackTeleportUtil().shouldBlockMovement()) {
            timing.reset();
            return false;
        }
        return true;
    }

    private record Settings(boolean enabled) {}

    static final class DigTiming {
        private Vector3i started;
        private Vector3i finished;
        private long tick;
        private long startTick;
        private long finishTick;
        private int sameTickFinishes;
        private int rapidRestarts;

        void tick() {
            tick++;
        }

        boolean start(Vector3i block, boolean nonInstant) {
            if (!nonInstant) {
                reset();
                return false;
            }
            boolean shortDelay = finished != null && !finished.equals(block) && tick - finishTick <= 1;
            finished = null;
            if (!block.equals(started)) {
                started = block;
                startTick = tick;
            }
            rapidRestarts = shortDelay ? rapidRestarts + 1 : Math.max(0, rapidRestarts - 1);
            if (rapidRestarts < 3) return false;
            rapidRestarts = 0;
            return true;
        }

        boolean finish(Vector3i block, boolean nonInstant) {
            if (!nonInstant || !block.equals(started)) {
                reset();
                return false;
            }
            started = null;
            finished = block;
            finishTick = tick;
            sameTickFinishes = tick == startTick ? sameTickFinishes + 1 : Math.max(0, sameTickFinishes - 1);
            if (sameTickFinishes < 3) return false;
            sameTickFinishes = 0;
            return true;
        }

        void reset() {
            started = null;
            finished = null;
            sameTickFinishes = 0;
            rapidRestarts = 0;
        }
    }
}
