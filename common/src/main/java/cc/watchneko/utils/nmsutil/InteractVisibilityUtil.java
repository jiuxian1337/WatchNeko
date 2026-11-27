package cc.watchneko.utils.nmsutil;

import cc.watchneko.player.PlayerData;
import cc.watchneko.predictionengine.MovementBlockShapes;
import cc.watchneko.utils.collisions.CollisionData;
import cc.watchneko.utils.collisions.datatypes.CollisionBox;
import cc.watchneko.utils.collisions.datatypes.ComplexCollisionBox;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import cc.watchneko.utils.data.HitData;
import cc.watchneko.utils.data.Pair;
import cc.watchneko.utils.math.GrimMath;
import cc.watchneko.utils.math.Vector3dm;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.defaulttags.BlockTags;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@UtilityClass
public class InteractVisibilityUtil {
    private static final int RAY_STEPS = 30;
    private static final double EPSILON = 1.0E-7D;

    public static boolean isBlockVisible(PlayerData player, Vector3i blockPos, BlockFace face) {
        return player.compensatedWorld.withSelectionSnapshot(() -> traceBlockVisibility(player, blockPos, face));
    }

    private static boolean traceBlockVisibility(PlayerData player, Vector3i blockPos, BlockFace face) {
        final double[] eyeHeights = player.getPossibleEyeHeights();
        final SimpleCollisionBox targetBox = new SimpleCollisionBox(blockPos);
        final SimpleCollisionBox[] collisionBoxes = new SimpleCollisionBox[ComplexCollisionBox.DEFAULT_MAX_COLLISION_BOX_SIZE];

        for (double eyeHeight : eyeHeights) {
            final double eyeX = player.x;
            final double eyeY = player.y + eyeHeight;
            final double eyeZ = player.z;

            if (sameBlock(blockPos, GrimMath.floor(eyeX), GrimMath.floor(eyeY), GrimMath.floor(eyeZ))) {
                return true;
            }

            if (!isRayBlocked(player, eyeX, eyeY, eyeZ, centerOf(blockPos), targetBox, collisionBoxes)) {
                return true;
            }

            if (walkBlockNeighbors(player, eyeX, eyeY, eyeZ, eyeHeight, blockPos, face, targetBox, collisionBoxes)) {
                return true;
            }
        }

        return false;
    }

    public static boolean isEntityVisible(PlayerData player, SimpleCollisionBox targetBox) {
        return player.compensatedWorld.withSelectionSnapshot(() -> traceEntityVisibility(player, targetBox));
    }

    private static boolean traceEntityVisibility(PlayerData player, SimpleCollisionBox targetBox) {
        final double[] eyeHeights = player.getPossibleEyeHeights();
        final int targetBlockX = GrimMath.floor((targetBox.minX + targetBox.maxX) / 2.0D);
        final int targetBlockY = GrimMath.floor((targetBox.minY + targetBox.maxY) / 2.0D);
        final int targetBlockZ = GrimMath.floor((targetBox.minZ + targetBox.maxZ) / 2.0D);
        final Vector3i targetBlock = new Vector3i(targetBlockX, targetBlockY, targetBlockZ);
        final SimpleCollisionBox[] collisionBoxes = new SimpleCollisionBox[ComplexCollisionBox.DEFAULT_MAX_COLLISION_BOX_SIZE];

        for (double eyeHeight : eyeHeights) {
            final double eyeX = player.x;
            final double eyeY = player.y + eyeHeight;
            final double eyeZ = player.z;

            if (targetBox.isIntersected(new SimpleCollisionBox(eyeX, eyeY, eyeZ, eyeX, eyeY, eyeZ))) {
                return true;
            }

            final Vector3d targetCenter = new Vector3d(
                    (targetBox.minX + targetBox.maxX) / 2.0D,
                    (targetBox.minY + targetBox.maxY) / 2.0D,
                    (targetBox.minZ + targetBox.maxZ) / 2.0D
            );

            if (!isRayBlocked(player, eyeX, eyeY, eyeZ, targetCenter, targetBox, collisionBoxes)) {
                return true;
            }

            if (walkEntityNeighbors(player, eyeX, eyeY, eyeZ, eyeHeight, targetBlock, targetBox, collisionBoxes)) {
                return true;
            }
        }

        return false;
    }

