package cc.watchneko.checks.impl.analysis;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.analysis.aim.AimHeuristicGate;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.impl.aim.processor.AimProcessor;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.math.Simplification;
import cc.watchneko.utils.math.Statistics;

/**
 * Constant-rotation detection from MX's {@code AimConstantCheck}: three tests on whether consecutive
 * rotation deltas share a common divisor, which is what a client that feeds the same mouse increment
 * through every frame looks like.
 *
 * <p>Punishes without adding global VL upstream, so it lives in the {@code NoPenalty} group; what it does
 * instead is suppress outgoing damage for four seconds, the same window upstream blocks attacks for.
 */
@CheckData(
        name = "AnalysisE",
        configName = "AnalysisE",
        description = "constant rotation deltas sharing a common divisor",
        decay = 0.05
)
public final class AnalysisE extends Check implements RotationCheck {

    private static final double MODULO_THRESHOLD = 60.0;
    private static final double LINEAR_THRESHOLD = 0.1;
    private static final float MIN_DELTA = 0.1f;
    private static final float MAX_DELTA = 20.0f;


    private float lastDeltaYaw;
    private float lastDeltaPitch;
    private float buffer;
    private float buffer2;
    private float buffer3;

    private float constant1NeedVl = 8;
    private float constant2NeedVl = 6;
    private float constant3NeedVl = 6;
    private long mitigateDamageMs = 4000L;

    public AnalysisE(final PlayerData player) {
        super(player);
    }

    @Override
    public void onReload(final ConfigManager config) {
        constant1NeedVl = (float) config.getDoubleElse("AnalysisE.constant1-need-vl", constant1NeedVl);
        constant2NeedVl = (float) config.getDoubleElse("AnalysisE.constant2-need-vl", constant2NeedVl);
        constant3NeedVl = (float) config.getDoubleElse("AnalysisE.constant3-need-vl", constant3NeedVl);
        mitigateDamageMs = Math.max(0L, (long) config.getDoubleElse("AnalysisE.mitigate-damage-ms", mitigateDamageMs));
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

        final double sensitivity = player.calculateSensitivity();
        final AimProcessor aimProcessor = player.checkManager.getRotationCheck(AimProcessor.class);
        final boolean sensitivityTooLow = sensitivity < 50.0 && sensitivity > -1.0
                || (aimProcessor != null && aimProcessor.totalSensitivityClient < 50);

        final double divisorYaw = Statistics.getGcd(
                (long) (deltaYaw * Statistics.EXPANDER),
                (long) (lastDeltaYaw * Statistics.EXPANDER));
        final double divisorPitch = Statistics.getGcd(
                (long) (deltaPitch * Statistics.EXPANDER),
                (long) (lastDeltaPitch * Statistics.EXPANDER));

        final double constantYaw = divisorYaw / Statistics.EXPANDER;
        final double constantPitch = divisorPitch / Statistics.EXPANDER;

        // type 1
        {
            final long gcd = Statistics.getGcd(
                    (long) (Statistics.EXPANDER * deltaPitch),
                    (long) (Statistics.EXPANDER * lastDeltaPitch));
            final boolean validAngles = deltaYaw > 0.25f && deltaPitch > 0.25f
                    && deltaPitch < MAX_DELTA && deltaYaw < MAX_DELTA;

            if (gcd < 131072L && validAngles && !sensitivityTooLow) {
                buffer = Math.min(buffer + 1, 200);
                if (buffer > constant1NeedVl + 2) {
                    if (flagAndAlert("constant rotations (1)") && mitigateDamageMs > 0) {
                        player.mitigateDamage(mitigateDamageMs);
                    }
                    buffer = 4;
                }
            } else if (buffer > 0) {
                buffer -= 2f;
            }
        }

        // type 2
        {
            final double currentX = deltaYaw / constantYaw;
            final double currentY = deltaPitch / constantPitch;
            final double previousX = lastDeltaYaw / constantYaw;
            final double previousY = lastDeltaPitch / constantPitch;

            final boolean validDelta = deltaYaw > MIN_DELTA && deltaPitch > MIN_DELTA
                    && deltaYaw < MAX_DELTA && deltaPitch < MAX_DELTA;

            if (validDelta) {
                final double moduloX = currentX % previousX;
                final double moduloY = currentY % previousY;
                final double floorModuloX = Math.abs(Math.floor(moduloX) - moduloX);
                final double floorModuloY = Math.abs(Math.floor(moduloY) - moduloY);

                final boolean invalidX = moduloX > MODULO_THRESHOLD && floorModuloX > LINEAR_THRESHOLD;
                final boolean invalidY = moduloY > MODULO_THRESHOLD && floorModuloY > LINEAR_THRESHOLD;

                if (invalidX && invalidY && !sensitivityTooLow) {
                    buffer2 = Math.min(buffer2 + 1, 200);
                    if (buffer2 > constant2NeedVl) {
                        if (flagAndAlert("constant rotations (2)") && mitigateDamageMs > 0) {
                            player.mitigateDamage(mitigateDamageMs);
                        }
                        buffer2 = 4;
                    }
                } else if (buffer2 > 0) {
                    buffer2 -= 2f;
                }
            }
        }

        // type 3
        {
            final double currentX = deltaYaw / constantYaw;
            final double currentY = deltaPitch / constantPitch;
            final double previousX = lastDeltaYaw / constantYaw;
            final double previousY = lastDeltaPitch / constantPitch;

            final boolean validDelta = deltaYaw > MIN_DELTA && deltaPitch > MIN_DELTA
                    && deltaYaw < MAX_DELTA && deltaPitch < MAX_DELTA;

            if (validDelta) {
                final double moduloX = currentX % previousX;
                final double moduloY = currentY % previousY;
                final double floorModuloX = Math.abs(Math.floor(moduloX) - moduloX);
                final double floorModuloY = Math.abs(Math.floor(moduloY) - moduloY);

                final boolean invalidX = moduloX > MODULO_THRESHOLD && floorModuloX > LINEAR_THRESHOLD;
                final boolean invalidY = moduloY > MODULO_THRESHOLD && floorModuloY > LINEAR_THRESHOLD;

                if (invalidX && invalidY && !sensitivityTooLow) {
                    buffer3 = Math.max(buffer3 + ((deltaPitch < 1 || deltaPitch > 13) ? 2f : 1), 0);
                    final float limit = constant3NeedVl + 1;
                    if (buffer3 > ((sensitivity < 70) ? limit + 1 : limit)) {
                        if (flagAndAlert("constant rotations (3) p=" + Simplification.scaleVal(deltaPitch, 3))
                                && mitigateDamageMs > 0) {
                            player.mitigateDamage(mitigateDamageMs);
                        }
                        buffer3 = 0;
                    }
                } else if (buffer3 > 0) {
                    buffer3 -= 2f;
                }
            }
        }

        this.lastDeltaYaw = deltaYaw;
        this.lastDeltaPitch = deltaPitch;
    }
}
