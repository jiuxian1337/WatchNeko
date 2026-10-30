package cc.watchneko.analysis.aim;

import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.math.Vec2f;

/**
 * The sampling rules every ported aim check shares.
 *
 * <p>The reference implementation decides once, in the parent check, whether a rotation update reaches
 * its components; each component then contains only its own arithmetic. Splitting those components into
 * separate checks here would otherwise copy the same exclusions into a dozen classes, where one of them
 * would eventually drift.
 */
public final class AimHeuristicGate {

    /** How long after a teleport the client's rotations are treated as unusable. */
    private static final long TELEPORT_GRACE_MS = 500L;

    /**
     * The longer window applied before a no-rotation tick counts.
     *
     * <p>The reference requires {@code lastTeleport + 1000 < now}, so a movement packet with no rotation
     * during the second after a teleport contributes nothing. Reproduced as-is: a shorter window would
     * feed the factor check extra zero samples right when the client is least reliable.
     */
    private static final long NO_ROTATION_TELEPORT_GRACE_MS = 1000L;

    private AimHeuristicGate() {
    }

    /**
     * True when this rotation update must not be sampled by a heuristic.
     *
     * <p>The teleport flag and {@code ignoreNextRotation} are the reference's dispatch guard: it refuses to
     * emit an event at all for 500 ms after a teleport, and for the first packet after one. The rest are
     * rotations that cannot be trusted no matter when they arrive.
     */
    public static boolean blocks(final PlayerData player) {
        if (player.ignoreNextRotation) {
            // The packet the client produced while reconciling the teleport. Consume the flag even when
            // another exclusion also applies: it is a one-shot, not a window.
            player.ignoreNextRotation = false;
            return true;
        }
        if (player.packetStateData.lastPacketWasTeleport
                || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation
                || player.packetStateData.lastPacketWasOnePointSeventeenDuplicate
                || player.compensatedEntities.self.getRiding() != null) {
            return true;
        }
        return System.currentTimeMillis() - player.lastServerTeleport < TELEPORT_GRACE_MS;
    }

    /**
     * The same guard for a tick that carried no rotation.
     *
     * <p>Only the timing applies: the exclusions above describe a rotation that cannot be trusted, and a
     * tick without one is neither trustworthy nor untrustworthy. The reference additionally waits a full
     * second after a teleport before emitting its synthetic no-rotation event.
     */
    public static boolean blocksNoRotation(final PlayerData player) {
        return System.currentTimeMillis() - player.lastServerTeleport < NO_ROTATION_TELEPORT_GRACE_MS;
    }

    /**
     * The guard for the windowed statistics checks, which have their own attack window and therefore only
     * need the teleport and finiteness rules.
     */
    public static boolean dropRotation(final PlayerData player, final RotationUpdate update) {
        if (blocks(player)) {
            return true;
        }
        return !isFinite(update.getTo().getYaw()) || !isFinite(update.getTo().getPitch())
                || !isFinite(update.getFrom().getYaw()) || !isFinite(update.getFrom().getPitch());
    }

    private static boolean isFinite(final float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value) && Math.abs(value) <= 3.0E8f;
    }

    /**
     * True when the update carried no pitch change, which the reference treats as "not a rotation".
     *
     * <p>Upstream compares the pitch delta with itself ({@code getY()} twice), so a purely horizontal
     * rotation takes the no-rotation path. That is a copy-paste bug there, reproduced on purpose: fixing it
     * here would feed every heuristic a different set of samples than the reference build.
     */
    public static boolean noPitchChange(final RotationUpdate update) {
        final float absDeltaY = Math.abs(Math.abs(update.getTo().getPitch()) - Math.abs(update.getFrom().getPitch()));
        return absDeltaY == 0 && absDeltaY == 0;
    }

    /**
     * The rotation delta with the sign the heuristics were written against.
     *
     * <p>{@link RotationUpdate#getDelta()} is {@code to - from}. The reference constructs its event as
     * {@code new RotationEvent(profile, to, from)} into a record whose fields are {@code (from, to)}, so
     * every one of its {@code getDelta()} calls is really {@code from - to} — the opposite sign. Three
     * things depend on it: the randomizer flaw only tests {@code delta > 1.5}, so it would otherwise also
     * fire on downward flicks; the pattern check's subtraction between consecutive deltas is negated, which
     * changes which vectors are considered repeats; and the smooth check's {@code atan2(x, y)} is mirrored.
     */
    public static Vec2f referenceDelta(final RotationUpdate update) {
        final Vec2f delta = update.getDelta();
        return new Vec2f(-delta.x(), -delta.y());
    }
}
