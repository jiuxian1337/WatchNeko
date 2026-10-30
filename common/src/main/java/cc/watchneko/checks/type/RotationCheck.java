package cc.watchneko.checks.type;

import cc.watchneko.utils.anticheat.update.RotationUpdate;

public interface RotationCheck extends PacketCheck {

    default void process(final RotationUpdate rotationUpdate) {
    }

    /**
     * Called once for a movement packet that moved the player without rotating at all.
     *
     * <p>{@link #process} only runs when yaw or pitch actually changed, so a check that reasons about the
     * gaps between rotations cannot see a tick where the player held still. Checks that do not care leave
     * this empty.
     */
    default void onNoRotation() {
    }
}
