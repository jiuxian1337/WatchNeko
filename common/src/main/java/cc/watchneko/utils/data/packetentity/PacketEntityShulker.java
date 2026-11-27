package cc.watchneko.utils.data.packetentity;

import cc.watchneko.player.PlayerData;
import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.world.BlockFace;

import java.util.UUID;

public class PacketEntityShulker extends PacketEntity {
    public BlockFace facing = BlockFace.DOWN;
    private float peek;
    private float previousPeek;
    private float targetPeek;

    public PacketEntityShulker(PlayerData player, UUID uuid, EntityType type, double x, double y, double z) {
        super(player, uuid, type, x, y, z);
    }

    public void setPeek(byte amount) {
        targetPeek = Math.max(0, Math.min(100, amount)) / 100f;
    }

    public void tickPeek() {
        previousPeek = peek;
        peek = peek < targetPeek ? Math.min(targetPeek, peek + 0.05f) : Math.max(targetPeek, peek - 0.05f);
    }

    public boolean appendMovementBoxes(java.util.List<SimpleCollisionBox> boxes) {
        int beginning = boxes.size();
        if (!super.appendMovementBoxes(boxes)) return false;
        BlockFace opening = facing.getOppositeFace();
        for (int i = beginning, end = boxes.size(); i < end; i++) {
            SimpleCollisionBox closed = boxes.get(i);
            boxes.set(i, expandPeek(closed, opening, peek));
            if (previousPeek != peek) boxes.add(expandPeek(closed, opening, previousPeek));
        }
        return true;
    }

    private SimpleCollisionBox expandPeek(SimpleCollisionBox box, BlockFace direction, float value) {
        double extension = (0.5 - Math.sin((0.5 + value) * Math.PI) * 0.5) * (box.maxX - box.minX);
        return box.copy().expandToCoordinate(direction.getModX() * extension, direction.getModY() * extension,
                direction.getModZ() * extension);
    }
}
