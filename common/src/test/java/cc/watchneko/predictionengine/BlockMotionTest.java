package cc.watchneko.predictionengine;

import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockMotionTest {
    @Test
    void pistonGeometryAndPushAdvanceWithClientTicks() {
        var destination = new SimpleCollisionBox(1, 0, 0, 2, 1, 1);
        var player = new SimpleCollisionBox(1.25, 0, 0.1, 1.85, 1.8, 0.7);
        var piston = new BlockMotion.Animation(List.of(destination), BlockFace.EAST, false, 0, 1);

        assertEquals(0, piston.at(destination, 0).minX);
        assertEquals(0, piston.pushDistance(player));
        assertFalse(piston.advance());
        assertEquals(0.26, piston.pushDistance(player), 1e-9);
        assertFalse(piston.advance());
        assertEquals(0.51, piston.pushDistance(player), 1e-9);
        assertFalse(piston.advance());
        assertEquals(0, piston.pushDistance(player));
    }

    @Test
    void aLidExpandsAlongItsFacingAndOnlyPushesWhileOpening() {
        var shape = new SimpleCollisionBox(0, 0, 0, 1, 1, 1);
        var player = new SimpleCollisionBox(-0.6, 1, 0, 0.1, 2.8, 0.6);
        var lid = new BlockMotion.Animation(List.of(shape), BlockFace.UP, true, 0, 0.5);

        assertEquals(1.5, lid.at(shape, 0.5).maxY);
        assertEquals(1, lid.at(shape, 0.5).maxX);
        assertFalse(lid.advance());
        assertEquals(0.05, lid.pushDistance(player), 1e-9);

        var closing = new BlockMotion.Animation(List.of(shape), BlockFace.UP, true, 0.05, 0);
        assertFalse(closing.advance());
        assertEquals(0, closing.pushDistance(player));
        assertTrue(closing.advance());
    }

    @Test
    void multiplePistonsShareOnePerAxisLimit() {
        assertEquals(0.16, BlockMotion.pistonDelta(0.35, 0.25), 1e-9);
        assertEquals(-0.16, BlockMotion.pistonDelta(-0.35, -0.25), 1e-9);
        assertEquals(0.2, BlockMotion.pistonDelta(-0.35, 0.2), 1e-9);
    }
}
