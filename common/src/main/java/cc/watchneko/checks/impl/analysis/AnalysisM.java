package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.analysis.aim.AimHeuristicGate;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.impl.aim.processor.AimProcessor;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.math.Statistics;
import cc.watchneko.utils.math.Vec2;
import cc.watchneko.utils.math.Vec2f;
import cc.watchneko.utils.math.Vec2i;

import java.util.ArrayList;
import java.util.List;

/**
 * The complex family from MX's {@code AimComplexCheck}: entropy stability, distinct values, a spike model
 * over gcd-scaled rotation integers, and a randomizer flaw test.
 *
 * <p>Three of the four verdicts add no VL upstream and are separate checks here — {@link AnalysisN}
 * (perfect entropy), {@link AnalysisO} (invalid distinct) and the randomizer flaw stays on this check
 * because upstream does add VL for it. Each split-out verdict keeps its own attack-block duration, which
 * is what the reference distinguishes them by: 5 s for entropy, 3 s for distinct, none for randomizer.
 */
@CheckData(
        name = "AnalysisM",
        configName = "AnalysisM",
        description = "entropy stability, distinct values, spike model, randomizer flaw",
        decay = 0.05
)
public final class AnalysisM extends Check implements RotationCheck {

    private static final int BUFFER_SLOTS = 16;
    private static final int WINDOW_SIZE = 10;
    private static final int SPIKE_WINDOW = 7;

    private final List<Vec2f> rawRotations = new ArrayList<>();
    private final List<Vec2i> rotations2 = new ArrayList<>();
    private final List<Vec2> kireikoGeneric = new ArrayList<>();
    private final float[] localBuffer = new float[BUFFER_SLOTS];

    private double oldShannonYaw;
    private double oldShannonPitch;

    private float entropyVlLimit = 30;
    private float distinctVlLimit = 4.0f;
    private float randomizerVlLimit = 2.5f;

    public AnalysisM(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        entropyVlLimit = (float) config.getDoubleElse("AnalysisM.entropy-vl-limit", entropyVlLimit);
        distinctVlLimit = (float) config.getDoubleElse("AnalysisM.distinct-vl-limit", distinctVlLimit);
        randomizerVlLimit = (float) config.getDoubleElse("AnalysisM.randomizer-vl-limit", randomizerVlLimit);
    }

    @Override
    public void process(final RotationUpdate update) {
        if (AimHeuristicGate.dropRotation(player, update)) {
            return;
        }
        if (!player.actionManager.hasAttackedSince(3500L)) {
            return;
        }

        final Vec2f delta = update.getDelta();
        rawRotations.add(delta);

        final double gcdValue = Statistics.getGCDValue(0.5d) * 3;
        rotations2.add(new Vec2i((int) (delta.x() / gcdValue), (int) (delta.y() / gcdValue)));

        if (rotations2.size() >= WINDOW_SIZE) {
            checkSpikes();
        }
        if (rawRotations.size() >= WINDOW_SIZE) {
            checkRaw();
        }
    }

