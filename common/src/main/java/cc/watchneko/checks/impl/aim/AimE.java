package cc.watchneko.checks.impl.aim;

import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.data.HeadRotation;
import cc.watchneko.utils.math.MathUtil;
import cc.watchneko.utils.math.Vec2f;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@CheckData(
        name = "AimE",
        configName = "AimE",
        description = "abnormal aim distribution and rank patterns",
        decay = 0.92
)
public class AimE extends Check implements RotationCheck {
    private static final int RAW_ROTATIONS_THRESHOLD = 100;
    private static final int SCORE_CHUNK_SIZE = 10;

    private final List<Vec2f> rawRotations = new ArrayList<>();
    private final List<Vec2f> limitedRotations = new ArrayList<>();
    private final List<Float> longTermAnalysis = new ArrayList<>();
    private double scoreBuffer;
    private double distributionBuffer;
    private double limitBuffer;
    private double outlierBuffer;
    private boolean query = false;
    private int standTicks;

    public AimE(PlayerData player) {
        super(player);
    }

    @Override
    public void process(RotationUpdate update) {
        if (!isInCombat() || hasExemptions() || !shouldModifyPackets()) {
            reduceAllBuffers(0.80, 0.3);
            rawRotations.clear();
            return;
        }
        if (player.getTarget() == null) {
            return;
        }

        standTicks = player.isMoving() ? 0 : standTicks + 1;
        if (standTicks > 20) {
            reduceAllBuffers(0.80, 0.3);
        }
        if (!player.isMoving()) {
            return;
        }

        final HeadRotation now = update.getTo();
        final HeadRotation last = update.getFrom();
        Vec2f delta = new Vec2f(now.getYaw() - last.getYaw(), now.getPitch() - last.getPitch());
        rawRotations.add(delta);

        if (rawRotations.size() >= RAW_ROTATIONS_THRESHOLD) {
            checkRotationData();
        }
        // Any sizeable rotation feeds the limited window. Sensitivity is deliberately not gated here:
        // low-sensitivity clients produce the same shapes and would otherwise go unchecked.
        if (Math.abs(delta.x()) > 1.35 || (Math.abs(delta.y()) > 1.35 && Math.abs(delta.x()) > 0.32)) {
            limitedRotations.add(delta);
            if (limitedRotations.size() >= 100) {
                checkLimited();
            }
        }
    }

    private void checkRotationData() {
        List<Float> yawChanges = new ArrayList<>();
        List<Float> pitchChanges = new ArrayList<>();

        for (Vec2f vec : rawRotations) {
            yawChanges.add(vec.x());
            pitchChanges.add(vec.y());
        }

        checkScore(yawChanges);
        checkDistribution(yawChanges, pitchChanges);
        rawRotations.clear();
    }

    private void checkScore(List<Float> yawChanges) {
        final List<Float> x = new ArrayList<>();
        final List<Float> y = new ArrayList<>();
        final int sens = player.calculateSensitivity();
        for (Vec2f vec2 : rawRotations) {
            x.add(vec2.x());
            y.add(vec2.y());
        }

        List<Float> yawStack = new ArrayList<>();
        List<Double> deviations = new ArrayList<>();
        int distinctCount = 0;
        for (final float yaw : x) {
            yawStack.add(yaw);
            if (yawStack.size() >= SCORE_CHUNK_SIZE) {
                deviations.add(MathUtil.getStandardDeviation(MathUtil.getJiffDelta(yawStack, 5)));
                distinctCount += MathUtil.getDistinct(MathUtil.getJiffDelta(yawStack, 4));
                yawStack.clear();
            }
        }

        List<Double> outliers = MathUtil.getZScoreOutliers(deviations, 0.5f);
        float distinctRank = (float) distinctCount / 60;

        // Two shapes of the same signal: either no outlier at all (every chunk looks alike), or exactly
        // one outlier far outside the normal range. Both indicate a machine-generated rotation stream.
        final boolean noOutlier = outliers.isEmpty();
        final boolean oneExtremeOutlier = outliers.size() == 1
                && Math.abs(outliers.get(0)) > 18 && Math.abs(outliers.get(0)) < 100;

        if (noOutlier || oneExtremeOutlier) {
            if (!query) {
                query = true;
            } else if (++outlierBuffer > 5) {
                if (flagAndAlert("o= " + Arrays.toString(outliers.toArray()))) {
                    player.mitigateDamage();
                }
            }
        } else {
            query = false;
            outlierBuffer = Math.max(0, outlierBuffer - 0.5);
        }

        final boolean valid = sens > 20 && sens < 140;
        if (distinctRank < 1.0 && distinctRank > 0.7 && MathUtil.getAverage(yawChanges) > 1.8 && valid) {
            if (++scoreBuffer > 3) {
                if (flagAndAlert("r= " + distinctRank) && scoreBuffer > 5.6) {
                    player.mitigateDamage();
                }
            }
        } else {
            scoreBuffer = Math.max(0, scoreBuffer - 0.5);
        }
    }

