package cc.watchneko.predictionengine;

import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import cc.watchneko.utils.data.VectorData;
import cc.watchneko.utils.math.Vector3dm;
import cc.watchneko.utils.nmsutil.Collisions;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import it.unimi.dsi.fastutil.floats.FloatArraySet;
import it.unimi.dsi.fastutil.floats.FloatArrays;

import java.util.ArrayList;
import java.util.List;

/** Clips one physics candidate against block shapes and the feasible hard entity positions. */
public final class MovementCollisionSolver {
    private MovementCollisionSolver() {}

    public static Vector3dm solve(PlayerData player, double x, double y, double z, double previousY, VectorData candidate) {
        SimpleCollisionBox start = player.boundingBox;
        float step = player.getMaxUpStep();
        ClientVersion version = player.getClientVersion();
        boolean xFirst = version.isOlderThan(ClientVersion.V_1_14) || Math.abs(x) >= Math.abs(z);
        BlockMotion motion = player.compensatedWorld.blockMotion;
        Vector3dm shove = null;
        if (candidate != null) player.movementSimulation.candidateShove(null);
        if (x == 0 && y == 0 && z == 0 && (candidate == null || !motion.isActive())) {
            if (candidate != null) player.uncertaintyHandler.isStepMovement = false;
            return new Vector3dm();
        }
        List<SimpleCollisionBox> blocks = null;
        if (candidate != null && motion.isActive()) {
            blocks = new ArrayList<>();
            Collisions.getCollisionBoxes(player, start.copy().expand(0.51), blocks, false, start.minY);
            Vector3dm displacement = motion.pushBeforeTravel(start, blocks, xFirst);
            blocks.clear();
            if (displacement.lengthSquared() > 0) {
                shove = displacement;
                start = start.copy().offset(shove.getX(), shove.getY(), shove.getZ());
                player.movementSimulation.candidateShove(shove);
            }
        }
        SimpleCollisionBox sweep = start.copy().expandToCoordinate(x, y, z);
        if (step > 0 && (x != 0 || z != 0)) {
            sweep.maxY = Math.max(sweep.maxY, start.maxY + step);
            if (version.isNewerThanOrEquals(ClientVersion.V_1_21)) {
                sweep.minY = Math.min(sweep.minY, start.minY - 1.0E-5F);
            }
        }
        if (candidate != null && !motion.isActive() && player.movementSimulation.currentFrame() != null) {
            blocks = player.movementSimulation.collisionBlocks(player, start, sweep);
        } else {
            if (blocks == null) blocks = new ArrayList<>();
            Collisions.getCollisionBoxes(player, sweep, blocks, false, start.minY);
            if (motion.isActive()) motion.collect(sweep, blocks);
        }
        List<List<SimpleCollisionBox>> worlds = candidate == null ? List.of(List.of())
                : MovementEntityCollisions.worlds(player.movementSimulation.hardEntities(player), sweep);
        if (worlds == null) {
            player.movementSimulation.unresolvedCollision();
            worlds = List.of(List.of());
        }

        Vector3dm best = null;
        double bestError = Double.MAX_VALUE;
        boolean stepped = false;
        for (List<SimpleCollisionBox> entities : worlds) {
            List<SimpleCollisionBox> colliders = blocks;
            if (!entities.isEmpty()) {
                colliders = new ArrayList<>(blocks.size() + entities.size());
                colliders.addAll(blocks);
                colliders.addAll(entities);
            }
            Vector3dm direct = clip(start, colliders, x, y, z, xFirst);
            Vector3dm clipped = tryStep(player, start, colliders, direct, x, y, z, previousY, step, candidate, xFirst);
            Vector3dm displacement = shove == null ? clipped : clipped.clone().add(shove);
            double error = displacement.distanceSquared(player.actualMovement);
            if (direct == clipped && shove == null && player.wouldCollisionResultFlagGroundSpoof(y, clipped.getY())) error += 1;
            if (best == null || error < bestError) {
                best = clipped;
                bestError = error;
                stepped = direct != clipped;
            }
        }
        if (candidate != null) player.uncertaintyHandler.isStepMovement = stepped;
        return best;
    }