    public static boolean isBoxVisible(PlayerData player, SimpleCollisionBox targetBox) {
        return isEntityVisible(player, targetBox);
    }

    private static boolean walkBlockNeighbors(PlayerData player, double eyeX, double eyeY, double eyeZ, double eyeHeight,
                                              Vector3i sourceBlock, BlockFace face, SimpleCollisionBox targetBox,
                                              SimpleCollisionBox[] collisionBoxes) {
        final Set<Long> visited = new HashSet<>();
        final RichAxisData axisData = new RichAxisData(priorityForFace(face), Direction.NONE);
        final Vector3i eyeBlock = new Vector3i(GrimMath.floor(eyeX), GrimMath.floor(eyeY), GrimMath.floor(eyeZ));
        Vector3i current = sourceBlock;
        Vector3d direction = normalize(eyeX - current.getX(), eyeY - current.getY(), eyeZ - current.getZ());
        boolean mightEdgeInteraction = true;
        int steps = 0;

        while (steps++ < RAY_STEPS) {
            boolean found = false;
            for (Vector3i neighbor : getNeighborsInDirection(current, direction, eyeX, eyeY, eyeZ, axisData)) {
                if (!correctDir(neighbor.getY(), sourceBlock.getY(), eyeBlock.getY())) {
                    continue;
                }
                if (!visited.add(pack(neighbor))) {
                    continue;
                }
                if (sameBlock(neighbor, eyeBlock)) {
                    return true;
                }
                if (!canPassThrough(player, current, neighbor, direction, eyeX, eyeY, eyeZ, eyeHeight, null, null, mightEdgeInteraction, axisData)) {
                    continue;
                }
                if (!isRayBlocked(player, eyeX, eyeY, eyeZ, centerOf(neighbor), targetBox, collisionBoxes)) {
                    return true;
                }

                current = neighbor;
                direction = normalize(eyeX - current.getX(), eyeY - current.getY(), eyeZ - current.getZ());
                found = true;
                break;
            }
            mightEdgeInteraction = false;
            if (!found) {
                break;
            }
        }

        return false;
    }

    private static boolean walkEntityNeighbors(PlayerData player, double eyeX, double eyeY, double eyeZ, double eyeHeight,
                                               Vector3i sourceBlock, SimpleCollisionBox targetBox,
                                               SimpleCollisionBox[] collisionBoxes) {
        final Set<Long> visited = new HashSet<>();
        final RichAxisData axisData = new RichAxisData(Axis.NONE, Direction.NONE);
        final Vector3i eyeBlock = new Vector3i(GrimMath.floor(eyeX), GrimMath.floor(eyeY), GrimMath.floor(eyeZ));
        final Vector3i sCollidingBox = new Vector3i(GrimMath.floor(targetBox.minX), GrimMath.floor(targetBox.minY), GrimMath.floor(targetBox.minZ));
        final Vector3i eCollidingBox = new Vector3i(GrimMath.floor(targetBox.maxX), GrimMath.floor(targetBox.maxY), GrimMath.floor(targetBox.maxZ));
        Vector3i current = sourceBlock;
        Vector3d direction = normalize(eyeX - current.getX(), eyeY - current.getY(), eyeZ - current.getZ());
        int steps = 0;

        while (steps++ < RAY_STEPS) {
            boolean found = false;
            for (Vector3i neighbor : getNeighborsInDirection(current, direction, eyeX, eyeY, eyeZ, axisData)) {
                if (!correctDir(neighbor.getY(), sourceBlock.getY(), eyeBlock.getY(), sCollidingBox.getY(), eCollidingBox.getY())) {
                    continue;
                }
                if (!visited.add(pack(neighbor))) {
                    continue;
                }
                if (sameBlock(neighbor, eyeBlock)) {
                    return true;
                }
                if (!canPassThrough(player, current, neighbor, direction, eyeX, eyeY, eyeZ, eyeHeight, sCollidingBox, eCollidingBox, false, axisData)) {
                    continue;
                }
                if (!isRayBlocked(player, eyeX, eyeY, eyeZ, centerOf(neighbor), targetBox, collisionBoxes)) {
                    return true;
                }

                current = neighbor;
                direction = normalize(eyeX - current.getX(), eyeY - current.getY(), eyeZ - current.getZ());
                found = true;
                break;
            }
            if (!found) {
                break;
            }
        }

        return false;
    }

