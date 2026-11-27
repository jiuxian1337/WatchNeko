package cc.watchneko.checks.impl.autoclicker;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.PacketCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.CombatPackets;
import cc.watchneko.utils.anticheat.update.BlockBreak;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.util.Vector3i;

import java.util.Locale;

@CheckData(name = "AutoclickerD", description = "Repeated starts and cancellations on the same block",
        experimental = true, requiresExperimentalChecks = false, requiresExactPunishmentMatch = true, setback = -1)
public final class AutoclickerD extends Check implements PacketCheck {
    private final MiningCadence mining = new MiningCadence();
    private volatile Settings settings;
    private Settings appliedSettings;
    private long lastTeleport;

    public AutoclickerD(PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(ConfigManager config) {
        settings = new Settings(config.getBooleanElse("AutoclickerD.enabled", true));
    }

    public boolean isConfiguredEnabled() {
        return settings.enabled() && isEnabled();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!updateContext() || event.isCancelled()) return;
        var type = event.getPacketType();
        if (isTickPacket(type)) {
            mining.tick();
        } else if (CombatPackets.isMainHandSwing(event)) {
            mining.swing();
        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE || type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
                || type == PacketType.Play.Client.USE_ITEM || type == PacketType.Play.Client.CLICK_WINDOW
                || CombatPackets.attackTarget(event) >= 0) {
            mining.reset();
        }
    }

    public void onDig(BlockBreak block, boolean accepted, boolean nonInstant, long now) {
        if (!updateContext()) return;
        if (!accepted || !player.isTickingReliablyFor(3)) {
            mining.reset();
            return;
        }
        int blockState = block.block.getGlobalId();
        switch (block.action) {
            case START_DIGGING -> {
                Match match = mining.start(block.position, blockState, nonInstant, now);
                if (match != null) {
                    flagAndAlert(String.format(Locale.ROOT, "%s, mean=%.1fms, silent=%d",
                            match.pattern(), match.mean(), match.silent()));
                }
            }
            case CANCELLED_DIGGING -> mining.abort(block.position, blockState);
            case FINISHED_DIGGING -> mining.reset();
            default -> {
            }
        }
    }

    private boolean updateContext() {
        Settings current = settings;
        if (current != appliedSettings || lastTeleport != player.lastServerTeleport) {
            appliedSettings = current;
            lastTeleport = player.lastServerTeleport;
            mining.reset();
        }
        if (!isConfiguredEnabled() || player.disableGrim || isExemptPermission() || player.gamemode != GameMode.SURVIVAL
                || player.inVehicle() || player.packetStateData.lastPacketWasTeleport) {
            mining.reset();
            return false;
        }
        return true;
    }

    private record Settings(boolean enabled) {}

    record Match(String pattern, double mean, int silent) {}

    static final class MiningCadence {
        private static final int WINDOW_SIZE = 24;
        private Vector3i position;
        private int blockState;
        private long startNanos;
        private long tick;
        private long swingTick = -1;
        private long abortTick;
        private boolean aborted;
        private boolean swung;
        private int intervals;
        private int silent;
        private int matchingWindows;
        private String previousPattern;
        private double sum;
        private double squares;

        void tick() {
            tick++;
        }

        void swing() {
            swingTick = tick;
            if (position != null && (!aborted || abortTick == tick)) swung = true;
        }

        Match start(Vector3i target, int state, boolean nonInstant, long now) {
            if (!nonInstant) {
                reset();
                return null;
            }
            double interval = (now - startNanos) / 1_000_000.0;
            Match match = null;
            if (aborted && target.equals(position) && state == blockState && interval >= 20 && interval <= 250) {
                match = sample(interval, !swung && abortTick < tick);
            } else {
                reset();
            }
            position = target;
            blockState = state;
            startNanos = now;
            swung = swingTick == tick;
            aborted = false;
            return match;
        }

        void abort(Vector3i target, int state) {
            if (position == null || aborted || !position.equals(target) || blockState != state) {
                reset();
                return;
            }
            aborted = true;
            abortTick = tick;
        }

        private Match sample(double interval, boolean missingSwing) {
            if (missingSwing) silent++;
            sum += interval;
            squares += interval * interval;
            if (++intervals < WINDOW_SIZE) return null;
            double mean = sum / intervals;
            double deviation = ClickIntervals.deviation(sum, squares, intervals);
            String pattern = null;
            if (mean <= 120 && silent >= WINDOW_SIZE - 2) {
                pattern = "missing-swings";
            } else if (mean <= 105 && deviation < 4) {
                pattern = "regular-aborts";
            }
            Match match = pattern == null ? null : new Match(pattern, mean, silent);
            clearWindow();
            if (pattern == null || !pattern.equals(previousPattern)) matchingWindows = 0;
            previousPattern = pattern;
            if (match == null || ++matchingWindows < 2) return null;
            matchingWindows = 0;
            return match;
        }

        void reset() {
            position = null;
            aborted = false;
            matchingWindows = 0;
            previousPattern = null;
            clearWindow();
        }

        private void clearWindow() {
            intervals = 0;
            silent = 0;
            sum = 0;
            squares = 0;
        }
    }
}
