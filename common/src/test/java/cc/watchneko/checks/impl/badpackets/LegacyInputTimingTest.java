package cc.watchneko.checks.impl.badpackets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyInputTimingTest {
    @Test
    void releasesRequireAKnownIdleStateAndAllowPlausibleUse() {
        var use = new LegacyItemRelease.ItemUse();
        for (int index = 0; index < 6; index++) assertFalse(use.release());
        for (int tick = 0; tick < 5; tick++) use.tick(false);
        assertFalse(use.release());
        assertFalse(use.release());
        assertTrue(use.release());
        use.possibleUse();
        assertFalse(use.release());
        use.tick(true);
        assertFalse(use.release());
        use.reset();
        assertFalse(use.release());
    }

    @Test
    void sprintAndSneakCanChangeTogetherWithoutConflicting() {
        var transitions = new LegacyEntityActions.Transitions();
        for (int index = 0; index < 8; index++) {
            transitions.sprint(true);
            transitions.sneak(false);
            assertFalse(transitions.finishTick());
            transitions.sprint(false);
            transitions.sneak(true);
            assertFalse(transitions.finishTick());
        }
        for (int index = 0; index < 2; index++) {
            transitions.sprint(true);
            transitions.sprint(false);
            assertFalse(transitions.finishTick());
        }
        transitions.sneak(true);
        transitions.sneak(false);
        assertTrue(transitions.finishTick());
    }
}
