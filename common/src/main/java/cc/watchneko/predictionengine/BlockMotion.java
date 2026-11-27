package cc.watchneko.predictionengine;

import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import cc.watchneko.utils.latency.CompensatedWorld;
import cc.watchneko.utils.math.GrimMath;
import cc.watchneko.utils.math.Vector3dm;
import cc.watchneko.utils.nmsutil.Materials;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.StringJoiner;

/** Packet-confirmed moving block shapes on a single client timeline. */
public final class BlockMotion {
    private final PlayerData player;
    private final LinkedHashMap<Vector3i, Animation> animations = new LinkedHashMap<>();
    private final LinkedHashMap<Vector3i, Structure> structures = new LinkedHashMap<>();
    private final LinkedHashMap<Vector3i, Long> modeledPistons = new LinkedHashMap<>();

    public BlockMotion(PlayerData player) {
        this.player = player;
    }

    public void captureStructure(Vector3i base, boolean extending, List<CompensatedWorld.SentBlock> sources) {
        structures.put(base, new Structure(extending, sources, player.movementSimulation.tick()));
    }

    public void blockAction(Vector3i pos, StateType type, int action, int value) {
        WrappedBlockState state = player.compensatedWorld.getBlock(pos);
        if (Materials.isShulker(state.getType())) {
            Animation lid = animations.get(pos);
            if (lid == null || !lid.lid) {
                lid = new Animation(List.of(new SimpleCollisionBox(pos)), state.getFacing(), true, 0, 0);
                animations.put(pos, lid);
            }
            lid.target = value > 0 ? 0.5 : 0;
            return;
        }
        if ((type != StateTypes.PISTON && type != StateTypes.STICKY_PISTON)
                || (action != 0 && action != 1 && action != 2) || (value & 7) > 5) return;

        BlockFace facing = BlockFace.getBlockFaceByValue(value & 7);
        boolean extending = action == 0;
        BlockFace travel = extending ? facing : facing.getOppositeFace();
        Structure structure = structures.remove(pos);
        if (action != 2 && structure != null && structure.extending == extending) {
            for (CompensatedWorld.SentBlock source : structure.sources) {
                WrappedBlockState moved = WrappedBlockState.getByGlobalId(CompensatedWorld.blockVersion, source.stateId());
                if (!moved.getType().isAir() && moved.getType() != StateTypes.MOVING_PISTON) {
                    Vector3i destination = relative(source.position(), travel);
                    piston(destination, moved, travel, 0);
                }
            }
        }
        var head = StateTypes.PISTON_HEAD.createBlockState(CompensatedWorld.blockVersion);
        head.setFacing(facing);
        piston(extending ? relative(pos, facing) : pos, head, travel, 0);
        modeledPistons.put(pos, player.movementSimulation.tick());
    }

    public void blockEntityData(Vector3i pos, NBTCompound tag) {
        if (tag == null || player.compensatedWorld.getBlock(pos).getType() != StateTypes.MOVING_PISTON) return;
        Number facing = tag.getNumberTagValueOrNull("facing");
        Number progress = tag.getNumberTagValueOrNull("progress");
        if (facing == null || progress == null || facing.intValue() < 0 || facing.intValue() > 5
                || !Double.isFinite(progress.doubleValue())) return;

        WrappedBlockState moved = pistonBlock(tag);
        if (moved == null || moved.getType().isAir()) return;
        BlockFace direction = BlockFace.getBlockFaceByValue(facing.intValue());
        if (tag.getNumberTagValueOrDefault("extending", 1).intValue() == 0) direction = direction.getOppositeFace();
        piston(pos, moved, direction, Math.max(0, Math.min(1, progress.doubleValue())));
    }

