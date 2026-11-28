package cc.watchneko.checks.impl.analysis;

import cc.watchneko.checks.impl.analysis.AnalysisH.Candidate;
import cc.watchneko.checks.impl.analysis.AnalysisH.Settings;
import cc.watchneko.checks.impl.analysis.AnalysisH.Window;
import cc.watchneko.utils.anticheat.update.RotationUpdate;
import cc.watchneko.utils.data.HeadRotation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnalysisHTest {
    private static final Settings DEFAULT = new Settings(3.0f, 2500);
    private static final Settings SINGLE_MATCH = new Settings(1.0f, 2500);
    // The vanilla 1.8 sensitivity slider displays the stored value multiplied by 200.
    private static final float QUANTUM = quantum(0.33f);

    @Test
    void sixtySixPercentTurningWithoutAttacksCannotFillTheBuffer() {
        Replay replay = new Replay(DEFAULT);
        for (int counts : new int[]{1, 480, 2, 3, 505, 5, 7, 530, 9}) {
            assertNull(replay.rotate(counts, 1));
        }
        assertNull(replay.rotate(11, 1));
        assertNull(replay.attack());
        assertNull(replay.rotate(550, 1));
        assertNull(replay.rotate(12, 1));
    }

    @Test
    void repeatedSnapsStillFlagWithAttacksOnBothSidesOfTheLookPacket() {
        Replay replay = new Replay(DEFAULT);
        assertNull(replay.snap(1, 480, 2, true));
        assertNull(replay.snap(3, 505, 5, false));
        assertNotNull(replay.snap(7, 530, 9, true));
    }

    @Test
    void attackCanConfirmASnapAfterAPositionOnlyTick() {
        Replay replay = new Replay(SINGLE_MATCH);
        replay.rotate(1, 1);
        replay.rotate(480, 1);
        assertNull(replay.stationaryLook());
        assertNotNull(replay.attack());
        assertNull(replay.attack());
    }

    @Test
    void anAttackCannotConfirmAStaleOrUnrelatedSnap() {
        Replay late = new Replay(SINGLE_MATCH);
        late.rotate(1, 1);
        late.rotate(480, 1);
        late.stationaryLook();
        late.now += millis(300);
        assertNull(late.attack());

        Replay afterAnotherLook = new Replay(SINGLE_MATCH);
        afterAnotherLook.rotate(1, 1);
        afterAnotherLook.rotate(480, 1);
        afterAnotherLook.rotate(2, 1);
        assertNull(afterAnotherLook.attack());
    }

    @Test
    void yawOnlyNeighboursStillCountAsTurning() {
        Replay replay = new Replay(SINGLE_MATCH);
        replay.rotate(80, 0);
        replay.rotate(480, 1);
        replay.attack();
        assertNull(replay.rotate(90, 0));
    }

    @Test
    void aSmallTurnAcrossTheYawBoundaryCannotBecomeASnap() {
        Window window = new Window();
        window.rotation(rotation(359.8f, 359.9f, 0, 0.1f), 1, millis(50), SINGLE_MATCH);
        window.attack(1, millis(75), SINGLE_MATCH);
        window.rotation(rotation(359.9f, 0.05f, 0.1f, 0.2f), 2, millis(100), SINGLE_MATCH);
        assertNull(window.rotation(rotation(0.05f, 0.15f, 0.2f, 0.3f), 3, millis(150), SINGLE_MATCH));
    }

    @Test
    void oldCombatEvidenceExpiresWhileThePlayerIsIdle() {
        Replay replay = new Replay(DEFAULT);
        assertNull(replay.snap(1, 480, 2, true));
        assertNull(replay.snap(3, 505, 5, false));
        replay.now += millis(3600);
        assertNull(replay.snap(7, 530, 9, false));
    }

    private static float quantum(float sensitivity) {
        float scaled = sensitivity * 0.6f + 0.2f;
        return scaled * scaled * scaled * 8.0f * 0.15f;
    }

    private static long millis(long time) {
        return TimeUnit.MILLISECONDS.toNanos(time);
    }

    private static RotationUpdate rotation(float fromYaw, float toYaw, float fromPitch, float toPitch) {
        return new RotationUpdate(new HeadRotation(fromYaw, fromPitch), new HeadRotation(toYaw, toPitch),
                toYaw - fromYaw, toPitch - fromPitch);
    }

    private static final class Replay {
        private final Window window = new Window();
        private final Settings settings;
        private long now;
        private long sequence;
        private float yaw = 10;
        private float pitch;

        private Replay(Settings settings) {
            this.settings = settings;
        }

        private Candidate rotate(int yawCounts, int pitchCounts) {
            float previousYaw = yaw;
            float previousPitch = pitch;
            yaw += yawCounts * QUANTUM;
            pitch += pitchCounts * QUANTUM;
            now += millis(50);
            return window.rotation(rotation(previousYaw, yaw, previousPitch, pitch), ++sequence, now, settings);
        }

        private Candidate attack() {
            now += millis(1);
            return window.attack(sequence, now, settings);
        }

        private Candidate stationaryLook() {
            now += millis(50);
            return window.sample(0, sequence, now, settings);
        }

        private Candidate snap(int left, int centre, int right, boolean attackFirst) {
            assertNull(rotate(left, 1));
            if (attackFirst) assertNull(attack());
            assertNull(rotate(centre, 1));
            if (!attackFirst) assertNull(attack());
            return rotate(right, 1);
        }
    }
}
