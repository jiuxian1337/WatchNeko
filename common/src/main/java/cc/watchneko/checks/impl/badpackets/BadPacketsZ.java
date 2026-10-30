package cc.watchneko.checks.impl.badpackets;

import cc.watchneko.analysis.aim.AimHeuristicGate;
import cc.watchneko.checks.Check;
import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.type.RotationCheck;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.math.Statistics;
import cc.watchneko.utils.math.Vec2f;

/**
 * Impossible pitch, from MX's {@code AimInvalidCheck}.
 *
 * <p>It lives with the bad-packet checks because that is what it is: a client reporting a pitch value no
 * legitimate client produces. It reads rotation updates rather than raw packets only because that is where
 * this build keeps decoded rotations.
 *
 * <p>Upstream gives this one enough VL to punish on the first occurrence, and the {@code Invalid} group
 * reproduces that by kicking at one violation. It does not block attacks upstream, so it does not suppress
 * damage here either.
 */
@CheckData(
        name = "BadPacketsZ",
        configName = "BadPacketsZ",
        description = "impossible pitch value",
        decay = 0.05
)
public final class BadPacketsZ extends Check implements RotationCheck {

    private static final float INVALID_PITCH = 90f + 1e-6f;
    private static final int BUFFER_LIMIT = 70;
    private static final int ADD_BUFFER = 20;

    private int buffer;

    public BadPacketsZ(final PlayerData player) {
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

        if (Statistics.isExponentiallySmall(absDeltaY) && absDeltaY > 0.0 && absDeltaX > 0.5f) {
            buffer += ADD_BUFFER;
            if (buffer > BUFFER_LIMIT) {
                flagAndAlert("invalid pitch " + delta.y());
                buffer = BUFFER_LIMIT - 1;
            }
        } else {
            buffer = Math.max(0, buffer - 1);
        }

        if (update.getTo().getPitch() > INVALID_PITCH) {
            flagAndAlert("unlimited pitch " + delta.y());
        }
    }
}
