package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.analysis.aim.AimHeuristicGate;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.CombatPackets;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.lists.EvictingList;
import cc.watchneko.utils.math.MathUtil;
import cc.watchneko.utils.math.Simplification;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Scores isolated large yaw deltas near an attack. Position changes without a look update
 * supply a zero yaw sample through {@link RotationCheck#onNoRotation()}.
 */
@CheckData(
        name = "AnalysisH",
        configName = "AnalysisH",
        description = "isolated large yaw turn alongside an attack",
        decay = 0.05
)
public final class AnalysisH extends Check implements RotationCheck {

    private static final double HUGE_ROTATION = 35;
    private static final double SMALL_NEIGHBOUR = 1.2;
    private static final long MAX_GAP = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long COMBAT_IDLE = TimeUnit.MILLISECONDS.toNanos(3500);

    private final Window window = new Window();
    private volatile Settings settings;

    public AnalysisH(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        settings = new Settings((float) config.getDoubleElse("AnalysisH.buffer", 3.0),
                config.getIntElse("AnalysisH.ticks-to-reset", 2500));
    }

    @Override
    public void process(final RotationUpdate update) {
        if (AimHeuristicGate.dropRotation(player, update) || !canSample()) {
            window.reset();
            return;
        }
        report(window.rotation(update, player.rotationSequence.position(), System.nanoTime(), settings));
    }

    @Override
    public void onNoRotation() {
        if (AimHeuristicGate.blocksNoRotation(player) || !canSample()) {
            window.reset();
            return;
        }
        report(window.sample(0, player.rotationSequence.position(), System.nanoTime(), settings));
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.isCancelled() || CombatPackets.attackTarget(event) < 0) return;
        if (AimHeuristicGate.blocksNoRotation(player) || !canSample() || player.inVehicle()) {
            window.reset();
            return;
        }
        report(window.attack(player.rotationSequence.position(), System.nanoTime(), settings));
    }

    private boolean canSample() {
        return isEnabled() && !isExemptPermission() && !player.disableGrim;
    }

    private void report(Candidate match) {
        if (match != null) flagAndAlert("factor analysis (" + match.centre().yaw() + "/"
                + Simplification.scaleVal(match.neighbours(), 2) + ")");
    }

    record Settings(float bufferLimit, int resetAfterTicks) {}

    record Candidate(Sample centre, double neighbours) {}

    private record Sample(double yaw, long sequence, long time) {}

    static final class Window {
        private final List<Sample> samples = new EvictingList<>(3);
        private Candidate candidate;
        private long attackSequence = -1;
        private long attackTime;
        private double lastHash;
        private float buffer;
        private int ticksToReset;

        Candidate rotation(RotationUpdate update, long sequence, long now, Settings settings) {
            double yaw = Math.abs(MathUtil.getAngleDifference(update.getTo().getYaw(), update.getFrom().getYaw()));
            return sample(yaw, sequence, now, settings);
        }

        Candidate sample(double yaw, long sequence, long now, Settings settings) {
            if (!samples.isEmpty()) {
                Sample previous = samples.get(samples.size() - 1);
                if (now - previous.time() > MAX_GAP) clearSamples();
                else if (yaw == 0 && previous.yaw() == 0) return null;
            }
            samples.add(new Sample(Simplification.scaleVal(yaw, 2), sequence, now));
            candidate = null;
            if (samples.size() != 3) return null;

            Sample left = samples.get(0);
            Sample centre = samples.get(1);
            double right = samples.get(2).yaw();
            double hash = left.yaw() + centre.yaw() + right;
            if (hash == lastHash) return null;
            lastHash = hash;

            if (centre.yaw() <= HUGE_ROTATION) {
                if (++ticksToReset >= settings.resetAfterTicks()) resetBuffer();
                return null;
            }
            boolean isolated = left.yaw() < SMALL_NEIGHBOUR && right < SMALL_NEIGHBOUR;
            boolean repeatedLarge = (left.yaw() == centre.yaw() || left.yaw() == right || centre.yaw() == right)
                    && Math.max(centre.yaw(), Math.max(left.yaw(), right)) > 70
                    && Math.min(left.yaw(), right) < SMALL_NEIGHBOUR;
            if (isolated || repeatedLarge) {
                candidate = new Candidate(centre, left.yaw() + right);
            }
            return score(now, settings);
        }

        Candidate attack(long sequence, long now, Settings settings) {
            if (attackSequence >= 0 && now - attackTime > COMBAT_IDLE) resetBuffer();
            attackSequence = sequence;
            attackTime = now;
            return score(now, settings);
        }

        private Candidate score(long now, Settings settings) {
            if (candidate == null || attackSequence < 0) return null;
            Sample centre = candidate.centre();
            // The attack can precede the look packet or follow it before the next look update.
            if (attackSequence < centre.sequence() - 1 || attackSequence > centre.sequence()
                    || Math.abs(attackTime - centre.time()) > MAX_GAP || now - centre.time() > MAX_GAP) return null;

            Candidate match = candidate;
            candidate = null;
            double yaw = centre.yaw();
            buffer += yaw > 160 ? 3 : yaw < 60 ? 1 : 2;
            if (buffer < settings.bufferLimit()) return null;
            buffer = settings.bufferLimit() - 1;
            return match;
        }

        void reset() {
            clearSamples();
            resetBuffer();
            attackSequence = -1;
        }

        private void clearSamples() {
            samples.clear();
            candidate = null;
            lastHash = 0;
        }

        private void resetBuffer() {
            ticksToReset = 0;
            buffer = 0;
        }
    }
}