    private static boolean isRayBlocked(PlayerData player, double eyeX, double eyeY, double eyeZ,
                                        Vector3d target, SimpleCollisionBox targetBox, SimpleCollisionBox[] collisionBoxes) {
        final int eyeBlockX = GrimMath.floor(eyeX);
        final int eyeBlockY = GrimMath.floor(eyeY);
        final int eyeBlockZ = GrimMath.floor(eyeZ);
        final Vector3d start = new Vector3d(eyeX, eyeY, eyeZ);

        HitData hit = WorldRayTrace.traverseBlocks(player, start, target, (state, pos) -> {
            if (pos.getX() == eyeBlockX && pos.getY() == eyeBlockY && pos.getZ() == eyeBlockZ) {
                return null;
            }
            if (isInsideTarget(pos, targetBox)) {
                return null;
            }
            return checkBlockHit(player, state, pos, eyeX, eyeY, eyeZ, target.getX(), target.getY(), target.getZ(), collisionBoxes);
        });

        return hit != null;
    }

    private static HitData checkBlockHit(PlayerData player, WrappedBlockState state, Vector3i pos,
                                         double startX, double startY, double startZ,
                                         double endX, double endY, double endZ, SimpleCollisionBox[] collisionBoxes) {
        if (state.getType().isAir()) {
            return null;
        }

        final CollisionBox collisionBox = player.movementSimulation.isUnified()
                ? MovementBlockShapes.selection(player, null, state, pos.getX(), pos.getY(), pos.getZ())
                : CollisionData.getData(state.getType()).getMovementCollisionBox(
                player, player.getClientVersion(), state, pos.getX(), pos.getY(), pos.getZ());
        if (collisionBox.isNull()) {
            return null;
        }

        final int size = collisionBox.downCast(collisionBoxes);
        final Vector3dm start = new Vector3dm(startX, startY, startZ);
        final Vector3dm end = new Vector3dm(endX, endY, endZ);

        for (int i = 0; i < size; i++) {
            final SimpleCollisionBox box = collisionBoxes[i];
            if (ReachUtils.isVecInside(box, start)) {
                continue;
            }
            final Pair<Vector3dm, BlockFace> intercept = ReachUtils.calculateIntercept(box, start, end);
            if (intercept.first() != null) {
                return new HitData(pos, intercept.first(), intercept.second(), state);
            }
        }

        return null;
    }

