package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.analysis.aim.AimHeuristicGate;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.math.Simplification;
import cc.watchneko.utils.math.Statistics;
import cc.watchneko.utils.math.Vec2;
import cc.watchneko.utils.math.Vec2f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The "basic" heuristic from MX's {@code AimBasicCheck}: three windowed statistics over the last ten
 * rotations, all fed from the same samples.
 *
 * <p>The three verdicts do not carry the same weight upstream, and they are not collapsed here. The
 * randomizer flaw is a direct flag, because it is a value no real client produces. The component and
 * interpolation scores are buffers that fill over many windows; upstream punishes them without adding
 * global VL, and {@link AnalysisC} / {@link AnalysisD} exist so that distinction survives the split into
 * separate checks: each has its own punishments entry, its own suppress window, and its own mitigation
 * duration.
 */
@CheckData(
        name = "AnalysisB",
        configName = "AnalysisB",
        description = "windowed rotation statistics: randomizer flaw, component score, interpolation",
        decay = 0.05
)
public final class AnalysisB extends Check implements RotationCheck {

    private final List<Vec2> rawRotations = new ArrayList<>();
    private final Set<Double> yaws = new HashSet<>();

    private int streak;
    private float componentVl;
    private float interpolationVl;
    private String componentReason = "";

    private float randomizerFlawAddVl = 25;
    private float componentVlLimit = 400;
    private float componentVlFade = 5;
    private float interpolationVlLimit = 400;
    private float interpolationVlFade = 5;

    public AnalysisB(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        randomizerFlawAddVl = (float) config.getDoubleElse("AnalysisB.randomizer-flaw-add-vl", randomizerFlawAddVl);
        componentVlLimit = (float) config.getDoubleElse("AnalysisB.component-vl-limit", componentVlLimit);
        componentVlFade = (float) config.getDoubleElse("AnalysisB.component-vl-fade", componentVlFade);
        interpolationVlLimit = (float) config.getDoubleElse("AnalysisB.interpolation-vl-limit", interpolationVlLimit);
        interpolationVlFade = (float) config.getDoubleElse("AnalysisB.interpolation-vl-fade", interpolationVlFade);
    }

    @Override
    public void process(final RotationUpdate update) {
        if (AimHeuristicGate.blocks(player)) {
            return;
        }
        if (AimHeuristicGate.noPitchChange(update)) {
            return;
        }

        rawRotations.add(new Vec2(update.getTo().getYaw(), update.getTo().getPitch()));

        if (randomizerFlawAddVl > 0) {
            final Vec2f delta = AimHeuristicGate.referenceDelta(update);
            final float pitch = update.getTo().getPitch();
            if ((delta.y() > 1.5f || delta.x() > 3.0f) && (pitch == 0 || pitch % 0.01f == 0)) {
                // Adds VL but does not block attacks upstream, so there is no mitigation here either.
                flagAndAlert("randomizer flaw");
            }
        }

        if (rawRotations.size() >= 10) {
            checkDefaultAim();
        }
    }