    private WrappedBlockState pistonBlock(NBTCompound tag) {
        NBTCompound state = tag.getCompoundTagOrNull("blockState");
        if (state == null) {
            Number legacy = tag.getNumberTagValueOrNull("blockId");
            if (legacy == null) return null;
            int data = tag.getNumberTagValueOrDefault("blockData", 0).intValue() & 15;
            return WrappedBlockState.getByGlobalId(CompensatedWorld.blockVersion, legacy.intValue() << 4 | data);
        }
        String name = state.getStringTagValueOrNull("Name");
        if (name == null) return null;
        NBTCompound properties = state.getCompoundTagOrNull("Properties");
        if (properties != null) {
            var values = new StringJoiner(",", name + "[", "]").setEmptyValue(name);
            properties.getTags().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
                if (entry.getValue() instanceof NBTString str) values.add(entry.getKey() + "=" + str.getValue());
            });
            name = values.toString();
        }
        return WrappedBlockState.getByString(CompensatedWorld.blockVersion, name);
    }

    private void piston(Vector3i pos, WrappedBlockState state, BlockFace direction, double progress) {
        List<SimpleCollisionBox> shape = new ArrayList<>();
        MovementBlockShapes.movement(player, state, pos.x, pos.y, pos.z, player.lastY).downCast(shape);
        if (!shape.isEmpty()) animations.put(pos, new Animation(shape, direction, false, progress, 1));
    }

    private static Vector3i relative(Vector3i pos, BlockFace direction) {
        return new Vector3i(pos.x + direction.getModX(), pos.y + direction.getModY(), pos.z + direction.getModZ());
    }

    public void blockReplaced(Vector3i position, WrappedBlockState state) {
        Animation animation = animations.get(position);
        if (animation != null && (animation.lid ? !Materials.isShulker(state.getType())
                : state.getType() != StateTypes.MOVING_PISTON)) animations.remove(position);
    }

    public boolean hasLid(Vector3i pos) {
        Animation animation = animations.get(pos);
        return animation != null && animation.lid;
    }

    public boolean hasPiston(Vector3i pos) {
        return modeledPistons.containsKey(pos);
    }

    public boolean isActive() {
        return !animations.isEmpty();
    }

    public void collect(SimpleCollisionBox sweep, List<SimpleCollisionBox> into) {
        for (Animation animation : animations.values()) {
            for (SimpleCollisionBox part : animation.shape) {
                SimpleCollisionBox box = animation.at(part, animation.current);
                if (box.isIntersected(sweep)) into.add(box);
            }
        }
    }

    public Vector3dm pushBeforeTravel(SimpleCollisionBox original, List<SimpleCollisionBox> staticBlocks, boolean xFirst) {
        SimpleCollisionBox playerBox = original.copy();
        Vector3dm total = new Vector3dm();
        Vector3dm pistonDeltas = new Vector3dm();
        for (Animation animation : animations.values()) {
            double push = animation.pushDistance(playerBox);
            if (push <= 0) continue;
            double x = push * animation.direction.getModX();
            double y = push * animation.direction.getModY();
            double z = push * animation.direction.getModZ();
            if (!animation.lid) {
                x = pistonDelta(pistonDeltas.getX(), x);
                y = pistonDelta(pistonDeltas.getY(), y);
                z = pistonDelta(pistonDeltas.getZ(), z);
                pistonDeltas.setX(pistonDeltas.getX() + x);
                pistonDeltas.setY(pistonDeltas.getY() + y);
                pistonDeltas.setZ(pistonDeltas.getZ() + z);
            }
            Vector3dm clipped = MovementCollisionSolver.clip(playerBox, staticBlocks, x, y, z, xFirst);
            total.add(clipped);
            playerBox.offset(clipped.getX(), clipped.getY(), clipped.getZ());
        }
        return total;
    }

    static double pistonDelta(double alreadyPushed, double requested) {
        return GrimMath.clamp(alreadyPushed + requested, -0.51, 0.51) - alreadyPushed;
    }

    private static double overlapInDirection(SimpleCollisionBox body, SimpleCollisionBox moved, BlockFace direction) {
        return switch (direction) {
            case EAST -> moved.maxX - body.minX;
            case WEST -> body.maxX - moved.minX;
            case UP -> moved.maxY - body.minY;
            case DOWN -> body.maxY - moved.minY;
            case SOUTH -> moved.maxZ - body.minZ;
            case NORTH -> body.maxZ - moved.minZ;
            default -> 0;
        };
    }

    public void endTick() {
        if (animations.isEmpty() && structures.isEmpty() && modeledPistons.isEmpty()) return;
        Iterator<Animation> iterator = animations.values().iterator();
        while (iterator.hasNext()) {
            Animation animation = iterator.next();
            if (animation.advance()) iterator.remove();
        }
        structures.values().removeIf(structure -> player.movementSimulation.tick() - structure.tick > 4);
        modeledPistons.values().removeIf(tick -> player.movementSimulation.tick() - tick > 5);
    }

    public void removeColumn(int x, int z) {
        animations.keySet().removeIf(pos -> pos.x >> 4 == x && pos.z >> 4 == z);
        structures.keySet().removeIf(pos -> pos.x >> 4 == x && pos.z >> 4 == z);
        modeledPistons.keySet().removeIf(pos -> pos.x >> 4 == x && pos.z >> 4 == z);
    }

    public void clear() {
        animations.clear();
        structures.clear();
        modeledPistons.clear();
    }

    private record Structure(boolean extending, List<CompensatedWorld.SentBlock> sources, long tick) {}

    static final class Animation {
        private final List<SimpleCollisionBox> shape;
        private final BlockFace direction;
        private final boolean lid;
        private double previous, current, target;
        private int age;

        Animation(List<SimpleCollisionBox> shape, BlockFace direction, boolean lid, double progress, double target) {
            this.shape = shape;
            this.direction = direction;
            this.lid = lid;
            this.previous = this.current = progress;
            this.target = target;
        }

        SimpleCollisionBox at(SimpleCollisionBox part, double progress) {
            double offset = lid ? progress : progress - 1.0;
            double x = offset * direction.getModX(), y = offset * direction.getModY(), z = offset * direction.getModZ();
            return lid ? part.copy().expandToCoordinate(x, y, z) : part.copy().offset(x, y, z);
        }

        double pushDistance(SimpleCollisionBox body) {
            double advance = current - previous;
            if (advance <= 0) return 0;
            double push = 0;
            for (SimpleCollisionBox part : shape) {
                SimpleCollisionBox end = at(part, current);
                if (end.isIntersected(body)) push = Math.max(push, overlapInDirection(body, end, direction));
            }
            double epsilon = lid ? 0 : 0.01;
            return push <= 0 ? 0 : Math.min(advance, push) + epsilon;
        }

        boolean advance() {
            previous = current;
            double rate = lid ? 0.05 : 0.5;
            current = current < target ? Math.min(target, current + rate) : Math.max(target, current - rate);
            return lid ? previous == 0 && current == 0 : ++age > 5;
        }
    }
}