    private static boolean canPassThrough(PlayerData player, Vector3i lastBlock, Vector3i nextBlock, Vector3d direction,
                                          double eyeX, double eyeY, double eyeZ, double eyeHeight,
                                          Vector3i sCollidingBox, Vector3i eCollidingBox,
                                          boolean mightEdgeInteraction, RichAxisData axisData) {
        final WrappedBlockState nextState = player.compensatedWorld.getBlock(nextBlock);
        final SimpleCollisionBox[] nextBoxes = getRelativeBoxes(player, nextState, nextBlock);
        if (nextBoxes.length == 0 || canPassThroughWorkAround(player, nextState, nextBlock, direction, eyeY, eyeHeight)) {
            return true;
        }

        final int dx = nextBlock.getX() - lastBlock.getX();
        final int dy = nextBlock.getY() - lastBlock.getY();
        final int dz = nextBlock.getZ() - lastBlock.getZ();
        final SimpleCollisionBox[] lastBoxes = getRelativeBoxes(player, player.compensatedWorld.getBlock(lastBlock), lastBlock);

        final Bounds nextBounds = flatten(nextBoxes);
        final Bounds lastBounds = flatten(lastBoxes);

        if (axisData != null && lastBounds != null) {
            if (dy != 0) {
                if (isFullYAndZ(nextBounds) && isFullYAndZ(lastBounds) && rangeContains(nextBounds.minX, lastBounds.minX, nextBounds.maxX, lastBounds.maxX)) {
                    axisData.dirExclusion = nextBounds.minX == 0.0D ? Direction.X_NEG : nextBounds.maxX == 1.0D ? Direction.X_POS : Direction.NONE;
                }
                if (isFullYAndX(nextBounds) && isFullYAndX(lastBounds) && rangeContains(nextBounds.minZ, lastBounds.minZ, nextBounds.maxZ, lastBounds.maxZ)) {
                    axisData.dirExclusion = nextBounds.minZ == 0.0D ? Direction.Z_NEG : nextBounds.maxZ == 1.0D ? Direction.Z_POS : Direction.NONE;
                }
            }
            if (dx != 0) {
                if (isFullXAndZ(nextBounds) && isFullXAndZ(lastBounds) && rangeContains(nextBounds.minY, lastBounds.minY, nextBounds.maxY, lastBounds.maxY)) {
                    axisData.dirExclusion = nextBounds.minY == 0.0D ? Direction.Y_NEG : nextBounds.maxY == 1.0D ? Direction.Y_POS : Direction.NONE;
                }
                if (isFullYAndX(nextBounds) && isFullYAndX(lastBounds) && rangeContains(nextBounds.minZ, lastBounds.minZ, nextBounds.maxZ, lastBounds.maxZ)) {
                    axisData.dirExclusion = nextBounds.minZ == 0.0D ? Direction.Z_NEG : nextBounds.maxZ == 1.0D ? Direction.Z_POS : Direction.NONE;
                }
            }
            if (dz != 0) {
                if (isFullXAndZ(nextBounds) && isFullXAndZ(lastBounds) && rangeContains(nextBounds.minY, lastBounds.minY, nextBounds.maxY, lastBounds.maxY)) {
                    axisData.dirExclusion = nextBounds.minY == 0.0D ? Direction.Y_NEG : nextBounds.maxY == 1.0D ? Direction.Y_POS : Direction.NONE;
                }
                if (isFullYAndZ(nextBounds) && isFullYAndZ(lastBounds) && rangeContains(nextBounds.minX, lastBounds.minX, nextBounds.maxX, lastBounds.maxX)) {
                    axisData.dirExclusion = nextBounds.minX == 0.0D ? Direction.X_NEG : nextBounds.maxX == 1.0D ? Direction.X_POS : Direction.NONE;
                }
            }
        }

        if (sCollidingBox != null && eCollidingBox != null && isInsideAABBIncludeEdges(nextBlock, sCollidingBox, eCollidingBox)) {
            return true;
        }

        final double stepX = dx * 0.99D;
        final double stepY = dy * 0.99D;
        final double stepZ = dz * 0.99D;
        final Axis collidingAxis = getCollidingAxis(lastBlock, nextBlock, stepX, stepY, stepZ, nextBoxes);
        if (collidingAxis == null) {
            return true;
        }

        if (Materials.isStairs(nextState.getType())) {
            if (dy == 0) {
                final int eyeBlockY = GrimMath.floor(eyeY);
                if (eyeBlockY > nextBlock.getY() && nextBounds.maxY == 1.0D) {
                    return false;
                }
                if (eyeBlockY < nextBlock.getY() && nextBounds.minY == 0.0D) {
                    return false;
                }
            }
            if (dx != 0) {
                for (int i = 1; i < nextBoxes.length; i++) {
                    final SimpleCollisionBox box = nextBoxes[i];
                    if (box.minY == 0.0D && box.maxY == 1.0D && (dx < 0 ? box.maxZ == 1.0D : box.minZ == 0.0D)) {
                        return false;
                    }
                }
            }
            if (dz != 0) {
                for (int i = 1; i < nextBoxes.length; i++) {
                    final SimpleCollisionBox box = nextBoxes[i];
                    if (box.minX == 0.0D && box.maxX == 1.0D && (dz < 0 ? box.maxY == 1.0D : box.minY == 0.0D)) {
                        return false;
                    }
                }
            }
        }

        if (dy != 0) {
            if (isFullXAndZ(nextBounds)) {
                if (axisData != null && (dy > 0 ? nextBounds.minY != 0.0D : nextBounds.maxY != 1.0D)) {
                    axisData.dirExclusion = dy > 0 ? Direction.Y_POS : Direction.Y_NEG;
                    return true;
                }
                return collidingAxis != Axis.Y_AXIS;
            }
            return mightEdgeInteraction || lastBounds == null || (dy > 0 ? lastBounds.maxY != 1.0D || nextBounds.minY != 0.0D : lastBounds.minY != 0.0D || nextBounds.maxY != 1.0D)
                    || ((nextBounds.minX != 0.0D || lastBounds.minX != 0.0D || nextBounds.maxX != 1.0D || lastBounds.maxX != 1.0D
                    || !equal(coveredSpace(lastBounds.minZ, lastBounds.maxZ, nextBounds.minZ, nextBounds.maxZ), 1.0D, 0.001D))
                    && (nextBounds.minZ != 0.0D || lastBounds.minZ != 0.0D || nextBounds.maxZ != 1.0D || lastBounds.maxZ != 1.0D
                    || !equal(coveredSpace(lastBounds.minX, lastBounds.maxX, nextBounds.minX, nextBounds.maxX), 1.0D, 0.001D)));
        }

        if (dx != 0) {
            if (isFullYAndZ(nextBounds)) {
                if (axisData != null && (dx > 0 ? nextBounds.minX != 0.0D : nextBounds.maxX != 1.0D)) {
                    axisData.dirExclusion = dx > 0 ? Direction.X_POS : Direction.X_NEG;
                    return true;
                }
                return collidingAxis != Axis.X_AXIS;
            }
            return mightEdgeInteraction || lastBounds == null || (dx > 0 ? lastBounds.maxX != 1.0D || nextBounds.minX != 0.0D : lastBounds.minX != 0.0D || nextBounds.maxX != 1.0D)
                    || ((nextBounds.minY != 0.0D || lastBounds.minY != 0.0D || nextBounds.maxY != 1.0D || lastBounds.maxY != 1.0D
                    || !equal(coveredSpace(lastBounds.minZ, lastBounds.maxZ, nextBounds.minZ, nextBounds.maxZ), 1.0D, 0.001D))
                    && (nextBounds.minZ != 0.0D || lastBounds.minZ != 0.0D || nextBounds.maxZ != 1.0D || lastBounds.maxZ != 1.0D
                    || !equal(coveredSpace(lastBounds.minY, lastBounds.maxY, nextBounds.minY, nextBounds.maxY), 1.0D, 0.001D)));
        }

        if (dz != 0) {
            if (isFullYAndX(nextBounds)) {
                if (axisData != null && (dz > 0 ? nextBounds.minZ != 0.0D : nextBounds.maxZ != 1.0D)) {
                    axisData.dirExclusion = dz > 0 ? Direction.Z_POS : Direction.Z_NEG;
                    return true;
                }
                return collidingAxis != Axis.Z_AXIS;
            }
            return mightEdgeInteraction || lastBounds == null || (dz > 0 ? lastBounds.maxZ != 1.0D || nextBounds.minZ != 0.0D : lastBounds.minZ != 0.0D || nextBounds.maxZ != 1.0D)
                    || ((nextBounds.minY != 0.0D || lastBounds.minY != 0.0D || nextBounds.maxY != 1.0D || lastBounds.maxY != 1.0D
                    || !equal(coveredSpace(lastBounds.minX, lastBounds.maxX, nextBounds.minX, nextBounds.maxX), 1.0D, 0.001D))
                    && (nextBounds.minX != 0.0D || lastBounds.minX != 0.0D || nextBounds.maxX != 1.0D || lastBounds.maxX != 1.0D
                    || !equal(coveredSpace(lastBounds.minY, lastBounds.maxY, nextBounds.minY, nextBounds.maxY), 1.0D, 0.001D)));
        }

        return false;
    }