    private void checkDefaultAim() {
        final List<Vec2> rotations = rawRotations;

        yaws.clear();
        double oldYaw = rotations.get(0).x();
        for (Vec2 rotation : rotations) {
            yaws.add(Math.abs(rotation.x() - oldYaw));
            oldYaw = rotation.x();
        }

        double oldYawResult = rotations.get(0).x();
        double oldPitchResult = rotations.get(0).y();
        double oldYawChange = Math.abs(rotations.get(0).x() - oldYawResult);
        double yawChangeFirst = Math.abs(rotations.get(0).x() - rotations.get(1).x());

        int machineKnownMovement = 0;
        int constantRotations = 0;
        int gcd = 0;
        int aggressivePatternI = 0;
        int aggressivePatternD = 0;
        int aggressivePatternI2 = 0;
        int aggressivePatternD2 = 0;
        int robotizedAmount = 0;
        int aggressiveAim = 0;
        int infinitives = 0;

        for (Vec2 rotation : rotations) {
            double yawChange = Math.abs(rotation.x() - oldYawResult);
            double pitchChange = Math.abs(rotation.y() - oldPitchResult);
            double robotized = Math.abs(yawChange - yawChangeFirst);
            double diffBetweenYawChanges = yawChange - oldYawChange;
            double interpolation;

            if (robotized < 2 && yawChange > 2.5) robotizedAmount++;
            if (robotized < 0.99 && yawChange > 4) machineKnownMovement++;
            if (robotized < 0.02 && yawChange > 3) constantRotations++;
            if (robotized < 2 && yawChange > 3) aggressiveAim++;

            interpolation = Simplification.scaleVal(yawChange / robotized, 2);
            if (Double.isInfinite(interpolation) && yawChange > 0) {
                infinitives++;
                if (infinitives > 1 && yawChange < 0.4) {
                    infinitives--;
                }
            }

            if (yawChange == 0.1 || pitchChange == 0.1) gcd++;
            if (yawChange == 0.01 || pitchChange == 0.01) gcd++;
            if (diffBetweenYawChanges > 0.01 && diffBetweenYawChanges < 2) aggressivePatternI++;
            if (diffBetweenYawChanges < -0.01 && diffBetweenYawChanges > -2) aggressivePatternD++;
            if (diffBetweenYawChanges > 2) aggressivePatternI2++;
            if (diffBetweenYawChanges < -2) aggressivePatternD2++;

            oldYawResult = rotation.x();
            oldPitchResult = rotation.y();
            oldYawChange = yawChange;
        }

        final int sensitivity = player.calculateSensitivity();
        if (sensitivity > 65) {
            if (robotizedAmount > 8) addComponentVl("heuristic(sync)");
            if (aggressiveAim > 8) addComponentVl("heuristic(aggressive)");
            if (machineKnownMovement > 7) addComponentVl("heuristic(aim)");
            if (constantRotations > 3) addComponentVl("heuristic(constant)");
        } else {
            if (machineKnownMovement > 8) addComponentVl("heuristic(aim)");
            if (constantRotations > 6) addComponentVl("heuristic(constant)");
        }

        if (infinitives > 1 && Math.abs(Statistics.getAverage(yaws)) > 3.2) {
            interpolationVl += 55;
        }

        if (gcd > 0) addComponentVl("pattern(gcd)");
        if (aggressivePatternI > 3 && aggressivePatternD > 3) {
            interpolationVl += 25;
        }
        if (aggressivePatternI2 > 3 && aggressivePatternD2 > 3
                && (aggressivePatternI2 + aggressivePatternD2) > 8) {
            streak++;
            if (streak > 2) addComponentVl("pattern(snap)");
        } else {
            streak = 0;
        }

        if (componentVl > componentVlLimit) {
            final AnalysisC component = player.checkManager.getCheck(AnalysisC.class);
            if (component != null && component.flagAndAlert(componentReason) && component.mitigateDamageMs > 0) {
                player.mitigateDamage(component.mitigateDamageMs);
            }
            componentVl = 360;
        }
        if (interpolationVl > interpolationVlLimit) {
            final AnalysisD interpolation = player.checkManager.getCheck(AnalysisD.class);
            if (interpolation != null && interpolation.flagAndAlert("interpolation")
                    && interpolation.mitigateDamageMs > 0) {
                player.mitigateDamage(interpolation.mitigateDamageMs);
            }
            interpolationVl -= 65;
        }

        if (componentVl > 0) componentVl -= componentVlFade;
        if (componentVl > 400) componentVl -= 10;
        if (interpolationVl > 0) interpolationVl -= interpolationVlFade;
        if (interpolationVl > 380) interpolationVl -= 10;

        rawRotations.clear();
    }

    /**
     * Upstream scores each reason differently: 1000 for a shared divisor (a value no mouse produces),
     * 125 for sync, 100 for aim, 65 for constant, 55 for snap and 50 for aggressive. Those weights are
     * the detection; only the report is uniform here.
     */
    private void addComponentVl(final String reason) {
        componentReason = reason;
        componentVl += switch (reason) {
            case "pattern(gcd)" -> 1000;
            case "heuristic(sync)" -> 125;
            case "heuristic(aggressive)" -> 50;
            case "heuristic(aim)" -> 100;
            case "heuristic(constant)" -> 65;
            default -> 55;
        };
    }
}
