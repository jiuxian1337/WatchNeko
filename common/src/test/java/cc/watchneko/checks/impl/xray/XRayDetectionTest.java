package cc.watchneko.checks.impl.xray;

import com.github.retrooper.packetevents.util.Vector3i;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static cc.watchneko.checks.impl.xray.MiningSample.Discovery.FIRST;
import static cc.watchneko.checks.impl.xray.MiningSample.Discovery.PREVIOUS;
import static cc.watchneko.checks.impl.xray.MiningSample.Discovery.UNKNOWN;
import static cc.watchneko.checks.impl.xray.MiningSample.Environment.ENCLOSED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XRayDetectionTest {
    private static final XRaySettings.Environment ENVIRONMENT = new XRaySettings.Environment(3, 14, 5, 14, 14);

    @Test
    void repeatedHitsInTheSameBlastCountOnce() {
        var config = new XRaySettings.Blast(targets(2), 600, 1, 0.1, 2, 8, 2);
        var window = new XRayA.Window();
        for (int sample = 0; sample < 8; sample++) {
            assertNull(window.add(sample, sample < 4 ? 1 : 0, true, config));
        }
        assertNull(window.add(20, 2, true, config));
        assertNull(window.add(21, 3, false, config));
        assertNull(window.add(22, 3, true, config));
        for (int sample = 23; sample < 27; sample++) assertNull(window.add(sample, 0, false, config));
        assertEquals(new XRayA.Result(2, 8, 2), window.add(27, 0, false, config));
    }

    @Test
    void rateThresholdIsStrictAndWindowsExpire() {
        var config = new XRaySettings.Blast(targets(1), 600, 1, 0.1, 2, 10, 1);
        var window = new XRayA.Window();
        assertNull(window.add(0, 1, true, config));
        for (int time = 1; time < 10; time++) assertNull(window.add(time, 0, false, config));
        assertNull(window.add(100, 2, true, config));
        assertNull(window.add(101, 3, true, config));
        for (int time = 700; time < 710; time++) assertNull(window.add(time, 0, false, config));
    }

    @Test
    void scanIncludesCornersAndHonorsUnknownCellsAndReadBudget() {
        Vector3i origin = pos(-1, 20, -1);
        Vector3i diagonal = pos(0, 21, 0);
        Set<Vector3i> ores = Set.of(origin, diagonal);
        var full = MiningGeometry.scanVein(origin, 5, 96, point -> cell(ores, point));
        assertTrue(full.complete());
        assertEquals(ores, full.members());
        assertEquals(1, full.faceConnectedSize(origin));

        AtomicInteger reads = new AtomicInteger();
        var limited = MiningGeometry.scanVein(origin, 5, 12, point -> {
            reads.incrementAndGet();
            return cell(ores, point);
        });
        assertEquals(12, reads.get());
        assertFalse(limited.complete());
        assertFalse(MiningGeometry.scanVein(origin, 1, 96, point -> cell(ores, point)).complete());
        assertFalse(MiningGeometry.scanVein(origin, 5, 96, point -> point.equals(origin.add(1, 0, 0))
                ? MiningGeometry.OreCell.UNKNOWN : cell(ores, point)).complete());
    }

    @Test
    void smallVeinStreakSkipsDuplicatesAndBreaksOnLargeOrUnknownVeins() {
        var config = new XRaySettings.SmallVeins(targets(1), 5, 4, 3, 96);
        var streak = new XRayC.Streak();
        assertFalse(streak.add(ore(FIRST, 3), 1, 100, config));
        assertFalse(streak.add(ore(PREVIOUS, 1), 2, 100, config));
        assertFalse(streak.add(ore(FIRST, 3), 3, 100, config));
        assertFalse(streak.add(ore(UNKNOWN, 1), 4, 100, config));
        assertFalse(streak.add(ore(FIRST, 2), 5, 100, config));
        assertFalse(streak.add(ore(FIRST, 4), 6, 100, config));
        assertFalse(streak.add(ore(FIRST, 1), 7, 100, config));
        assertFalse(streak.add(ore(FIRST, 2), 8, 100, config));
        assertTrue(streak.add(ore(FIRST, 3), 9, 100, config));
        assertFalse(streak.add(ore(FIRST, 3), 10, 100, config));
    }

    @Test
    void partialViolationBufferDoesNotSurviveAnIdleSession() {
        var config = new XRaySettings.SmallVeins(targets(2), 5, 4, 3, 96);
        var streak = new XRayC.Streak();
        for (int i = 0; i < 3; i++) assertFalse(streak.add(ore(FIRST, 1), i, 100, config));
        for (int i = 103; i < 108; i++) assertFalse(streak.add(ore(FIRST, 1), i, 100, config));
        assertTrue(streak.add(ore(FIRST, 1), 108, 100, config));
    }

    @Test
    void twoBlockHighTunnelAndStairsDoNotCountAlternatingBreaksAsTurnsOrBranches() {
        Set<Vector3i> tunnel = new HashSet<>();
        Set<Vector3i> stairs = new HashSet<>();
        for (int x = 0; x <= 14; x++) {
            tunnel.add(pos(x, 10, 0));
            tunnel.add(pos(x, 11, 0));
            stairs.add(pos(x, 10 + x / 2, 0));
            stairs.add(pos(x, 11 + x / 2, 0));
        }
        var flat = MiningPath.measure(pos(0, 10, 0), pos(15, 10, 0), pos(14, 10, 0), tunnel, 3);
        assertNotNull(flat);
        assertEquals(29, flat.blocks());
        assertEquals(0, flat.turns());
        assertEquals(0, flat.branches());
        assertEquals(0, flat.yShifts());
        assertTrue(flat.straight());

        var upward = MiningPath.measure(pos(0, 10, 0), pos(15, 17, 0), pos(14, 17, 0), stairs, 3);
        assertNotNull(upward);
        assertEquals(0, upward.turns());
        assertEquals(0, upward.branches());
        assertEquals(1, upward.yShifts());
    }

    @Test
    void branchesMustLeaveTheRouteAndUnknownConnectionsCannotScore() {
        Set<Vector3i> path = new HashSet<>();
        for (int x = 1; x <= 6; x++) path.add(pos(x, 10, 0));
        for (int z = 1; z <= 6; z++) path.add(pos(6, 10, z));
        path.add(pos(3, 10, -1));
        path.add(pos(3, 10, -2));
        path.add(pos(1, 10, 1));
        path.add(pos(2, 10, 1));
        Vector3i start = pos(0, 10, 0);
        Vector3i end = pos(6, 10, 7);
        Vector3i opener = pos(6, 10, 6);
        var metrics = MiningPath.measure(start, end, opener, path, 3);
        assertNotNull(metrics);
        assertEquals(1, metrics.turns());
        assertEquals(1, metrics.branches());
        path.remove(pos(6, 10, 2));
        assertNull(MiningPath.measure(start, end, opener, path, 3));
    }

    @Test
    void pathRequiresAnExcavatedOpeningAndFakeCaveOnlyChangesTheContext() {
        var config = new XRaySettings.Path(targets(1), 1000, 100, 10, 0, 6, 4, 3,
                new XRaySettings.FakeCave(true, 10, 1, 20, 0.3, 5, 200));
        var terrain = new XRayB.PathState();
        var plain = new XRayB.PathState();
        var unsettled = new XRayB.PathState();
        var start = new MiningSample("diamond_ore", ore(FIRST, 2), null);
        assertNull(terrain.observe(config, pos(0, 10, 0), 1, start));
        assertNull(plain.observe(config, pos(0, 10, 0), 1, start));
        assertNull(unsettled.observe(config, pos(0, 10, 0), 1, start));
        var carved = new MiningSample("stone", null, new MiningSample.AirSample(12, 24));
        var solid = new MiningSample("stone", null, new MiningSample.AirSample(0, 24));
        for (int index = 1; index <= 12; index++) {
            Vector3i block = pos(Math.min(index, 6), 10, Math.max(0, index - 6));
            terrain.observe(config, block, index + 1, carved);
            plain.observe(config, block, index + 1, solid);
            unsettled.observe(config, block, index + 1, carved);
        }
        var endpoint = new MiningSample("diamond_ore", new MiningSample.Ore(FIRST, 2, 2, ENCLOSED, ENCLOSED,
                ENCLOSED, true, 0, false, new MiningSample.Reveal(pos(6, 10, 6), 13)), null);
        assertNull(unsettled.observe(config, pos(6, 10, 7), 14, start));
        var artificial = terrain.observe(config, pos(6, 10, 7), 14, endpoint);
        var unmarked = plain.observe(config, pos(6, 10, 7), 14, endpoint);
        assertNotNull(artificial);
        assertNotNull(unmarked);
        assertEquals(unmarked.metrics(), artificial.metrics());
        assertEquals(0.5, artificial.fakeAirRatio());
        assertEquals(-1, unmarked.fakeAirRatio());
    }

    private static XRaySettings.Targets targets(double buffer) {
        return new XRaySettings.Targets(true, Set.of("diamond_ore"), ENVIRONMENT, buffer);
    }

    private static MiningSample.Ore ore(MiningSample.Discovery discovery, int size) {
        return new MiningSample.Ore(discovery, size, size, ENCLOSED, ENCLOSED, ENCLOSED, true, 0, false, null);
    }

    private static MiningGeometry.OreCell cell(Set<Vector3i> ores, Vector3i position) {
        return ores.contains(position) ? MiningGeometry.OreCell.ORE : MiningGeometry.OreCell.OTHER;
    }

    private static Vector3i pos(int x, int y, int z) {
        return new Vector3i(x, y, z);
    }
}
