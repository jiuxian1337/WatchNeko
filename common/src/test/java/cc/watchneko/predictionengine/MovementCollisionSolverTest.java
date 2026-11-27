package cc.watchneko.predictionengine;

import cc.watchneko.utils.collisions.datatypes.SimpleCollisionBox;
import cc.watchneko.utils.math.Vector3dm;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class MovementCollisionSolverTest {
    @Test
    void diagonalMovementClipsOnlyAfterTheFirstHorizontalAxisMoves() {
        var player = new SimpleCollisionBox(0, 0, 0, 0.6, 1.8, 0.6);
        var corner = new SimpleCollisionBox(1, 0, 1, 2, 2, 2);

        Vector3dm xFirst = MovementCollisionSolver.clip(player, List.of(corner), 1, 0, 1, true);
        Vector3dm zFirst = MovementCollisionSolver.clip(player, List.of(corner), 1, 0, 1, false);

        assertEquals(1, xFirst.getX());
        assertEquals(0.4, xFirst.getZ(), 1e-9);
        assertEquals(0.4, zFirst.getX(), 1e-9);
        assertEquals(1, zFirst.getZ());
    }

    @Test
    void modernStepUsesTheSlabHeightAndLegacyStepCanReturnToTheFloor() {
        var player = new SimpleCollisionBox(0.2, 0, 0.2, 0.8, 1.8, 0.8);
        var floor = new SimpleCollisionBox(-1, -1, -1, 4, 0, 2);
        var slab = new SimpleCollisionBox(1, 0, 0, 2, 0.5, 1);
        var boxes = List.of(floor, slab);
        Vector3dm direct = MovementCollisionSolver.clip(player, boxes, 2, -0.08, 0, true);

        Vector3dm old = MovementCollisionSolver.step(ClientVersion.V_1_8, player, boxes, direct, 2, -0.08, 0, 0.6f, true, true);
        Vector3dm modern = MovementCollisionSolver.step(ClientVersion.V_1_21_4, player, boxes, direct, 2, -0.08, 0, 0.6f, true, true);

        assertEquals(2, old.getX());
        assertEquals(0, old.getY(), 1e-9);
        assertEquals(2, modern.getX());
        assertEquals(0.5, modern.getY(), 1e-9);
    }

    @Test
    void anEntityOnlyOccupiesOnePossiblePositionInEachCandidate() {
        var sweep = new SimpleCollisionBox(0, 0, 0, 4, 2, 4);
        var one = new SimpleCollisionBox(1, 0, 1, 2, 1, 2);
        var two = new SimpleCollisionBox(2, 0, 1, 3, 1, 2);
        var outside = new SimpleCollisionBox(5, 0, 5, 6, 1, 6);

        var worlds = MovementEntityCollisions.add(List.of(List.of()), List.of(one, one.copy(), two, outside), sweep);
        assertEquals(3, worlds.size());
        assertEquals(0, worlds.get(0).size());
        assertSame(one, worlds.get(1).get(0));
        assertSame(two, worlds.get(2).get(0));
    }

    @Test
    void entityPositionCombinationsAreBoundedAtSixteen() {
        var sweep = new SimpleCollisionBox(0, 0, 0, 4, 2, 4);
        var positions = List.of(new SimpleCollisionBox(1, 0, 1, 2, 1, 2),
                new SimpleCollisionBox(2, 0, 1, 3, 1, 2));
        List<List<SimpleCollisionBox>> worlds = List.of(List.of());
        for (int entity = 0; entity < 4; entity++) worlds = MovementEntityCollisions.add(worlds, positions, sweep);
        assertEquals(16, worlds.size());
        assertEquals(4, worlds.get(0).size());
        assertNull(MovementEntityCollisions.add(worlds, positions, sweep));
    }
}
