package cc.watchneko.checks.impl.aim.heuristic;

import cc.watchneko.checks.impl.aim.AimAA;
import cc.watchneko.checks.impl.aim.processor.AimProcessor;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.math.Simplification;
import cc.watchneko.utils.math.Statistics;

public final class AimHeuristicConstantCheck implements HeuristicComponent {
    private static final double MODULO_THRESHOLD = 60.0;
    private static final double LINEAR_THRESHOLD = 0.1;
    private static final float MIN_DELTA = 0.1f;
    private static final float MAX_DELTA = 20.0f;
    private static final float CONSTANT1_NEED_VL = 8;
    private static final float CONSTANT2_NEED_VL = 6;
    private static final float CONSTANT3_NEED_VL = 6;

    private final AimAA check;
    private float lastDeltaYaw = 0.0f, lastDeltaPitch = 0.0f;
    private float buffer = 0, buffer2 = 0, buffer3 = 0;

    public AimHeuristicConstantCheck(final AimAA check) {
        this.check = check;
    }

    @Override
    public void process(final RotationUpdate event) {
        if (event.getDeltaXRotABS() == 0 && event.getDeltaYRotABS() == 0) return;

        final float deltaYaw = Math.abs(Math.abs(event.getTo().getYaw()) - Math.abs(event.getFrom().getYaw()));
        final float deltaPitch = Math.abs(Math.abs(event.getTo().getPitch()) - Math.abs(event.getFrom().getPitch()));

        // 原 function.allowed-cinematic 配置项已移除，固定按默认值 true：
        // 原条件为 `isCinematic2() && allowed`，allowed 恒为 true，等同于只判断 isCinematic2()
        if (event.isCinematic2()) {
            return;
        }

        final PlayerData player = check.getPlayer();
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
            final long expandedPitch = (long) (Statistics.EXPANDER * deltaPitch);
            final long expandedLastPitch = (long) (Statistics.EXPANDER * lastDeltaPitch);
            final long gcd = Statistics.getGcd(expandedPitch, expandedLastPitch);
            final boolean validAngles = deltaYaw > 0.25f && deltaPitch > 0.25f
                    && deltaPitch < MAX_DELTA && deltaYaw < MAX_DELTA;
            final boolean invalid = gcd < 131072L;

            if (invalid && validAngles && !sensitivityTooLow) {
                buffer = Math.min(buffer + 1, 200);
                if (buffer > CONSTANT1_NEED_VL + 2) {
                    if (check.flagAndAlert("* Constant rotations (1)")) {
                        check.getPlayer().mitigateDamage();
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
                    if (buffer2 > CONSTANT2_NEED_VL) {
                        if (check.flagAndAlert("* Constant rotations (2)")) {
                            check.getPlayer().mitigateDamage();
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
                    final float limit = CONSTANT3_NEED_VL + 1;
                    if (buffer3 > ((sensitivity < 70) ? limit + 1 : limit)) {
                        if (check.flagAndAlert("* Constant rotations (3) p=" + Simplification.scaleVal(deltaPitch, 3))) {
                            check.getPlayer().mitigateDamage();
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
