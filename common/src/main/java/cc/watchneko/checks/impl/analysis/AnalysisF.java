package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.analysis.aim.AimHeuristicGate;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.impl.aim.processor.AimProcessor;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.data.Pair;
import cc.watchneko.utils.math.Statistics;

import java.util.ArrayList;
import java.util.List;

/**
 * Inconsistent-rotation detection from MX's {@code AimInconsistentCheck}: over sixty samples, a rotation
 * stream with almost no outliers and almost no repeated values is not a hand.
 *
 * <p>The delta is taken the way the rest of the heuristic family takes it, {@code ||to| - |from||}, not as
 * {@code |to - from|}. The two differ by a factor of two whenever the yaw crosses zero, and the buffer
 * thresholds below were tuned against the former.
 *
 * <p>Punishes without adding global VL upstream, so it lives in the {@code NoPenalty} group and suppresses
 * outgoing damage for four seconds instead.
 */
@CheckData(
        name = "AnalysisF",
        configName = "AnalysisF",
        description = "inconsistent rotation distribution: too few outliers or duplicates",
        decay = 0.05
)
public final class AnalysisF extends Check implements RotationCheck {

    private final List<Float> samplesYaw = new ArrayList<>();
    private final List<Float> samplesPitch = new ArrayList<>();

    private float lastDeltaYaw;
    private float lastDeltaPitch;
    private float buffer;

    private float bufferLimit = 2;
    private long mitigateDamageMs = 4000L;

    public AnalysisF(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        bufferLimit = (float) config.getDoubleElse("AnalysisF.buffer", bufferLimit);
        mitigateDamageMs = Math.max(0L, (long) config.getDoubleElse("AnalysisF.mitigate-damage-ms", mitigateDamageMs));
    }

    @Override
    public void process(final RotationUpdate update) {
        if (AimHeuristicGate.blocks(player)) {
            return;
        }
        if (AimHeuristicGate.noPitchChange(update)) {
            return;
        }

        final float deltaYaw = Math.abs(Math.abs(update.getTo().getYaw()) - Math.abs(update.getFrom().getYaw()));
        final float deltaPitch = Math.abs(Math.abs(update.getTo().getPitch()) - Math.abs(update.getFrom().getPitch()));

        final AimProcessor aimProcessor = player.checkManager.getRotationCheck(AimProcessor.class);
        final int totalSensitivityClient = aimProcessor != null ? aimProcessor.totalSensitivityClient : 0;
        final int sensitivity = player.calculateSensitivity();
        if (sensitivity < 75 || sensitivity > 175
                || totalSensitivityClient < 75 || totalSensitivityClient > 170) {
            return;
        }

        final float differenceYaw = Math.abs(deltaYaw - lastDeltaYaw);
        final float differencePitch = Math.abs(deltaPitch - lastDeltaPitch);

        final float joltX = Math.abs(deltaYaw - differenceYaw);
        final float joltY = Math.abs(deltaPitch - differencePitch);

        samplesYaw.add((float) Statistics.roundToPlace(joltX, 2));
        samplesPitch.add((float) Statistics.roundToPlace(joltY, 2));

        if (samplesYaw.size() + samplesPitch.size() >= 60) {
            if (!(joltX == 0.0 || joltY == 0.0)) {
                final Pair<List<Double>, List<Double>> outliersYaw = Statistics.getOutliers(samplesYaw);
                final Pair<List<Double>, List<Double>> outliersPitch = Statistics.getOutliers(samplesPitch);

                final int duplicatesX = Statistics.getDuplicates(samplesYaw);
                final int duplicatesY = Statistics.getDuplicates(samplesPitch);
                final int duplicatesSum = duplicatesX + duplicatesY;
                final int outliersX = outliersYaw.first().size() + outliersYaw.second().size();
                final int outliersY = outliersPitch.first().size() + outliersPitch.second().size();

                // The buffer is compared before it is incremented, so a limit of 2 fires on the third
                // suspicious window, not the second. The same holds for the zero-value branch.
                if ((duplicatesSum <= 3 && outliersX < 10 && outliersY < 7) && buffer++ >= bufferLimit) {
                    if (flagAndAlert("inconsistent rotations (" + outliersX + ", " + outliersY
                            + ", duplicates: " + duplicatesSum + ") [too low values]") && mitigateDamageMs > 0) {
                        player.mitigateDamage(mitigateDamageMs);
                    }
                } else if (((outliersX == 0 || outliersY == 0) && (outliersX > 1 || outliersY > 1)
                        && duplicatesSum <= 3) && buffer++ >= bufferLimit) {
                    if (flagAndAlert("inconsistent rotations (" + outliersX + ", " + outliersY
                            + ", duplicates: " + duplicatesSum + ") [zero value]") && mitigateDamageMs > 0) {
                        player.mitigateDamage(mitigateDamageMs);
                    }
                } else {
                    // No floor: a player who has been clean for a while earns a real margin before the
                    // next suspicious window, instead of restarting from zero.
                    buffer -= 0.5f;
                }
            }
            samplesYaw.clear();
            samplesPitch.clear();
        }

        this.lastDeltaYaw = deltaYaw;
        this.lastDeltaPitch = deltaPitch;
    }
}
