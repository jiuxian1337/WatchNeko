package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.analysis.aim.AimHeuristicGate;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.lists.EvictingList;
import cc.watchneko.utils.math.Simplification;
import cc.watchneko.utils.math.Statistics;

import java.util.List;

/**
 * Factor analysis from MX's {@code AimFactorCheck}: three consecutive yaw deltas around one huge rotation,
 * where the middle value is large but the neighbours are not — a snap onto a target rather than a turn.
 *
 * <p>This is the only heuristic that consumes ticks with no rotation. A player who aims, holds still,
 * then flicks produces a triple whose middle element is a zero, and without the no-rotation tick the two
 * rotations would look adjacent. Upstream emits a synthetic zero-rotation event for that; this build
 * overrides {@link RotationCheck#onNoRotation()} to get the same input.
 */
@CheckData(
        name = "AnalysisH",
        configName = "AnalysisH",
        description = "isolated large rotation between two small ones",
        decay = 0.05
)
public final class AnalysisH extends Check implements RotationCheck {

    private static final double HUGE_ROTATION = 35;
    private static final double SMALL_NEIGHBOUR = 1.2;

    private final List<Double> stack = new EvictingList<>(3);

    private boolean lastWasNoRotation;
    private double lastHash;
    private float buffer;
    private int ticksToReset;

    private float bufferLimit = 3.0f;
    private int resetAfterTicks = 2500;

    public AnalysisH(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        bufferLimit = (float) config.getDoubleElse("AnalysisH.buffer", bufferLimit);
        resetAfterTicks = config.getIntElse("AnalysisH.ticks-to-reset", resetAfterTicks);
    }

    @Override
    public void process(final RotationUpdate update) {
        if (AimHeuristicGate.blocks(player)) {
            return;
        }
        if (AimHeuristicGate.noPitchChange(update)) {
            // A rotation that only moved the yaw is treated as no rotation at all, exactly as upstream does.
            noRotation();
            return;
        }
        final float deltaYaw = Math.abs(Math.abs(update.getTo().getYaw()) - Math.abs(update.getFrom().getYaw()));
        stack.add(Simplification.scaleVal(deltaYaw, 2));
        check();
        lastWasNoRotation = false;
    }

    @Override
    public void onNoRotation() {
        if (AimHeuristicGate.blocksNoRotation(player)) {
            return;
        }
        noRotation();
    }

    private void noRotation() {
        if (!lastWasNoRotation) stack.add(0.0);
        check();
        lastWasNoRotation = true;
    }

    private void check() {
        if (stack.size() != 3) return;

        double hash = stack.get(0) + stack.get(1) + stack.get(2);
        if (hash == lastHash) return;

        double centre = stack.get(1);
        boolean hugeRotation = centre > HUGE_ROTATION;

        if (hugeRotation && centre != 360.0f) {
            boolean invalid = (stack.get(0) < SMALL_NEIGHBOUR && stack.get(2) < SMALL_NEIGHBOUR)
                    || (stack.get(0) > 55 && stack.get(1) < 2 && stack.get(2) > 55)
                    || Statistics.getMax(stack) > 70 && Statistics.getMin(stack) < SMALL_NEIGHBOUR
                    && Statistics.getDistinct(stack) != 3;

            if (invalid) {
                float localVl = (centre > 160) ? 3 : (centre < 60) ? 1 : 2;
                buffer += localVl;
                if (buffer >= bufferLimit) {
                    // Adds VL but does not block attacks upstream, so there is no mitigation here either.
                    flagAndAlert("factor analysis (" + centre + "/"
                            + Simplification.scaleVal(stack.get(0) + stack.get(2), 2) + ")");
                    buffer = bufferLimit - 1;
                }
            }
        } else {
            ticksToReset++;
            if (ticksToReset >= resetAfterTicks) {
                ticksToReset = 0;
                buffer = 0;
            }
        }
        lastHash = hash;
    }
}