    private static Vector3dm tryStep(PlayerData player, SimpleCollisionBox start, List<SimpleCollisionBox> colliders,
                                      Vector3dm direct, double x, double y, double z, double previousY,
                                      float step, VectorData candidate, boolean xFirst) {
        if (step <= 0 || (direct.getX() == x && direct.getZ() == z)) return direct;
        var setback = player.getSetbackTeleportUtil().getRequiredSetBack();
        if (setback != null && setback.getTicksComplete() == 1) return direct;
        boolean down = direct.getY() != y && y < 0;
        boolean canStep = player.lastOnGround || down
                || player.pointThreeEstimator.closeEnoughToGroundToStepWithPointThree(candidate, previousY);
        if (!canStep) return direct;
        return step(player.getClientVersion(), start, colliders, direct, x, y, z, step, down, xFirst);
    }

    static Vector3dm step(ClientVersion version, SimpleCollisionBox start, List<SimpleCollisionBox> colliders,
                          Vector3dm direct, double x, double y, double z, float step, boolean down, boolean xFirst) {
        if (version.isNewerThanOrEquals(ClientVersion.V_1_21)) {
            double alreadyMoved = down ? direct.getY() : 0;
            var landed = start.copy().offset(0, alreadyMoved, 0);
            var probe = landed.copy().expandToCoordinate(x, step, z);
            if (!down) probe.minY -= 1.0E-5F;
            List<SimpleCollisionBox> stepBoxes = new ArrayList<>();
            for (SimpleCollisionBox collider : colliders) {
                if (collider.isIntersected(probe)) stepBoxes.add(collider);
            }
            for (float height : stepHeights(stepBoxes, landed.minY, step, direct.getY())) {
                Vector3dm attempt = clip(landed, stepBoxes, x, height, z, xFirst);
                if (horizontal(attempt) > horizontal(direct)) return attempt.setY(attempt.getY() + alreadyMoved);
            }
            return direct;
        }

        Vector3dm lifted = clip(start, colliders, x, step, z, xFirst);
        Vector3dm clearance = clip(start.copy().expandToCoordinate(x, 0, z), colliders, 0, step, 0, xFirst);
        if (clearance.getY() < step) {
            Vector3dm alternate = clip(start.copy().offset(0, clearance.getY(), 0), colliders, x, 0, z, xFirst).add(clearance);
            if (horizontal(alternate) > horizontal(lifted)) lifted = alternate;
        }
        if (horizontal(lifted) <= horizontal(direct)) return direct;
        var raised = start.copy().offset(lifted.getX(), lifted.getY(), lifted.getZ());
        double returnY = -lifted.getY() + (version.isNewerThanOrEquals(ClientVersion.V_1_14) ? y : 0);
        return lifted.add(clip(raised, colliders, 0, returnY, 0, xFirst));
    }

    private static float[] stepHeights(List<SimpleCollisionBox> boxes, double feet, float maximum, double clippedY) {
        FloatArraySet heights = new FloatArraySet();
        for (SimpleCollisionBox box : boxes) {
            for (double edge : box.getYPointPositions()) {
                float height = (float) (edge - feet);
                if (height >= 0 && height <= maximum && height != (float) clippedY) heights.add(height);
            }
        }
        float[] sorted = heights.toFloatArray();
        FloatArrays.unstableSort(sorted);
        return sorted;
    }

    static Vector3dm clip(SimpleCollisionBox start, List<SimpleCollisionBox> boxes, double x, double y, double z, boolean xFirst) {
        SimpleCollisionBox box = start.copy();
        for (SimpleCollisionBox collider : boxes) y = collider.collideY(box, y);
        box.offset(0, y, 0);
        if (xFirst) {
            for (SimpleCollisionBox collider : boxes) x = collider.collideX(box, x);
            box.offset(x, 0, 0);
            for (SimpleCollisionBox collider : boxes) z = collider.collideZ(box, z);
        } else {
            for (SimpleCollisionBox collider : boxes) z = collider.collideZ(box, z);
            box.offset(0, 0, z);
            for (SimpleCollisionBox collider : boxes) x = collider.collideX(box, x);
        }
        return new Vector3dm(x, y, z);
    }

    private static double horizontal(Vector3dm vector) {
        return vector.getX() * vector.getX() + vector.getZ() * vector.getZ();
    }
}