    private static Axis getCollidingAxis(Vector3i lastBlock, Vector3i nextBlock, double stepX, double stepY, double stepZ, SimpleCollisionBox[] nextBoxes) {
        final Vector3dm start = new Vector3dm(lastBlock.getX(), lastBlock.getY(), lastBlock.getZ());
        final Vector3dm end = new Vector3dm(nextBlock.getX() + stepX, nextBlock.getY() + stepY, nextBlock.getZ() + stepZ);
        Vector3dm bestHit = null;
        BlockFace bestFace = null;

        for (SimpleCollisionBox box : nextBoxes) {
            final SimpleCollisionBox shifted = box.copy().offset(nextBlock.getX(), nextBlock.getY(), nextBlock.getZ());
            if (ReachUtils.isVecInside(shifted, start)) {
                continue;
            }
            final Pair<Vector3dm, BlockFace> intercept = ReachUtils.calculateIntercept(shifted, start, end);
            if (intercept.first() == null) {
                continue;
            }
            if (bestHit == null || start.distanceSquared(intercept.first()) < start.distanceSquared(bestHit)) {
                bestHit = intercept.first();
                bestFace = intercept.second();
            }
        }

        if (bestFace == null) {
            return null;
        }

        return switch (bestFace) {
            case EAST, WEST -> Axis.X_AXIS;
            case UP, DOWN -> Axis.Y_AXIS;
            case NORTH, SOUTH -> Axis.Z_AXIS;
            default -> Axis.NONE;
        };
    }

