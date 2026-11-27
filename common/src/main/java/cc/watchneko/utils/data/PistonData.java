package cc.watchneko.utils.data;

import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3i;

import java.util.List;

public class PistonData {
    public final boolean isPush;
    public final boolean hasSlimeBlock;
    public final boolean hasHoneyBlock;
    public final BlockFace direction;
    public final Vector3i base;
    public final int lastTransactionSent;

    // Calculate if the player has no-push, and when to end the possibility of applying piston
    public int ticksOfPistonBeingAlive = 0;

    // The actual blocks pushed by the piston, plus the piston head itself
    public List<SimpleCollisionBox> boxes;

    public PistonData(Vector3i base, BlockFace direction, List<SimpleCollisionBox> pushedBlocks, int lastTransactionSent, boolean isPush, boolean hasSlimeBlock, boolean hasHoneyBlock) {
        this.base = base;
        this.direction = direction;
        this.boxes = pushedBlocks;
        this.lastTransactionSent = lastTransactionSent;
        this.isPush = isPush;
        this.hasSlimeBlock = hasSlimeBlock;
        this.hasHoneyBlock = hasHoneyBlock;
    }

    // We don't know when the piston has applied, or what stage of pushing it is on
    // Therefore, we need to use what we have - the number of movement packets.
    // 10 is a very cautious number
    public boolean tickIfGuaranteedFinished() {
        return ++ticksOfPistonBeingAlive >= 10;
    }
}