    private void checkRaw() {
        final AimProcessor aimProcessor = player.checkManager.getRotationCheck(AimProcessor.class);
        final int sens = player.calculateSensitivity();
        final int sensTemp = aimProcessor != null ? aimProcessor.totalSensitivityClient : 0;

        final List<Float> x = new ArrayList<>();
        final List<Float> y = new ArrayList<>();
        for (Vec2f vec : rawRotations) {
            x.add(vec.x());
            y.add(vec.y());
        }

        final int distinctX = Statistics.getDistinct(x);
        final double shannonYaw = Statistics.getShannonEntropy(x);
        final double shannonPitch = Statistics.getShannonEntropy(y);
        final boolean valid = sens >= 60 && sens <= 150 && sensTemp >= 60 && sensTemp < 150;

        // Perfect entropy: owned by AnalysisN.
        if (valid && getDifference(shannonYaw, oldShannonYaw) < 1e-5
                && getDifference(shannonPitch, oldShannonPitch) < 1e-5) {
            increaseBuffer(11, 1.0f);
            if (localBuffer[11] > entropyVlLimit) {
                final AnalysisN perfect = player.checkManager.getCheck(AnalysisN.class);
                if (perfect != null && perfect.flagAndAlert("perfect shannon entropy " + shannonYaw)
                        && perfect.mitigateDamageMs > 0) {
                    player.mitigateDamage(perfect.mitigateDamageMs);
                }
                localBuffer[11] = entropyVlLimit - 1;
            }
        } else {
            localBuffer[11] = 0f;
        }

        // Similar entropy: also owned by AnalysisN, same budget upstream.
        if (valid && getDifference(shannonYaw, shannonPitch) < 1e-5) {
            increaseBuffer(12, 1.0f);
            if (localBuffer[12] > entropyVlLimit) {
                final AnalysisN perfect = player.checkManager.getCheck(AnalysisN.class);
                if (perfect != null && perfect.flagAndAlert("similar shannon entropy " + shannonYaw)
                        && perfect.mitigateDamageMs > 0) {
                    player.mitigateDamage(perfect.mitigateDamageMs);
                }
                localBuffer[12] = entropyVlLimit - 1;
            }
        } else {
            localBuffer[12] = 0f;
        }

        // Invalid distinct: owned by AnalysisO.
        if (distinctX < 8 && Math.abs(Statistics.getAverage(x)) > 2.5) {
            increaseBuffer(9, 1.7f);
            if (localBuffer[9] >= distinctVlLimit) {
                final AnalysisO distinct = player.checkManager.getCheck(AnalysisO.class);
                if (distinct != null && distinct.flagAndAlert("invalid distinct")
                        && distinct.mitigateDamageMs > 0) {
                    player.mitigateDamage(distinct.mitigateDamageMs);
                }
                increaseBuffer(9, -0.5f);
            }
        } else {
            increaseBuffer(9, -0.35f);
        }

        oldShannonYaw = shannonYaw;
        oldShannonPitch = shannonPitch;
        rawRotations.clear();
    }

    private void checkSpikes() {
        final List<Integer> gcdYaw = new ArrayList<>();
        final List<Integer> gcdPitch = new ArrayList<>();
        for (Vec2i vec : rotations2) {
            gcdYaw.add(vec.x());
            gcdPitch.add(vec.y());
        }
        rotations2.clear();
        if (gcdYaw.isEmpty()) return;

        final Vec2 kireikoGenericVec = new Vec2(
                (float) Statistics.getKireikoGeneric(gcdYaw),
                (float) Statistics.getKireikoGeneric(gcdPitch));

        // Spike model: upstream adds no VL for it, so it only fills a buffer that feeds nothing else.
        kireikoGeneric.add(kireikoGenericVec);
        if (kireikoGeneric.size() >= SPIKE_WINDOW) {
            final List<Double> kx = new ArrayList<>();
            final List<Double> ky = new ArrayList<>();
            for (Vec2 vec : kireikoGeneric) {
                kx.add((double) vec.x());
                ky.add((double) vec.y());
            }

            final double xDev = Statistics.getStandardDeviation(kx);
            final double xSpikeMax = Statistics.getMax(kx);

            if (xDev > 5 && xDev < 22 && xSpikeMax < 50) {
                increaseBuffer(5, (Statistics.getAverage(kx) < 6.0) ? 0 : (xDev < 10) ? 1.5f : 1.0f);
                if (localBuffer[5] >= 7.0f) {
                    localBuffer[5] = 6.0f;
                }
            } else {
                increaseBuffer(5, (xDev < 40 || xSpikeMax < 70) ? -0.4f : -0.8f);
            }
            kireikoGeneric.clear();
        }

        // Randomizer flaw: upstream adds VL for this one, so it stays on this check.
        {
            final double devX = Statistics.getVariance(gcdYaw);
            final double devY = Statistics.getVariance(gcdPitch);
            final double min = Math.min(devX, devY);
            final double max = Math.max(devX, devY);
            if (min < 0.09 && max > 35 && Statistics.getMin(gcdPitch) != 0.0
                    && player.calculateSensitivity() > 50) {
                increaseBuffer(4, 1.0f);
                if (localBuffer[4] > randomizerVlLimit) {
                    flagAndAlert("randomizer flaw");
                    localBuffer[4] = randomizerVlLimit - 1;
                }
            } else {
                increaseBuffer(4, -0.4f);
            }
        }
    }

    private void increaseBuffer(final int index, final float value) {
        localBuffer[index] = Math.max(localBuffer[index] + value, 0);
    }

    private static double getDifference(final double a, final double b) {
        return Math.abs(Math.abs(a) - Math.abs(b));
    }
}
