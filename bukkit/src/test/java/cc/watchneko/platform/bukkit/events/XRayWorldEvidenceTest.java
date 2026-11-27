package cc.watchneko.platform.bukkit.events;

import cc.watchneko.checks.impl.xray.MiningGeometry;
import cc.watchneko.checks.impl.xray.XRaySettings;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static cc.watchneko.checks.impl.xray.MiningSample.Discovery.FIRST;
import static cc.watchneko.checks.impl.xray.MiningSample.Discovery.PREVIOUS;
import static cc.watchneko.checks.impl.xray.MiningSample.Environment.ENCLOSED;
import static cc.watchneko.checks.impl.xray.MiningSample.Environment.OPEN;
import static cc.watchneko.checks.impl.xray.MiningSample.Environment.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class XRayWorldEvidenceTest {
    private static final String ORE = "diamond_ore";
    private static final XRayBlockView.MaterialState STONE = new XRayBlockView.MaterialState("stone", true);
    private static final XRayBlockView.MaterialState DIAMOND = new XRayBlockView.MaterialState(ORE, true);
    private static final XRayBlockView.MaterialState AIR = new XRayBlockView.MaterialState("air", false);
    private static final XRaySettings.Environment ENVIRONMENT = new XRaySettings.Environment(3, 14, 5, 14, 14);

    @Test
    void cancelledAndUnchangedExplosionsDoNotCreateExposure() {
        var world = new WorldFixture();
        var ore = pos(0, 20, 0);
        var cover = pos(1, 20, 0);
        world.blocks.put(ore, DIAMOND);
        var capture = world.view(1).enclosedNeighbors(Set.of(cover), ORE::equals);
        assertTrue(capture.containsKey(ore));

        var cancelled = world.history.begin(cover, "stone", 1);
        assertEquals(XRayWorldHistory.Outcome.UNCHANGED, world.history.finish(cancelled, "stone", false, false,
                false, false, null, 2));
        XRayListener.recordExposures(world.view(2), world.history, capture, Set.of(), 11, null, 2);
        assertNull(world.history.observe(ore, ORE, 2).exposure());
        assertNull(world.view(2).confirmExposure(ore, capture.get(ore), Set.of(cover)));
    }

    @Test
    void blastExposureRequiresTheOriginalOreAndAnOpeningFromThatBlast() {
        var world = new WorldFixture();
        var ore = pos(0, 20, 0);
        var cover = pos(1, 20, 0);
        var unrelated = pos(0, 20, 1);
        world.blocks.put(ore, DIAMOND);
        var capture = world.view(1).enclosedNeighbors(Set.of(cover), ORE::equals);
        world.blocks.put(cover, AIR);

        world.blocks.put(unrelated, AIR);
        assertNull(world.view(2).confirmExposure(ore, capture.get(ore), Set.of(cover)));
        world.blocks.put(unrelated, STONE);
        world.blocks.put(ore, STONE);
        assertNull(world.view(2).confirmExposure(ore, capture.get(ore), Set.of(cover)));

        world.blocks.put(ore, DIAMOND);
        XRayListener.recordExposures(world.view(2), world.history, capture, Set.of(cover), 5, null, 2);
        assertEquals(5, world.history.observe(ore, ORE, 2).exposure().blastId());
        var replacement = world.history.begin(ore, ORE, 3);
        assertEquals(XRayWorldHistory.Outcome.CONFIRMED, world.history.finish(replacement, ORE, true, true,
                false, true, null, 4));
        assertTrue(world.history.observe(ore, ORE, 4).placed());
        assertNull(world.history.observe(ore, ORE, 4).exposure());
    }

    @Test
    void blastMatchingPrefersExactAndRejectsDiagonalsAndExpiredEntries() {
        var world = new WorldFixture();
        var ore = pos(0, 20, 0);
        var close = pos(1, 20, 0);
        var diagonal = pos(1, 20, 1);
        Set<Vector3i> members = Set.of(ore, close, diagonal);
        for (Vector3i member : members) world.blocks.put(member, DIAMOND);
        var vein = new MiningGeometry.Vein(members, true);
        var config = new XRaySettings.Blast(new XRaySettings.Targets(true, Set.of(ORE), ENVIRONMENT, 2),
                100, 1, 0.1, 2, 8, 2);

        world.history.expose(diagonal, ORE, new XRayWorldHistory.Exposure(1, null, pos(2, 20, 1), 5));
        assertEquals(0, XRayListener.blastMatch(world.view(6), ore, vein, config, 6).id());
        world.history.expose(close, ORE, new XRayWorldHistory.Exposure(2, null, pos(2, 20, 0), 5));
        assertEquals(new XRayListener.BlastMatch(2, false), XRayListener.blastMatch(world.view(6), ore, vein, config, 6));
        world.history.expose(ore, ORE, new XRayWorldHistory.Exposure(3, null, pos(0, 20, -1), 5));
        assertEquals(new XRayListener.BlastMatch(3, true), XRayListener.blastMatch(world.view(6), ore, vein, config, 6));
        assertEquals(0, XRayListener.blastMatch(world.view(105), ore, vein, config, 105).id());
    }

    @Test
    void naturalCaveAirAndLiquidsAreCountedAndKnownExcavationIsExcluded() {
        var world = new WorldFixture();
        var ore = pos(0, 20, 0);
        world.blocks.put(ore, DIAMOND);
        for (int x = 1; x <= 3; x++) {
            world.blocks.put(pos(x, 20, 0), new XRayBlockView.MaterialState("cave_air", false));
        }
        assertEquals(OPEN, world.view(3).environment(ore, ENVIRONMENT));
        for (int x = 1; x <= 3; x++) {
            var position = pos(x, 20, 0);
            var change = world.history.begin(position, "stone", 4);
            assertEquals(XRayWorldHistory.Outcome.CONFIRMED, world.history.finish(change, "cave_air", true, true,
                    true, false, null, 5));
        }
        assertEquals(ENCLOSED, world.view(6).environment(ore, ENVIRONMENT));
        assertEquals(3, world.view(6).artificialAir(ore, 3).artificial());
        for (int x = -3; x <= 3; x++) {
            world.blocks.put(pos(x, 21, 0), new XRayBlockView.MaterialState("stationary_water", false));
            world.blocks.put(pos(x, 21, 1), new XRayBlockView.MaterialState("water", false));
        }
        assertEquals(OPEN, world.view(7).environment(ore, ENVIRONMENT));
        world.blocks.put(pos(-1, 20, 0), null);
        assertEquals(UNKNOWN, world.view(8).environment(ore, ENVIRONMENT));
    }

    @Test
    void remainingOreBelongsToTheFirstDiscoveryEvenForAnotherPlayer() {
        var history = new XRayWorldHistory(1000);
        var a = pos(0, 20, 0);
        var b = pos(1, 20, 0);
        var members = Map.of(a, ORE, b, ORE);
        var capture = new XRayListener.OreSnapshot(new MiningGeometry.Vein(members.keySet(), true), members,
                false, false, ENCLOSED, ENCLOSED, ENCLOSED, null, null);
        assertEquals(FIRST, XRayListener.recordDiscovery(history, capture, a, false, 1).discovery());
        assertTrue(history.observe(b, ORE, 2).seenVein());
        var firstSideGone = new XRayListener.OreSnapshot(new MiningGeometry.Vein(Set.of(b), true), Map.of(b, ORE),
                false, false, ENCLOSED, ENCLOSED, ENCLOSED, null, null);
        assertEquals(PREVIOUS, XRayListener.recordDiscovery(history, firstSideGone, b, false, 2).discovery());
    }

    @Test
    void competingChangesAndUnloadedChunksInvalidatePendingProof() {
        var history = new XRayWorldHistory(1000);
        var position = pos(-17, 20, 17);
        var first = history.begin(position, ORE, 1);
        var second = history.begin(position, ORE, 2);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(XRayWorldHistory.Outcome.UNKNOWN, history.finish(first, "air", true, true, true, false, ORE, 3));
        assertEquals(XRayWorldHistory.Outcome.UNKNOWN, history.finish(second, "air", true, true, true, false, ORE, 3));
        assertFalse(history.observe(position, "air", 3).known());

        var stale = history.begin(position, ORE, 4);
        history.unloadChunk(-2, 1);
        history.begin(position, ORE, 4);
        assertEquals(XRayWorldHistory.Outcome.UNKNOWN, history.finish(stale, "air", true, true, true, false, ORE, 5));
    }

    @Test
    void aNeighborChunkReloadInvalidatesAnOtherwiseUnchangedSnapshot() {
        var world = new WorldFixture();
        var before = world.view(1);
        before.cell(pos(15, 20, 0));
        before.cell(pos(16, 20, 0));
        var epochs = before.chunkEpochs();
        assertTrue(XRayBlockView.stillObserved(world.history, epochs, key -> true));
        assertFalse(XRayBlockView.stillObserved(world.history, epochs, key -> key != XRayWorldHistory.chunkKey(1, 0)));

        world.history.unloadChunk(1, 0);
        world.view(2).cell(pos(16, 20, 0));
        assertFalse(XRayBlockView.stillObserved(world.history, epochs, key -> true));
    }

    @Test
    void worldReadsStopBeforeAnUnownedOrUnloadedChunk() {
        World world = mock(World.class);
        var position = pos(-17, 20, 17);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, -2, 1)).thenReturn(false);
            assertNull(XRayListener.readBlock(world, position, true));
            verifyNoInteractions(world);

            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, -2, 1)).thenReturn(true);
            assertNull(XRayListener.readBlock(world, position, true));
            verify(world).isChunkLoaded(-2, 1);
            verifyNoMoreInteractions(world);
        }
    }

    private static Vector3i pos(int x, int y, int z) {
        return new Vector3i(x, y, z);
    }

    private static final class WorldFixture {
        final XRayWorldHistory history = new XRayWorldHistory(1000);
        final Map<Vector3i, XRayBlockView.MaterialState> blocks = new HashMap<>();

        XRayBlockView view(long now) {
            return new XRayBlockView(history, now, -64, 320, point -> blocks.getOrDefault(point, STONE));
        }
    }
}
