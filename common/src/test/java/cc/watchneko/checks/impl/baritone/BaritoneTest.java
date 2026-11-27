package cc.watchneko.checks.impl.baritone;

import cc.watchneko.checks.CheckData;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3i;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneTest {
    private static final Vector3i MINING_TARGET = new Vector3i(0, 64, -3);

    @Test
    void baritoneRunsAsARegularCheck() {
        CheckData metadata = Baritone.class.getAnnotation(CheckData.class);
        assertEquals("Baritone", metadata.name());
        assertFalse(metadata.experimental());
    }

    @Test
    void separatesPreciseMiningTurnsAndHeldAimAcrossWrappedYaw() {
        Baritone.Look onTarget = look(-179.99f, 24.13f, 100);
        Baritone.Aim turned = Baritone.miningAim(look(172, 28, 50),
                onTarget, MINING_TARGET, BlockFace.UP);
        Baritone.Aim stayed = Baritone.miningAim(look(179.98f, 24.10f, 50),
                onTarget, MINING_TARGET, BlockFace.UP);

        assertTrue(turned.miningApproach());
        assertFalse(turned.miningHold());
        assertFalse(stayed.miningApproach());
        assertTrue(stayed.miningHold());
    }

    @Test
    void rejectsStaleRotationsAndChangedPositionOrPose() {
        Baritone.Look before = look(175, 24, 50);
        Baritone.Look current = look(180, 24, 100);
        assertTrue(Baritone.fresh(before, current, ms(350), 250, 200));
        assertFalse(Baritone.fresh(before, current, ms(351), 250, 200));
        assertFalse(Baritone.fresh(look(175, 24, 0), look(180, 24, 250), ms(260), 250, 200));
        assertFalse(Baritone.stableMiningPosition(before, current, 0.56, 64, 0, 1.62));
        assertFalse(Baritone.stableMiningPosition(before, current, 0.5, 64, 0, 1.27));
    }

    @Test
    void placementUsesTheSupportFaceAndRequiresAnAdjacentTurn() {
        Vector3i support = new Vector3i(0, 64, -3);
        // The SOUTH face is at z=-2 and the pathing point is a quarter block above y=64.
        float pitch = (float) Math.toDegrees(Math.atan2(1.37, 2.0));
        Baritone.Look current = look(180, pitch, 100);
        Baritone.Aim snap = Baritone.placementAim(look(165, pitch, 50),
                current, support, BlockFace.SOUTH);
        assertTrue(snap.placementSnap());
        assertFalse(Baritone.placementAim(look(179.9f, pitch, 50),
                current, support, BlockFace.SOUTH).placementSnap());
        assertFalse(Baritone.placementAim(look(165, pitch, 50),
                current, support, BlockFace.NORTH).placementSnap());
    }

    @Test
    void placementConfirmationConsumesOnlyOneUnambiguousRequest() {
        var requests = new Baritone.Placements();
        Vector3i placed = new Vector3i(0, 64, -2);
        Baritone.Aim aim = new Baritone.Aim(0, 0, 4, 4, 2, 8, 25);
        requests.add(MINING_TARGET, placed, aim, ms(100));
        assertNull(requests.confirm(MINING_TARGET, new Vector3i(0, 65, -3), ms(120)));
        assertNotNull(requests.confirm(MINING_TARGET, placed, ms(140)));
        assertNull(requests.confirm(MINING_TARGET, placed, ms(141)));

        requests.add(MINING_TARGET, placed, null, ms(200));
        requests.add(MINING_TARGET, placed, aim, ms(210));
        assertNull(requests.confirm(MINING_TARGET, placed, ms(215)));
        assertNull(requests.confirm(MINING_TARGET, placed, ms(216)));

        requests.add(MINING_TARGET, placed, aim, ms(300));
        assertNull(requests.confirm(MINING_TARGET, placed, ms(299)));
        assertNull(requests.confirm(MINING_TARGET, placed, ms(1301)));
    }

    @Test
    void traverseRequiresTheSameTargetAndSustainedPitch() {
        var session = new Baritone.MiningSession(MINING_TARGET, ms(0));
        var drifting = new Baritone.MiningSession(MINING_TARGET, ms(0));
        for (int i = 1; i <= 10; i++) {
            session.add(look(175 + i, 26, i * 50L));
            drifting.add(look(175 + i, 26 + i * 0.03f, i * 50L));
        }
        assertTrue(session.matches(MINING_TARGET, ms(550)));
        assertFalse(session.matches(new Vector3i(0, 64, -4), ms(550)));
        assertFalse(session.matches(MINING_TARGET, ms(10_001)));
        assertFalse(drifting.matches(MINING_TARGET, ms(550)));
    }

    @Test
    void miningEvidenceRequiresFreshDistinctTargetsAndPitchDiversity() {
        var window = new Baritone.Window(3, 4, 1, 8);
        Vector3i first = new Vector3i(0, 0, 0);
        assertNull(window.add(first, 20, ms(0)));
        assertNull(window.add(first, 30, ms(15)));
        assertNull(window.add(new Vector3i(1, 0, 0), 20, ms(50)));
        assertNull(window.add(new Vector3i(2, 0, 0), 20, ms(150)));
        assertNull(window.add(new Vector3i(3, 0, 0), 32, ms(1200)));
        assertNull(window.add(new Vector3i(4, 0, 0), Double.NaN, ms(1300)));
        assertNull(window.add(new Vector3i(5, 0, 0), 20, ms(1350)));
        assertNotNull(window.add(new Vector3i(6, 0, 0), 20, ms(1400)));
        assertNull(window.add(new Vector3i(7, 0, 0), 20, ms(1450)));
    }

    private static Baritone.Look look(float yaw, float pitch, long millis) {
        return new Baritone.Look(0.5, 64, 0, 1.62, yaw, pitch, ms(millis));
    }

    private static long ms(long value) {
        return TimeUnit.MILLISECONDS.toNanos(value);
    }
}
