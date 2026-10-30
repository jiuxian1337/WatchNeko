package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.analysis.aim.AimHeuristicGate;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.math.Statistics;
import cc.watchneko.utils.math.Vec2f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The statistics family from MX's {@code AimStatisticsCheck}: four verdicts over a window of 25 rotations.
 *
 * <p>The four do not carry the same weight upstream, and they are not collapsed here. IQR, z-factor and
 * bot-pattern have their own buffers and their own attack-block durations; improbable is the accumulator
 * over the first three. IQR and z-factor are separate checks ({@link AnalysisK}, {@link AnalysisL}) so that
 * each keeps its own punishments entry and mitigation window; bot-pattern and improbable add VL, so they
 * stay here.
 */
@CheckData(
        name = "AnalysisJ",
        configName = "AnalysisJ",
        description = "windowed rotation statistics: IQR, z-factor, bot pattern, improbable",
        decay = 0.05
)
public final class AnalysisJ extends Check implements RotationCheck {

    private static final int BUFFER_SLOTS = 16;
    private static final int WINDOW_SIZE = 25;

    private final List<Vec2f> rawRotations = new ArrayList<>();
    private final float[] localBuffer = new float[BUFFER_SLOTS];

    private float iqrVlLimit = 11.0f;
    private float zFactorVlLimit = 7.0f;
    private float improbableVlLimit = 15.0f;

    public AnalysisJ(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        iqrVlLimit = (float) config.getDoubleElse("AnalysisJ.iqr-vl-limit", iqrVlLimit);
        zFactorVlLimit = (float) config.getDoubleElse("AnalysisJ.zfactor-vl-limit", zFactorVlLimit);
        improbableVlLimit = (float) config.getDoubleElse("AnalysisJ.improbable-vl-limit", improbableVlLimit);
    }

    @Override
    public void process(final RotationUpdate update) {
        if (AimHeuristicGate.dropRotation(player, update)) {
            return;
        }
        if (!player.actionManager.hasAttackedSince(3500L)) {
            return;
        }

        rawRotations.add(update.getDelta());
        if (rawRotations.size() >= WINDOW_SIZE) {
            checkRaw();
        }
    }

    private void checkRaw() {
        float total = 0;
        {
            final List<Float> x = new ArrayList<>();
            final List<Float> y = new ArrayList<>();
            for (Vec2f vec : rawRotations) {
                x.add(vec.x());
                y.add(vec.y());
            }

            final List<Double> zFactorYaw = Statistics.getZScoreOutliers(x, 2.0);
            final List<Float> jiffYaw = Statistics.getJiffDelta(x, 5);
            final List<Float> jiffPitch = Statistics.getJiffDelta(y, 5);
            final List<Float> jiffOmni = new ArrayList<>();
            for (int i = 0; i < Math.min(jiffYaw.size(), jiffPitch.size()); i++) {
                jiffOmni.add(jiffYaw.get(i) / jiffPitch.get(i));
            }

            // IQR verdict: owned by AnalysisK.
            {
                int infs = 0;
                for (float value : jiffOmni) {
                    if (Float.isInfinite(value)) infs++;
                }
                final double iqr = Statistics.getIQR(jiffOmni);
                if (iqr > 12.5 && iqr < 96 && infs > 0) {
                    increaseBuffer(8, (iqr > 20) ? 1.4f : 0.8f);
                    if (localBuffer[8] > iqrVlLimit) {
                        final AnalysisK iqrCheck = player.checkManager.getCheck(AnalysisK.class);
                        if (iqrCheck != null && iqrCheck.flagAndAlert("IQR " + iqr) && iqrCheck.mitigateDamageMs > 0) {
                            player.mitigateDamage(iqrCheck.mitigateDamageMs);
                        }
                        localBuffer[8] = iqrVlLimit - 2f;
                    }
                } else if (iqr < 13 || infs == 0) {
                    increaseBuffer(8, iqr < 7 ? -5.0f : -3.5f);
                }
            }

            // Kolmogorov-Smirnov verdict, one of the inputs to the improbable accumulator.
            final double kTest = Statistics.kolmogorovSmirnovTest(Statistics.getJiffDelta(x, 6), Function.identity());
            if (kTest > 10 && Math.abs(Statistics.getAverage(x)) < 13) {
                total++;
            }

            // Bot pattern: adds VL, so it stays on this check.
            int jiffPatterns = 0;
            for (int i = 0; i < jiffYaw.size(); i++) {
                final float value = jiffYaw.get(i);
                if (!String.valueOf(value).contains("E") || value == 0) continue;
                for (int r = 0; r < jiffYaw.size(); r++) {
                    if (r == i) continue;
                    if (value == jiffYaw.get(r)) jiffPatterns++;
                }
            }
            if (jiffPatterns > 2 && Statistics.getAverage(x) > 3.0
                    && jiffPatterns != 6 && jiffPatterns != 12 && jiffPatterns != 4) {
                flagAndAlert("AimBot pattern " + jiffPatterns);
            }

            // z-factor verdict: owned by AnalysisL.
            boolean positive = false;
            boolean negative = false;
            for (double value : zFactorYaw) {
                if (value > 10) positive = true;
                if (value < -10) negative = true;
            }
            if (zFactorYaw.size() == 2 && positive && negative && Statistics.getMax(zFactorYaw) < 55) {
                increaseBuffer(0, 1.5f);
                if (localBuffer[0] > 4) total++;
                if (localBuffer[0] > zFactorVlLimit) {
                    final AnalysisL zFactorCheck = player.checkManager.getCheck(AnalysisL.class);
                    if (zFactorCheck != null && zFactorCheck.flagAndAlert("suspicious zFactor " + zFactorYaw)
                            && zFactorCheck.mitigateDamageMs > 0) {
                        player.mitigateDamage(zFactorCheck.mitigateDamageMs);
                    }
                    localBuffer[0] = zFactorVlLimit - 1f;
                }
            } else {
                increaseBuffer(0, -1.2f);
            }
        }

        // Improbable accumulator: adds VL, so it stays on this check.
        {
            if (total < 2.0) {
                increaseBuffer(10, -2f);
            } else if (total > 2.0) {
                increaseBuffer(10, 5f);
                if (localBuffer[10] >= improbableVlLimit) {
                    flagAndAlert("Improbable " + localBuffer[10]);
                    // Upstream adds (limit - 2) after a flag rather than resetting to zero, which leaves
                    // the buffer above the threshold and forces a long decay before it can fire again.
                    increaseBuffer(10, improbableVlLimit - 2.0f);
                }
            }
        }

        rawRotations.clear();
    }

    private void increaseBuffer(final int index, final float value) {
        localBuffer[index] = Math.max(localBuffer[index] + value, 0);
    }
}