    private static SimpleCollisionBox[] getRelativeBoxes(PlayerData player, WrappedBlockState state, Vector3i pos) {
        final CollisionBox collisionBox = player.movementSimulation.isUnified()
                ? MovementBlockShapes.selection(player, null, state, pos.getX(), pos.getY(), pos.getZ())
                    .offset(-pos.getX(), -pos.getY(), -pos.getZ())
                : CollisionData.getData(state.getType()).fetch(
                    player, player.getClientVersion(), state, pos.getX(), pos.getY(), pos.getZ());
        if (collisionBox.isNull()) {
            return new SimpleCollisionBox[0];
        }

        final List<SimpleCollisionBox> boxes = new ArrayList<>();
        collisionBox.downCast(boxes);
        return boxes.toArray(new SimpleCollisionBox[0]);
    }

    private static boolean canPassThroughWorkAround(PlayerData player, WrappedBlockState state, Vector3i blockPos, Vector3d direction,
                                                    double eyeY, double eyeHeight) {
        if (Materials.isWater(player.getClientVersion(), state) || state.getType() == StateTypes.LAVA) {
            return true;
        }

        if (Materials.isFence(state.getType()) || Materials.isWall(state.getType()) || BlockTags.GLASS_PANES.contains(state.getType())) {
            final int entityBlockY = GrimMath.floor(eyeY - eyeHeight);
            return direction.getY() > 0.76D && entityBlockY > blockPos.getY()
                    || direction.getY() < -0.76D && entityBlockY < blockPos.getY();
        }

        return false;
    }

