package cc.watchneko.checks.impl.analysis;

import cc.watchneko.analysis.aim.AimHeuristicGate;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.math.MathUtil;
import cc.watchneko.utils.math.Simplification;
import cc.watchneko.utils.math.Statistics;
import cc.watchneko.utils.math.Vec2f;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Smoothing detection from MX's {@code AimSmoothCheck}: over twenty sizeable rotations, the angle of the
 * delta vector should not stay numerically identical three times in a row. Perfectly repeated angles are
 * what an interpolated aim path looks like, because the interpolation divides the same total by the same
 * step count every time.
 *
 * <p>Adds global VL upstream, so it stays in the {@code Analysis} group. Upstream does not block attacks
 * for this one, and neither does this build.
 */
@CheckData(
        name = "AnalysisI",
        configName = "AnalysisI",
        description = "repeated delta-vector angles: interpolated aim path",
        decay = 0.05
)
public final class AnalysisI extends Check implements RotationCheck {

    private static final int SAMPLE_SIZE = 20;

    private final List<Double> stack = new ArrayList<>();

    private float lastAbsDeltaX;
    private float lastAbsDeltaY;
    private double lastRawAngle;

    public AnalysisI(final PlayerData player) {
        super(player);
    }

    @Override
    public void process(final RotationUpdate update) {
        if (AimHeuristicGate.blocks(player)) {
            return;
        }
        if (AimHeuristicGate.noPitchChange(update)) {
            return;
        }

        final Vec2f delta = AimHeuristicGate.referenceDelta(update);
        final float absDeltaX = Math.abs(Math.abs(update.getTo().getYaw()) - Math.abs(update.getFrom().getYaw()));
        final float absDeltaY = Math.abs(Math.abs(update.getTo().getPitch()) - Math.abs(update.getFrom().getPitch()));
        final double rawAngle = MathUtil.getAngleInDegrees(new Vec2f(delta.x(), delta.y()));

        if ((absDeltaY > 1.5 && absDeltaX > 0.32) || absDeltaX > 1.5) {
            stack.add(rawAngle % 90);
            this.lastAbsDeltaX = absDeltaX;
            this.lastAbsDeltaY = absDeltaY;
            this.lastRawAngle = rawAngle;
        }

        if (stack.size() >= SAMPLE_SIZE) {
            final List<Float> jiff = Statistics.getJiffDelta(stack, 1);
            float prev = 999;
            float prePrev = 999;
            int zeroRunIndex = -1;

            for (int i = 0; i < jiff.size(); i++) {
                final float value = jiff.get(i);
                if (value == 0.0 && prev == 0.0 && prePrev == 0) {
                    zeroRunIndex = i - 2;
                    break;
                }
                prePrev = prev;
                prev = value;
            }

            if (zeroRunIndex >= 0) {
                final String stackSummary = stack.stream()
                        .map(value -> String.valueOf(Simplification.scaleVal(value, 2)))
                        .collect(Collectors.joining(","));
                // Adds VL but does not block attacks upstream, so there is no mitigation here either.
                flagAndAlert("invalid smoothing"
                        + " rawAngle=" + Simplification.scaleVal(lastRawAngle, 2)
                        + " dX=" + Simplification.scaleVal(lastAbsDeltaX, 2)
                        + " dY=" + Simplification.scaleVal(lastAbsDeltaY, 2)
                        + " zeroRun@" + zeroRunIndex + "/" + jiff.size()
                        + " stack=[" + stackSummary + "]");
            }
            stack.clear();
        }
    }
}