    private void checkLimited() {
        final List<Float> x = new ArrayList<>();
        for (Vec2f vec2 : limitedRotations) {
            x.add(vec2.x());
        }

        final List<Float> yawStack = new ArrayList<>();
        int resultDistinct = 0;
        for (float yaw : x) {
            yawStack.add(yaw);
            if (yawStack.size() >= 10) {
                resultDistinct += MathUtil.getDistinct(MathUtil.getJiffDelta(yawStack, 4));
                yawStack.clear();
            }
        }

        final float distinctRank = (float) resultDistinct / 60;
        longTermAnalysis.add(distinctRank);
        if (longTermAnalysis.size() >= 10) {
            double avg = MathUtil.getAverage(longTermAnalysis);
            long normalCount = longTermAnalysis.stream().filter(d -> d > 0.97).count();
            if (avg < 0.95 && normalCount < 4) {
                if (limitBuffer++ > 4 && flagAndAlert("(Limit)\navg= " + avg + "\nn= " + normalCount)) {
                    player.mitigateDamage();
                }
            } else {
                limitBuffer = Math.max(0, limitBuffer - 0.75);
            }
            longTermAnalysis.clear();
        }
        limitedRotations.clear();
    }

    private void checkDistribution(List<Float> yawChanges, List<Float> pitchChanges) {
        double distinctX = MathUtil.getDistinct(yawChanges);
        double maxYawAbs = Math.abs(MathUtil.getMax(yawChanges));
        double averageYawAbs = yawChanges.stream().mapToDouble(value -> Math.abs(value)).average().orElse(0.0);
        double averagePitchAbs = pitchChanges.stream().mapToDouble(value -> Math.abs(value)).average().orElse(0.0);
        double kurtosis = MathUtil.getKurtosis(yawChanges);
        double pearson = MathUtil.getPearsonCorrelation(yawChanges, pitchChanges);
        int spikeCount = MathUtil.getZScoreOutliers(yawChanges, 1.0f).size() + MathUtil.getZScoreOutliers(pitchChanges, 1.0f).size();

        // Keep this branch for obviously synthetic distributions only.
        // Legit high-sensitivity combat can naturally land in the old distinct/spike range.
        if (maxYawAbs > 12
                && averageYawAbs > 3.25
                && averagePitchAbs > 0.9
                && Math.abs(pearson) < 0.06
                && distinctX < 80
                && distinctX > 68
                && kurtosis > 1.25
                && spikeCount >= 48) {
            if (++distributionBuffer > 6) {
                if (flagAndAlert("d= " + distinctX
                        + "\np= " + pearson
                        + "\nmax= " + maxYawAbs
                        + "\navgY= " + averageYawAbs
                        + "\navgP= " + averagePitchAbs
                        + "\ns= " + spikeCount)) {
                    player.mitigateDamage();
                }
            }
        } else {
            distributionBuffer = Math.max(0, distributionBuffer - 0.85);
        }
    }

    private void reduceAllBuffers(double factor, double minCutoff) {
        scoreBuffer *= factor;
        distributionBuffer *= factor;
        outlierBuffer *= factor;
        limitBuffer *= factor;
        if (limitBuffer < minCutoff) limitBuffer = 0;
        if (scoreBuffer < minCutoff) scoreBuffer = 0;
        if (outlierBuffer < minCutoff) outlierBuffer = 0;
        if (distributionBuffer < minCutoff) distributionBuffer = 0;
    }

    private boolean isInCombat() {
        return player.actionManager.hasAttackedSince(1500L);
    }

    private boolean hasExemptions() {
        return player.packetStateData.lastPacketWasTeleport
                || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation
                || player.packetStateData.lastPacketWasOnePointSeventeenDuplicate
                || player.compensatedEntities.self.getRiding() != null
                || player.getTarget().type != EntityTypes.PLAYER
                || (player.getLastTarget() != null && !player.getTarget().getUuid().equals(player.getLastTarget().getUuid()));
    }
}