    private static List<Vector3i> getNeighborsInDirection(Vector3i source, Vector3d direction,
                                                          double eyeX, double eyeY, double eyeZ, RichAxisData axisData) {
        final List<Vector3i> neighbors = new ArrayList<>(3);
        final int stepY = direction.getY() > 0.0D ? 1 : direction.getY() < 0.0D ? -1 : 0;
        final int stepX = direction.getX() > 0.0D ? 1 : direction.getX() < 0.0D ? -1 : 0;
        final int stepZ = direction.getZ() > 0.0D ? 1 : direction.getZ() < 0.0D ? -1 : 0;

        Axis priorityAxis = Axis.NONE;
        Direction excludeDir = Direction.NONE;
        boolean allowX = true;
        boolean allowY = true;
        boolean allowZ = true;

        if (axisData != null) {
            priorityAxis = axisData.priority;
            excludeDir = axisData.dirExclusion;
            axisData.priority = Axis.NONE;
            axisData.dirExclusion = Direction.NONE;
            allowX = !(excludeDir == Direction.X_NEG && stepX < 0 || excludeDir == Direction.X_POS && stepX > 0);
            allowY = !(excludeDir == Direction.Y_NEG && stepY < 0 || excludeDir == Direction.Y_POS && stepY > 0);
            allowZ = !(excludeDir == Direction.Z_NEG && stepZ < 0 || excludeDir == Direction.Z_POS && stepZ > 0);
        }

        if (priorityAxis == Axis.X_AXIS) {
            neighbors.add(new Vector3i(source.getX() + stepX, source.getY(), source.getZ()));
            if (allowZ)
                neighbors.add(new Vector3i(source.getX(), source.getY(), source.getZ() + stepZ));
            if (allowY)
                neighbors.add(new Vector3i(source.getX(), source.getY() + stepY, source.getZ()));
            return neighbors;
        }
        if (priorityAxis == Axis.Y_AXIS) {
            neighbors.add(new Vector3i(source.getX(), source.getY() + stepY, source.getZ()));
            if (allowX)
                neighbors.add(new Vector3i(source.getX() + stepX, source.getY(), source.getZ()));
            if (allowZ)
                neighbors.add(new Vector3i(source.getX(), source.getY(), source.getZ() + stepZ));
            return neighbors;
        }
        if (priorityAxis == Axis.Z_AXIS) {
            neighbors.add(new Vector3i(source.getX(), source.getY(), source.getZ() + stepZ));
            if (allowX)
                neighbors.add(new Vector3i(source.getX() + stepX, source.getY(), source.getZ()));
            if (allowY)
                neighbors.add(new Vector3i(source.getX(), source.getY() + stepY, source.getZ()));
            return neighbors;
        }

        final double manhattanY = manhattan(source.getX(), source.getY() + stepY, source.getZ(), eyeX, eyeY, eyeZ);
        final double manhattanZ = manhattan(source.getX(), source.getY(), source.getZ() + stepZ, eyeX, eyeY, eyeZ);
        final double manhattanX = manhattan(source.getX() + stepX, source.getY(), source.getZ(), eyeX, eyeY, eyeZ);

        if (manhattanY <= manhattanX && manhattanY <= manhattanZ && Math.abs(direction.getY()) >= 0.5D) {
            if (allowY)
                neighbors.add(new Vector3i(source.getX(), source.getY() + stepY, source.getZ()));
            if (manhattanX < manhattanZ) {
                if (allowX)
                    neighbors.add(new Vector3i(source.getX() + stepX, source.getY(), source.getZ()));
                if (allowZ)
                    neighbors.add(new Vector3i(source.getX(), source.getY(), source.getZ() + stepZ));
            } else {
                if (allowZ)
                    neighbors.add(new Vector3i(source.getX(), source.getY(), source.getZ() + stepZ));
                if (allowX)
                    neighbors.add(new Vector3i(source.getX() + stepX, source.getY(), source.getZ()));
            }
            return neighbors;
        }

        if (manhattanX < manhattanZ) {
            if (allowX)
                neighbors.add(new Vector3i(source.getX() + stepX, source.getY(), source.getZ()));
            if (allowZ)
                neighbors.add(new Vector3i(source.getX(), source.getY(), source.getZ() + stepZ));
            if (allowY)
                neighbors.add(new Vector3i(source.getX(), source.getY() + stepY, source.getZ()));
        } else {
            if (allowZ)
                neighbors.add(new Vector3i(source.getX(), source.getY(), source.getZ() + stepZ));
            if (allowX)
                neighbors.add(new Vector3i(source.getX() + stepX, source.getY(), source.getZ()));
            if (allowY)
                neighbors.add(new Vector3i(source.getX(), source.getY() + stepY, source.getZ()));
        }

        return neighbors;
    }

    private static boolean correctDir(int neighbor, int block, int eyeBlock) {
        final int d = eyeBlock - block;
        if (d > 0) {
            return neighbor <= eyeBlock;
        } else if (d < 0) {
            return neighbor >= eyeBlock;
        }
        return neighbor == eyeBlock;
    }

    private static boolean correctDir(int neighbor, int block, int eyeBlock, int min, int max) {
        if (neighbor >= min && neighbor <= max) {
            return true;
        }
        return correctDir(neighbor, block, eyeBlock);
    }

