package cc.watchneko.checks.impl.aim;

import cc.watchneko.utils.anticheat.update.AttackAnchor;
import cc.watchneko.utils.anticheat.update.AttackSignalListener;
import cc.watchneko.utils.math.MathUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class AimTrajectoryTracker implements AttackSignalListener {
    private static final int TARGET_TTL = 60;
    private static final long EVIDENCE_AGE = TimeUnit.SECONDS.toNanos(90);
    private static final int TURN_HISTORY = 8;

    private final Frame[] history = new Frame[AimPeriodicity.WINDOW_TICKS];
    private final Deque<Long> recentAttacks = new ArrayDeque<>();
    private final Deque<Long> periodMatches = new ArrayDeque<>(2);
    private final Deque<Long> brakeCorroboration = new ArrayDeque<>(2);
    private final Deque<TimedTurn> brakeHistory = new ArrayDeque<>(TURN_HISTORY);
    private AttackAnchor pendingAttack;
    private AimPeriodicity.Result lastPeriodMatch;
    private int nextFrame;
    private int frameCount;
    private int periodFrameCount;
    private long tick;
    private long lastAttack = -1;
    private long turnStart = -1;
    private long lastMovingTick;
    private double turnQuantum;
    private Turn pendingTurn;

    @Override
    public void onAttack(AttackAnchor anchor) {
        pendingAttack = anchor;
    }

    AttackAnchor finishAttack(boolean cancelled) {
        AttackAnchor attack = pendingAttack;
        pendingAttack = null;
        return cancelled ? null : attack;
    }

    void recordAttack() {
        lastAttack = tick;
        if (recentAttacks.isEmpty() || recentAttacks.getLast() != tick) recentAttacks.addLast(tick);
    }

    boolean hasTargetForNextTick() {
        return lastAttack >= 0 && tick - lastAttack < TARGET_TTL;
    }

    void endSegment() {
        Arrays.fill(history, null);
        nextFrame = 0;
        frameCount = 0;
        periodFrameCount = 0;
        turnStart = -1;
        pendingTurn = null;
        pendingAttack = null;
        recentAttacks.clear();
    }

    void clearTarget() {
        endSegment();
        lastAttack = -1;
    }

    void reset() {
        clearTarget();
        periodMatches.clear();
        brakeCorroboration.clear();
        brakeHistory.clear();
        lastPeriodMatch = null;
    }

    Reports recordFrame(float yaw, float pitch, TargetAngles target, double quantum,
                        boolean checkPeriod, boolean checkBraking, long now) {
        Frame frame = new Frame(++tick, yaw, pitch, target, quantum);
        history[nextFrame] = frame;
        nextFrame = (nextFrame + 1) % history.length;
        frameCount = Math.min(frameCount + 1, history.length);
        while (!recentAttacks.isEmpty() && recentAttacks.getFirst() < tick - 5) recentAttacks.removeFirst();

        AimPeriodicity.Result period = null;
        if (checkPeriod && ++periodFrameCount == AimPeriodicity.WINDOW_TICKS) {
            List<Frame> frames = copyFrames(tick - periodFrameCount + 1, tick);
            period = AimPeriodicity.evaluate(frames, frames.get(0).quantum());
            periodFrameCount = 0;
        }

        AimBraking.Result braking = finishTurn();
        observeTurn();
        if (period == null && braking == null) return Reports.EMPTY;

        discardExpired(now);
        if (period != null && period.matched()) {
            addRecentMatch(periodMatches, now);
            lastPeriodMatch = period;
        }
        if (braking != null && braking.qualified()) {
            if (checkPeriod && braking.matched()) addRecentMatch(brakeCorroboration, now);
            if (checkBraking) {
                if (brakeHistory.size() == TURN_HISTORY) brakeHistory.removeFirst();
                brakeHistory.addLast(new TimedTurn(now, braking));
            }
        }

        AimPeriodicity.Result flagPeriod = null;
        if (periodMatches.size() == 2 && brakeCorroboration.size() == 2) {
            flagPeriod = lastPeriodMatch;
            periodMatches.clear();
            brakeCorroboration.clear();
            lastPeriodMatch = null;
        }

        AimBraking.Result flagBraking = matchTurnHistory();
        if (flagBraking != null) brakeHistory.clear();
        return new Reports(period, braking, flagPeriod, flagBraking);
    }

    private void observeTurn() {
        if (frameCount < 4) return;

        Frame current = frameAt(tick);
        Frame previous = frameAt(tick - 1);
        if (turnStart < 0) {
            double quantum = previous.quantum();
            double tolerance = AimBraking.quietTolerance(quantum);
            Frame earlier = frameAt(tick - 2);
            if (!quiet(previous, current, tolerance) && quiet(earlier, previous, tolerance)
                    && quiet(frameAt(tick - 3), earlier, tolerance)) {
                turnStart = tick;
                lastMovingTick = tick;
                turnQuantum = quantum;
            }
            return;
        }

        if (!quiet(previous, current, AimBraking.quietTolerance(turnQuantum))) {
            lastMovingTick = tick;
        } else if (tick - lastMovingTick == 2) {
            long length = lastMovingTick - turnStart + 1;
            if (length >= AimBraking.MIN_TICKS && length <= AimBraking.MAX_TICKS) {
                pendingTurn = new Turn(turnStart - 1, lastMovingTick, turnQuantum);
            }
            turnStart = -1;
        }
    }

    private AimBraking.Result finishTurn() {
        // An attack after Flying is anchored to that tick. Wait until the last eligible tick has closed.
        if (pendingTurn == null || tick <= pendingTurn.endTick() + 3) return null;
        Turn turn = pendingTurn;
        pendingTurn = null;
        Iterator<Long> attacks = recentAttacks.iterator();
        while (attacks.hasNext()) {
            long attack = attacks.next();
            if (attack < turn.endTick() - 1) continue;
            if (attack > turn.endTick() + 3) break;
            attacks.remove();
            return AimBraking.evaluate(copyFrames(turn.startTick(), turn.endTick()), turn.quantum());
        }
        return AimBraking.Result.skipped("attack-window");
    }

    private Frame frameAt(long frameTick) {
        int age = (int) (tick - frameTick);
        return history[Math.floorMod(nextFrame - 1 - age, history.length)];
    }

    private List<Frame> copyFrames(long start, long end) {
        List<Frame> frames = new ArrayList<>((int) (end - start + 1));
        for (long frameTick = start; frameTick <= end; frameTick++) frames.add(frameAt(frameTick));
        return frames;
    }

    private static boolean quiet(Frame from, Frame to, double tolerance) {
        return Math.abs(MathUtil.getAngleDifference(to.yaw(), from.yaw())) <= tolerance
                && Math.abs(to.pitch() - from.pitch()) <= tolerance;
    }

    private void discardExpired(long now) {
        while (!periodMatches.isEmpty() && now - periodMatches.getFirst() > EVIDENCE_AGE) periodMatches.removeFirst();
        while (!brakeCorroboration.isEmpty() && now - brakeCorroboration.getFirst() > EVIDENCE_AGE) brakeCorroboration.removeFirst();
        while (!brakeHistory.isEmpty() && now - brakeHistory.getFirst().time() > EVIDENCE_AGE) brakeHistory.removeFirst();
    }

    private static void addRecentMatch(Deque<Long> matches, long now) {
        if (matches.size() == 2) matches.removeFirst();
        matches.addLast(now);
    }

    private AimBraking.Result matchTurnHistory() {
        if (brakeHistory.size() != TURN_HISTORY) return null;
        int small = 0;
        int large = 0;
        AimBraking.Result latestMatch = null;
        for (TimedTurn turn : brakeHistory) {
            if (!turn.result().matched()) continue;
            latestMatch = turn.result();
            if (turn.result().amplitude() < 30) small++;
            else large++;
        }
        return small > 0 && large > 0 && small + large >= 6 ? latestMatch : null;
    }

    record Frame(long tick, float yaw, float pitch, TargetAngles target, double quantum) {}

    record TargetAngles(float yaw, double minYawOffset, double maxYawOffset, double minPitch, double maxPitch,
                        boolean couldHit, boolean canReach) {
        double pitch() {
            return (minPitch + maxPitch) / 2;
        }
    }

    record Reports(AimPeriodicity.Result period, AimBraking.Result braking,
                   AimPeriodicity.Result flagPeriod, AimBraking.Result flagBraking) {
        static final Reports EMPTY = new Reports(null, null, null, null);
    }

    private record Turn(long startTick, long endTick, double quantum) {}

    private record TimedTurn(long time, AimBraking.Result result) {}
}
