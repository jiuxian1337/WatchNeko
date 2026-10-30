package cc.watchneko.checks.impl.aim;

import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.data.Pair2;
import cc.watchneko.utils.lists.EvictingList;
import cc.watchneko.utils.math.MathUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@CheckData(
        name = "AimW",
        configName = "AimW",
        decay = 0.65,
        description = "Detect abnormal horizontal rotation patterns during attacks"
)
public final class AimW extends Check implements RotationCheck {
    private final EvictingList<Pair2<Double, Double>> rotations = new EvictingList<>(10);
    private final EvictingList<Pair2<Integer, Integer>> rotationsG = new EvictingList<>(10);

    public AimW(PlayerData player) {
        super(player);
    }

    @Override
    public void process(RotationUpdate update) {
        if (player.actionManager.hasAttackedSince(1200L)) {
            double deltaYaw = update.getProcessor().getDeltaYaw();
            double deltaPitch = update.getProcessor().getDeltaPitch();
            double gcdValue = MathUtil.getGCDValueStatistic(0.5) * 3;
            rotations.add(new Pair2<>(deltaYaw, deltaPitch));
            rotationsG.add(new Pair2<>((int) (deltaYaw / gcdValue), (int) (deltaPitch / gcdValue)));
            if (rotations.isFull()) {
                List<Double> x = new ArrayList<>();
                List<Double> y = new ArrayList<>();
                List<Integer> xG = new ArrayList<>();
                List<Integer> yG = new ArrayList<>();
                for (Pair2<Double, Double> vec2 : rotations) {
                    x.add(vec2.x());
                    y.add(vec2.y());
                }
                for (Pair2<Integer, Integer> vec2 : rotationsG) {
                    xG.add(vec2.x());
                    yG.add(vec2.y());
                }

                double devX = MathUtil.getVariance(xG);
                double devY = MathUtil.getVariance(yG);
                double min = Math.min(devX, devY);
                double max = Math.max(devX, devY);
                // The window is 10 samples, so these were 0.09 and 35 against an un-normalised sum of
                // squares; they are divided by 10 to stay the same fraction of the spread now that
                // getVariance returns a real variance.
                if (min < 0.009 && max > 3.5 && Collections.min(yG) != 0.0) {
                    if (buffer++ > 4) {
                        if (flagAndAlert("low= " + min + "\nmax= " + max) && getViolations() > 5) {
                            player.mitigateDamage();
                        }
                    } else {
                        rewardBufferAndVL();
                    }
                }
            }
        }
    }
}