    private static boolean isInsideTarget(Vector3i blockPos, SimpleCollisionBox targetBox) {
        for (Vector3i targetPos : SimpleCollisionBox.betweenClosed(targetBox)) {
            if (sameBlock(blockPos, targetPos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInsideAABBIncludeEdges(Vector3i pos, Vector3i min, Vector3i max) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    private static Axis priorityForFace(BlockFace face) {
        if (Math.abs(face.getModX()) > 0) {
            return Axis.X_AXIS;
        }
        if (Math.abs(face.getModY()) > 0) {
            return Axis.Y_AXIS;
        }
        if (Math.abs(face.getModZ()) > 0) {
            return Axis.Z_AXIS;
        }
        return Axis.NONE;
    }

    private static Vector3d centerOf(Vector3i pos) {
        return new Vector3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private static Vector3d normalize(double x, double y, double z) {
        final double length = Math.sqrt(x * x + y * y + z * z);
        if (length < EPSILON) {
            return new Vector3d(0.0D, 0.0D, 0.0D);
        }
        return new Vector3d(x / length, y / length, z / length);
    }

    private static double manhattan(double x, double y, double z, double eyeX, double eyeY, double eyeZ) {
        return Math.abs(x - eyeX) + Math.abs(y - eyeY) + Math.abs(z - eyeZ);
    }

    private static boolean sameBlock(Vector3i first, Vector3i second) {
        return first.getX() == second.getX() && first.getY() == second.getY() && first.getZ() == second.getZ();
    }

    private static boolean sameBlock(Vector3i first, int x, int y, int z) {
        return first.getX() == x && first.getY() == y && first.getZ() == z;
    }

    private static long pack(Vector3i pos) {
        return ((long) pos.getX() & 0x3FFFFFFL) << 38 | ((long) pos.getZ() & 0x3FFFFFFL) << 12 | ((long) pos.getY() & 0xFFFL);
    }

    private static boolean rangeContains(double min, double lastMin, double max, double lastMax) {
        return min <= lastMin + EPSILON && max >= lastMax - EPSILON;
    }

    private static double coveredSpace(double minA, double maxA, double minB, double maxB) {
        final double overlap = Math.max(0.0D, Math.min(maxA, maxB) - Math.max(minA, minB));
        return (maxA - minA) + (maxB - minB) - overlap;
    }

    private static boolean equal(double a, double b, double tolerance) {
        return Math.abs(a - b) <= tolerance;
    }

    private static boolean isFullXAndZ(Bounds bounds) {
        return bounds.minX == 0.0D && bounds.maxX == 1.0D && bounds.minZ == 0.0D && bounds.maxZ == 1.0D;
    }

    private static boolean isFullYAndZ(Bounds bounds) {
        return bounds.minY == 0.0D && bounds.maxY == 1.0D && bounds.minZ == 0.0D && bounds.maxZ == 1.0D;
    }

    private static boolean isFullYAndX(Bounds bounds) {
        return bounds.minY == 0.0D && bounds.maxY == 1.0D && bounds.minX == 0.0D && bounds.maxX == 1.0D;
    }

    private static Bounds flatten(SimpleCollisionBox[] boxes) {
        if (boxes.length == 0) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (SimpleCollisionBox box : boxes) {
            minX = Math.min(minX, box.minX);
            minY = Math.min(minY, box.minY);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX);
            maxY = Math.max(maxY, box.maxY);
            maxZ = Math.max(maxZ, box.maxZ);
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private enum Axis {
        X_AXIS,
        Y_AXIS,
        Z_AXIS,
        NONE
    }

    private enum Direction {
        X_NEG,
        X_POS,
        Y_NEG,
        Y_POS,
        Z_NEG,
        Z_POS,
        NONE
    }

    private record Bounds(double minX, double minY, double minZ, double maxX, double maxY,
                          double maxZ) {
    }

    private static final class RichAxisData {
        private Axis priority;
        private Direction dirExclusion;

        private RichAxisData(Axis priority, Direction dirExclusion) {
            this.priority = priority;
            this.dirExclusion = dirExclusion;
        }
    }
}
