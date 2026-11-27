package cc.watchneko.predictionengine;

import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.collisions.CollisionData;
import cc.watchneko.utils.collisions.HitboxData;
import cc.watchneko.utils.collisions.datatypes.CollisionBox;
import cc.watchneko.utils.collisions.datatypes.NoCollisionBox;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;

/** Versioned block geometry shared by movement, selection and block interaction. */
public final class MovementBlockShapes {
    private MovementBlockShapes() {}

    public static CollisionBox movement(PlayerData player, WrappedBlockState block, int x, int y, int z, double feet) {
        ClientVersion version = player.getClientVersion();
        StateType type = block.getType();
        if (type == StateTypes.POWDER_SNOW && version.isNewerThanOrEquals(ClientVersion.V_1_17)) {
            MovementFrame frame = player.movementSimulation.currentFrame();
            double fallDistance = frame == null ? player.fallDistance : frame.fallDistance();
            if (fallDistance > 2.5) return new SimpleCollisionBox(x, y, z, x + 1, y + 0.9, z + 1);
            var boots = player.inventory.getBoots();
            if (feet > y + 1 - 1e-5 && boots != null && boots.getType() == ItemTypes.LEATHER_BOOTS
                    && !player.isSneaking && !player.inVehicle()) {
                return new SimpleCollisionBox(x, y, z, x + 1, y + 1, z + 1, true);
            }
            return NoCollisionBox.INSTANCE;
        }
        if (type == StateTypes.SCAFFOLDING && version.isNewerThanOrEquals(ClientVersion.V_1_14)) {
            if (feet > y + 1 - 1e-5 && !player.isSneaking) {
                return new SimpleCollisionBox(x, y + 0.875, z, x + 1, y + 1, z + 1);
            }
            if (block.isBottom() && block.getDistance() != 0 && feet > y - 1e-5) {
                return new SimpleCollisionBox(x, y, z, x + 1, y + 0.125, z + 1);
            }
            return NoCollisionBox.INSTANCE;
        }
        return CollisionData.getData(type).getMovementCollisionBox(player, version, block, x, y, z);
    }

    public static CollisionBox selection(PlayerData player, StateType heldItem, WrappedBlockState block, int x, int y, int z) {
        return HitboxData.getBlockHitbox(player, heldItem, player.getClientVersion(), block, false, x, y, z);
    }

    public static CollisionBox interaction(PlayerData player, StateType heldItem, WrappedBlockState block, int x, int y, int z) {
        return HitboxData.getBlockHitbox(player, heldItem, player.getClientVersion(), block, true, x, y, z);
    }
}
