package cc.watchneko.checks.impl.aim;

import cc.watchneko.checks.CheckData;
import cc.watchneko.checks.impl.aim.AimTrajectoryTracker.Frame;
import cc.watchneko.checks.impl.aim.AimTrajectoryTracker.Reports;
import cc.watchneko.checks.impl.aim.AimTrajectoryTracker.TargetAngles;
import cc.watchneko.utils.anticheat.update.AttackAnchor;
import cc.watchneko.utils.anticheat.update.RotationSequence;
import cc.watchneko.utils.math.MathUtil;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AimTrajectoryTest {
    private static final double QUANTUM = 0.15;
    private static final float TARGET_YAW = -179.5F;
    // Remaining degrees in units of the sensitivity step, sampled at client ticks.
    private static final int[] SMALL_TURN = {120, 115, 93, 57, 27, 8, 0};
    private static final int[] LARGE_TURN = {308, 303, 281, 243, 189, 119, 58, 18, 0};
    private static final int[] AMBIGUOUS_TURN = {160, 149, 122, 79, 38, 11, 0};
    private static final int[] SMOOTH_TURN = {160, 154, 126, 80, 34, 6, 0};

    @Test
    void testsPeriodOnLaterTicksAndKeepsThePhaseThroughIdleTicks() {
        var periodic = AimPeriodicity.evaluate(periodFrames(0.19, false), QUANTUM);
        assertTrue(periodic.matched(), periodic::verbose);
        assertTrue(periodic.gain() >= 0.80);
        assertEquals(0.19, periodic.amplitude(), 0.02);

        var shifted = AimPeriodicity.evaluate(periodFrames(0.19, true), QUANTUM);
        assertFalse(shifted.matched(), shifted::verbose);
    }

    @Test
    void rejectsFixedTiltAndIndependentPitchPeriod() {
        var result = AimPeriodicity.evaluate(periodFrames(0, false), QUANTUM);
        assertFalse(result.matched(), result::verbose);
        assertTrue(result.trainingSamples() >= 24 && result.validationSamples() >= 24);
    }

    @Test
    void rejectsAWindowWithUncertainOrVerticallyMovingTarget() {
        var frames = periodFrames(0.19, false);
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            TargetAngles target = i % 2 == 0 ? new TargetAngles(TARGET_YAW, 0, 0, -1, 1, true, true) : null;
            frames.set(i, new Frame(frame.tick(), frame.yaw(), frame.pitch(), target, QUANTUM));
        }
        var result = AimPeriodicity.evaluate(frames, QUANTUM);
        assertFalse(result.matched());
        assertEquals("coverage", result.reason());
    }

    @Test
    void separatesClippedBrakingFromSmoothAndShortTurnsAcrossYawWrap() {
        var clipped = AimBraking.evaluate(turnFrames(SMALL_TURN), QUANTUM);
        assertTrue(clipped.matched(), clipped::verbose);
        assertEquals(18, clipped.amplitude(), 0.01);

        var smooth = AimBraking.evaluate(turnFrames(SMOOTH_TURN), QUANTUM);
        assertTrue(smooth.qualified());
        assertFalse(smooth.matched(), smooth::verbose);
        assertTrue(smooth.lastMoveFraction() >= 0.12);
        assertTrue(smooth.smoothError() <= 1.5 * QUANTUM);

        var ambiguous = AimBraking.evaluate(turnFrames(AMBIGUOUS_TURN), QUANTUM);
        assertTrue(ambiguous.qualified());
        assertTrue(ambiguous.error() < 0.08);
        assertFalse(ambiguous.matched(), ambiguous::verbose);

        var abrupt = AimBraking.evaluate(turnFrames(new int[]{160, 155, 135, 90, 35, 0}), QUANTUM);
        assertFalse(abrupt.qualified());
    }

    @Test
    void replaysDifferentQuantizationStepsAndTickPhases() {
        for (double quantum : new double[]{0.05, 0.15, 0.30}) {
            for (int phase = 0; phase < 3; phase++) {
                var frames = new ArrayList<Frame>();
                for (int i = 0; i <= 8; i++) {
                    double t = Math.max(0, Math.min((i * 3.0 - phase) / (24.0 - phase), 1));
                    double progress = t * t * t * (10 + t * (-15 + 6 * t));
                    float yaw = MathUtil.wrapAngleTo180_float((float) (TARGET_YAW - quantize(40 * (1 - progress), quantum)));
                    frames.add(new Frame(i, yaw, 0, target(i == 8), quantum));
                }
                assertFalse(AimBraking.evaluate(frames, quantum).matched());
            }
        }
    }

    @Test
    void onlyAcceptsAnUncancelledAttackAnchorOnce() {
        var rotations = new RotationSequence();
        var tracker = new AimTrajectoryTracker();
        rotations.addListener(tracker);
        rotations.onAttackObserved(12);
        assertNull(tracker.finishAttack(true));
        assertNull(tracker.finishAttack(false));
        assertFalse(tracker.hasTargetForNextTick());
        rotations.onAttackObserved(13);
        assertEquals(new AttackAnchor(0, 13), tracker.finishAttack(false));
        tracker.recordAttack();
        assertTrue(tracker.hasTargetForNextTick());
        for (int i = 0; i < 60; i++) {
            assertTrue(tracker.hasTargetForNextTick());
            tracker.recordFrame(TARGET_YAW, 0, null, QUANTUM, true, false, i);
        }
        assertFalse(tracker.hasTargetForNextTick());
        tracker.clearTarget();
        assertFalse(tracker.hasTargetForNextTick());
    }

    @Test
    void requiresDifferentTurnsToCorroborateRepeatedPeriodicWindows() {
        var replay = new Replay(true, false);
        assertTrue(replay.period().period().matched());
        var second = replay.periodOnTarget();
        assertTrue(second.period().matched());
        assertNull(second.flagPeriod());
        var firstTurn = replay.turn(SMALL_TURN, 3, false);
        assertTrue(firstTurn.braking().matched());
        assertNull(firstTurn.flagPeriod());
        var secondTurn = replay.turn(LARGE_TURN, 0, false);
        assertNotNull(secondTurn.flagPeriod());
        assertNull(secondTurn.flagBraking());
        assertNull(replay.turn(LARGE_TURN, 0, false).flagPeriod());
    }

    @Test
    void requiresAnAttackInTheTurnWindowAndDiscardsAnInterruptedTurn() {
        var replay = new Replay(false, true);
        var noAttack = replay.turn(SMALL_TURN, -2, false);
        assertEquals("attack-window", noAttack.braking().reason());
        assertNull(replay.turn(SMALL_TURN, 0, true).braking());
        assertTrue(replay.turn(SMALL_TURN, 3, false).braking().matched());
    }

    @Test
    void startsAFullPeriodWindowAfterAnUncertainTick() {
        var replay = new Replay(true, false);
        replay.tracker.recordAttack();
        for (Frame frame : periodFrames(0.19, false).subList(0, 50)) {
            assertNull(replay.feed(frame.yaw(), frame.pitch(), frame.target()).period());
        }
        replay.tracker.endSegment();
        assertTrue(replay.periodOnTarget().period().matched());
    }

    @Test
    void waitsForEightEligibleTurnsAndRequiresBothAmplitudeRanges() {
        var replay = new Replay(false, true);
        for (int i = 0; i < 8; i++) {
            var report = replay.turn(SMALL_TURN, 0, false);
            assertTrue(report.braking().matched());
            assertNull(report.flagBraking());
        }
        assertNotNull(replay.turn(LARGE_TURN, 0, false).flagBraking());
        assertNull(replay.turn(LARGE_TURN, 0, false).flagBraking());

        var mixed = new Replay(false, true);
        for (int i = 0; i < 2; i++) assertNull(mixed.turn(SMOOTH_TURN, 0, false).flagBraking());
        for (int i = 0; i < 5; i++) assertNull(mixed.turn(SMALL_TURN, 0, false).flagBraking());
        assertNotNull(mixed.turn(LARGE_TURN, 0, false).flagBraking());
    }

    @Test
    void expiresEvidenceAndClearsItOnReset() {
        var replay = new Replay(true, false);
        replay.period();
        replay.period();
        replay.turn(SMALL_TURN, 0, false);
        replay.now += TimeUnit.SECONDS.toNanos(91);
        assertNull(replay.turn(LARGE_TURN, 0, false).flagPeriod());

        replay.period();
        replay.period();
        replay.tracker.reset();
        assertNull(replay.turn(SMALL_TURN, 0, false).flagPeriod());
        assertNull(replay.turn(LARGE_TURN, 0, false).flagPeriod());
    }

    @Test
    void defaultsUseTheExistingExperimentalGateAndExactAlertGroups() throws IOException {
        for (Class<?> check : List.of(AimY.class, AimZ.class)) {
            CheckData annotation = check.getAnnotation(CheckData.class);
            assertTrue(annotation.experimental());
            assertTrue(annotation.requiresExperimentalChecks());
            assertTrue(annotation.requiresExactPunishmentMatch());
            assertEquals(-1, annotation.setback());
            for (String language : List.of("en", "zh")) {
                String checkName = annotation.name();
                var config = (Map<?, ?>) readYaml("/config/" + language + ".yml").get(checkName);
                assertEquals(false, config.get("enabled"));
                assertEquals(false, config.get("debug-log"));
                var punishments = (Map<?, ?>) readYaml("/punishments/" + language + ".yml").get("Punishments");
                var group = (Map<?, ?>) punishments.get(checkName);
                assertEquals(List.of(checkName), group.get("checks"));
                assertEquals(List.of("1:1 [log]", "1:1 [alert]"), group.get("commands"));
            }
        }
    }

    private static Map<?, ?> readYaml(String path) throws IOException {
        try (var input = AimTrajectoryTest.class.getResourceAsStream(path)) {
            return new Yaml().load(input);
        }
    }

    private static List<Frame> periodFrames(double amplitude, boolean shiftValidation) {
        List<Frame> frames = new ArrayList<>();
        double yaw = TARGET_YAW;
        double pitch = 0;
        for (int tick = 0; tick < AimPeriodicity.WINDOW_TICKS; tick++) {
            double phase = 2 * Math.PI * tick / 20.08;
            if (tick % 10 != 5 && tick % 10 != 6) {
                double delta = (tick % 2 == 0 ? 1 : -1) * (2.1 + 0.30 * (tick % 5));
                yaw += delta;
                double coefficient = shiftValidation && tick >= 48 ? -amplitude : amplitude;
                pitch += (0.12 + coefficient * Math.sin(phase)) * delta + 0.20 * Math.cos(phase);
            }
            frames.add(new Frame(tick, MathUtil.wrapAngleTo180_float((float) quantize(yaw, QUANTUM)),
                    (float) quantize(pitch, QUANTUM), target(true), QUANTUM));
        }
        return frames;
    }

    private static double quantize(double angle, double quantum) {
        return Math.round(angle / quantum) * quantum;
    }

    private static List<Frame> turnFrames(int[] remaining) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < remaining.length; i++) {
            frames.add(new Frame(i, yawFor(remaining[i]), 0, target(i == remaining.length - 1), QUANTUM));
        }
        return frames;
    }

    private static float yawFor(int counts) {
        return MathUtil.wrapAngleTo180_float((float) (TARGET_YAW - counts * QUANTUM));
    }

    private static TargetAngles target(boolean hit) {
        return new TargetAngles(TARGET_YAW, 0, 0, 0, 0, hit, hit);
    }

    private static final class Replay {
        private final AimTrajectoryTracker tracker = new AimTrajectoryTracker();
        private final boolean checkPeriod;
        private final boolean checkBraking;
        private long now;

        private Replay(boolean checkPeriod, boolean checkBraking) {
            this.checkPeriod = checkPeriod;
            this.checkBraking = checkBraking;
        }

        private Reports period() {
            tracker.clearTarget();
            return periodOnTarget();
        }

        private Reports periodOnTarget() {
            tracker.recordAttack();
            Reports report = Reports.EMPTY;
            for (Frame frame : periodFrames(0.19, false)) {
                if (frame.tick() == 48) tracker.recordAttack();
                report = feed(frame.yaw(), frame.pitch(), frame.target());
                if (frame.tick() < 95) assertNull(report.period());
            }
            return report;
        }

        private Reports turn(int[] remaining, int attackOffset, boolean interrupted) {
            tracker.clearTarget();
            tracker.recordAttack();
            for (int i = 0; i < 3; i++) feed(yawFor(remaining[0]), 0, target(false));
            for (int i = 1; i < remaining.length; i++) {
                int offset = i - remaining.length + 1;
                feed(yawFor(remaining[i]), 0, target(offset == 0));
                if (offset == attackOffset) tracker.recordAttack();
            }
            for (int i = 1; i < 4; i++) {
                assertNull(feed(TARGET_YAW, 0, target(true)).braking());
                if (i == attackOffset) tracker.recordAttack();
                if (interrupted && i == 2) tracker.endSegment();
            }
            return feed(TARGET_YAW, 0, target(true));
        }

        private Reports feed(float yaw, float pitch, TargetAngles target) {
            assertTrue(tracker.hasTargetForNextTick());
            now += TimeUnit.MILLISECONDS.toNanos(50);
            return tracker.recordFrame(yaw, pitch, target, QUANTUM, checkPeriod, checkBraking, now);
        }
    }
}
