package cc.watchneko.analysis.aim.trajectory;

import cc.watchneko.checks.impl.aim.AimV;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.math.MathUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;

public class DetectionContext {
    private final Queue<Rotation> rotationHistory = new ArrayDeque<>(100);
    private final Queue<Rotation> rotationDeltaHistory = new ArrayDeque<>(100);
    private final Queue<Double> reactionTimes = new ArrayDeque<>(50);
    private final PlayerData playerData;
    private ArrayList<Rotation> rotations;
    private double distanceToTarget;
    private Rotation currentRotation;
    private Rotation lastRotation;
    private float deltaYaw;
    private float deltaPitch;
    private float optimalYaw;
    private float avgYaw;
    private float avgPitch;
    private float maxYaw;
    private float maxPitch;
    private float lastDeltaYaw;
    private float lastDeltaPitch;
    private float lastOptimalYaw;
    private long lastMovementUpdateTime;
    private double previousYaw;
    private double previousPitch;
    private double previousYawVelocity;
    private double previousPitchVelocity;
    private double yawAcceleration;
    private double pitchAcceleration;

    public DetectionContext(PlayerData playerData) {
        this.playerData = playerData;
    }

    public void updateMovementData(double currentYaw, double currentPitch, long currentTime) {
        if (lastMovementUpdateTime == 0) {
            lastMovementUpdateTime = currentTime;
            previousYaw = currentYaw;
            previousPitch = currentPitch;
            return;
        }
        double deltaTime = (currentTime - lastMovementUpdateTime) / 1000.0;
        if (deltaTime <= 0) {
            return;
        }
        double currentYawVelocity = calculateAngularVelocity(previousYaw, currentYaw, deltaTime);
        double currentPitchVelocity = calculateAngularVelocity(previousPitch, currentPitch, deltaTime);
        yawAcceleration = (currentYawVelocity - previousYawVelocity) / deltaTime;
        pitchAcceleration = (currentPitchVelocity - previousPitchVelocity) / deltaTime;
        previousYawVelocity = currentYawVelocity;
        previousYaw = currentYaw;
        previousPitchVelocity = currentPitchVelocity;
        previousPitch = currentPitch;
        lastMovementUpdateTime = currentTime;
    }

    private double calculateAngularVelocity(double previousAngle, double currentAngle, double deltaTime) {
        double delta = currentAngle - previousAngle;
        delta = (delta + 180) % 360 - 180;
        return delta / deltaTime;
    }

    public void flagDetection(AimDetectionStrategy strategy, String reason) {
        if (playerData.checkManager.getCheck(AimV.class).flagAndAlert(strategy.getCheckName() + " | " + reason)) {
            playerData.mitigateDamage();
        }
    }

    public float getAngleDifference(float yaw1, float yaw2) {
        return Rotation.getAngleDifference(yaw1, yaw2);
    }

    public long getGcd(long a, long b) {
        return MathUtil.getGcd(a, b);
    }

    public void updateTargetInfo(double distance) {
        this.distanceToTarget = distance;
    }

    public void updateRotation(Rotation rotation) {
        this.lastRotation = this.currentRotation;
        this.currentRotation = rotation;
        if (rotationHistory.size() >= 100) {
            rotationHistory.poll();
        }
        rotationHistory.add(rotation);
    }

    public void updateRotationDelta(Rotation rotation) {
        if (rotationDeltaHistory.size() >= 100) {
            rotationDeltaHistory.poll();
        }
        rotationDeltaHistory.add(rotation);
        avgYaw = avgPitch = maxYaw = maxPitch = 0;
        for (Rotation rotation1 : rotationDeltaHistory) {
            avgYaw += Math.abs(rotation1.getYaw());
            avgPitch += Math.abs(rotation1.getPitch());
            if (Math.abs(rotation1.getYaw()) > maxYaw) {
                maxYaw = Math.abs(rotation1.getYaw());
            }
            if (Math.abs(rotation1.getPitch()) > maxPitch) {
                maxPitch = Math.abs(rotation1.getPitch());
            }
        }
        avgYaw /= rotationDeltaHistory.size();
        avgPitch /= rotationDeltaHistory.size();
    }

    public boolean isAimingAtTarget() {
        return distanceToTarget < 4;
    }

    public ArrayList<Rotation> getRotations() {
        return rotations;
    }

    public void setRotations(ArrayList<Rotation> rotations) {
        this.rotations = rotations;
    }

    public float getDeltaYaw() {
        return deltaYaw;
    }

    public void setDeltaYaw(float deltaYaw) {
        this.deltaYaw = deltaYaw;
    }

    public float getDeltaPitch() {
        return deltaPitch;
    }

    public void setDeltaPitch(float deltaPitch) {
        this.deltaPitch = deltaPitch;
    }

    public float getOptimalYaw() {
        return optimalYaw;
    }

    public void setOptimalYaw(float optimalYaw) {
        this.optimalYaw = optimalYaw;
    }

    public float getLastDeltaYaw() {
        return lastDeltaYaw;
    }

    public void setLastDeltaYaw(float lastDeltaYaw) {
        this.lastDeltaYaw = lastDeltaYaw;
    }

    public float getLastDeltaPitch() {
        return lastDeltaPitch;
    }

    public void setLastDeltaPitch(float lastDeltaPitch) {
        this.lastDeltaPitch = lastDeltaPitch;
    }

    public float getLastOptimalYaw() {
        return lastOptimalYaw;
    }

    public void setLastOptimalYaw(float lastOptimalYaw) {
        this.lastOptimalYaw = lastOptimalYaw;
    }

    public double getPreviousYawVelocity() {
        return previousYawVelocity;
    }

    public double getPreviousPitchVelocity() {
        return previousPitchVelocity;
    }

    public double getYawAcceleration() {
        return yawAcceleration;
    }

    public double getPitchAcceleration() {
        return pitchAcceleration;
    }
}
