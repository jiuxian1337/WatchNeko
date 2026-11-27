package cc.watchneko.predictionengine;

import ac.grim.grimac.api.config.ConfigManager;
import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.anticheat.update.PositionUpdate;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import cc.watchneko.utils.math.Vector3dm;
import cc.watchneko.utils.nmsutil.Collisions;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import java.util.ArrayList;
import java.util.List;

/** The negotiated engine mode and client tick state, both owned by the connection. */
public final class MovementSimulation {
    private final boolean unified;
    private long tick;
    private boolean positionReported;
    private boolean vehicleRotationPending;
    private boolean readsTranslatedTickEnd;
    private boolean unresolvedCollision;
    private int pushersBefore;
    private int pushersAfter;
    private int previousPushersAfter;
    private Vector3dm candidateShove;
    private Vector3dm selectedTravel;
    private MovementFrame frame;
    private Result lastResult;
    private List<MovementEntityCollisions.HardEntity> hardEntities;
    private SimpleCollisionBox collisionStart;
    private SimpleCollisionBox collisionQuery;
    private final List<SimpleCollisionBox> collisionBlocks = new ArrayList<>();

    public MovementSimulation(ConfigManager config) {
        this("unified".equalsIgnoreCase(config.getStringElse("movement.engine", "unified")));
    }

    MovementSimulation(boolean unified) {
        this.unified = unified;
    }

    public boolean isUnified() {
        return unified;
    }

    public String engineName() {
        return unified ? "unified" : "legacy";
    }

    public long tick() {
        return tick;
    }

    public boolean readsTranslatedTickEnd() {
        return readsTranslatedTickEnd;
    }

    public void setReadsTranslatedTickEnd(boolean readsTranslatedTickEnd) {
        this.readsTranslatedTickEnd = readsTranslatedTickEnd;
    }

    public MovementFrame currentFrame() {
        return frame;
    }

    void begin(PlayerData player, PositionUpdate position, boolean reportedPosition) {
        if (reportedPosition) positionReported = true;
        unresolvedCollision = false;
        candidateShove = null;
        selectedTravel = null;
        pushersBefore = 0;
        pushersAfter = 0;
        if (position.isTeleport()) {
            previousPushersAfter = 0;
            vehicleRotationPending = false;
        }
        frame = MovementFrame.capture(player, position, tick, reportedPosition);
    }

    void finishFrame() {
        frame = null;
        candidateShove = null;
        selectedTravel = null;
        hardEntities = null;
        collisionStart = null;
        collisionQuery = null;
        collisionBlocks.clear();
    }

    List<MovementEntityCollisions.HardEntity> hardEntities(PlayerData player) {
        if (frame == null) return MovementEntityCollisions.snapshot(player);
        if (hardEntities == null) hardEntities = MovementEntityCollisions.snapshot(player);
        return hardEntities;
    }

    List<SimpleCollisionBox> collisionBlocks(PlayerData player, SimpleCollisionBox start, SimpleCollisionBox query) {
        if (collisionStart != start || !contains(collisionQuery, query)) {
            collisionStart = start;
            collisionQuery = query.copy().expand(0.125);
            collisionBlocks.clear();
            Collisions.getCollisionBoxes(player, collisionQuery, collisionBlocks, false, start.minY);
        }
        return collisionBlocks;
    }

    private static boolean contains(SimpleCollisionBox outer, SimpleCollisionBox inner) {
        return outer.minX <= inner.minX && outer.minY <= inner.minY && outer.minZ <= inner.minZ
                && outer.maxX >= inner.maxX && outer.maxY >= inner.maxY && outer.maxZ >= inner.maxZ;
    }

    public boolean hasPosition() {
        return positionReported;
    }

    public boolean endsTick(PacketTypeCommon packet, boolean explicitTickEnd, boolean mounted) {
        if (explicitTickEnd || !mounted) {
            vehicleRotationPending = false;
            return explicitTickEnd ? packet == PacketType.Play.Client.CLIENT_TICK_END
                    : WrapperPlayClientPlayerFlying.isFlying(packet);
        }
        if (packet == PacketType.Play.Client.VEHICLE_MOVE) {
            vehicleRotationPending = false;
            return true;
        }
        if (packet != PacketType.Play.Client.PLAYER_ROTATION) return false;

        // A second rotation closes a passenger tick if the client did not send a vehicle move.
        boolean previousTickEnded = vehicleRotationPending;
        vehicleRotationPending = true;
        return previousTickEnded;
    }

    public void finishTick() {
        tick++;
        positionReported = false;
    }

    void unresolvedCollision() {
        unresolvedCollision = true;
    }

    void candidateShove(Vector3dm shove) {
        candidateShove = shove;
    }

    public Vector3dm candidateShove() {
        return candidateShove;
    }

    public void selectTravel(Vector3dm travel) {
        selectedTravel = travel;
    }

    public Vector3dm selectedTravel(Vector3dm displacement) {
        return selectedTravel == null ? displacement : selectedTravel;
    }

    public boolean canJudgeMovement() {
        return !unified || frame == null || frame.reportedPosition() && !unresolvedCollision;
    }

    public void entityPushers(int before, int after) {
        pushersBefore = before;
        pushersAfter = after;
    }

    public Vector3dm applyEntityPush(Vector3dm velocity, Vector3dm target) {
        if (!unified || frame == null || frame.vehicle()) return velocity;
        double radius = (pushersBefore + previousPushersAfter) * (Math.sqrt(2) * 0.05);
        if (radius == 0) return velocity;
        double x = target.getX() - velocity.getX();
        double z = target.getZ() - velocity.getZ();
        double length = Math.hypot(x, z);
        if (length > radius) {
            x *= radius / length;
            z *= radius / length;
        }
        return velocity.add(new Vector3dm(x, 0, z));
    }

    void commit(PlayerData player, boolean checked) {
        lastResult = new Result(frame, copy(player.predictedVelocity.vector), copy(player.clientVelocity),
                player.onGround, player.fallDistance, checked);
        previousPushersAfter = pushersAfter;
    }

    public Result lastResult() {
        return lastResult;
    }

    private static Vector3d copy(Vector3dm vector) {
        return new Vector3d(vector.getX(), vector.getY(), vector.getZ());
    }

    public record Result(MovementFrame frame, Vector3d displacement, Vector3d nextVelocity, boolean onGround,
                         double fallDistance, boolean checked) {}
}
