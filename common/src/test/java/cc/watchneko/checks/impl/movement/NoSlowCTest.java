package cc.watchneko.checks.impl.movement;

import cc.watchneko.utils.data.PacketStateData;
import cc.watchneko.utils.data.VectorData;
import cc.watchneko.utils.math.Vector3dm;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoSlowCTest {
    private static final VectorData SLOWED = new VectorData(new Vector3dm(), VectorData.VectorType.Normal);
    private static final VectorData UNSLOWED = SLOWED.returnNewModified(VectorData.VectorType.Flip_Use_Item);

    @Test
    void completesOnlyTheOpposingGroup() {
        var prediction = new NoSlowC.Prediction(0.003, 0.012, 1);
        prediction.measure(0, false, 0.002);
        prediction.complete(List.of(SLOWED, SLOWED, UNSLOWED), candidate -> {
            assertTrue(candidate.isFlipItem());
            return 0.03;
        });

        assertEquals(NoSlowC.ItemModel.UNSLOWED, prediction.excludedGroup());
    }

    @Test
    void budgetExhaustionAndSkippedCompatibleCandidatesLeaveTheFrameUnknown() {
        var limited = new NoSlowC.Prediction(0.003, 0.012, 1);
        limited.measure(0, true, 0.002);
        limited.complete(List.of(UNSLOWED, SLOWED, SLOWED), candidate -> 0.03);
        assertNull(limited.excludedGroup());

        var ambiguous = new NoSlowC.Prediction(0.003, 0.012, 2);
        ambiguous.measure(0, true, 0.002);
        ambiguous.measure(1, false, 0.03);
        ambiguous.complete(List.of(UNSLOWED, SLOWED, SLOWED), candidate -> 0.002);
        assertNull(ambiguous.excludedGroup());
    }

    @Test
    void anAbsentPredictionGroupCannotBeExcluded() {
        var prediction = new NoSlowC.Prediction(0.003, 0.012, 0);
        prediction.measure(0, true, 0.002);
        prediction.complete(List.of(UNSLOWED), candidate -> {
            throw new AssertionError("There is no opposing candidate to compute");
        });

        assertNull(prediction.excludedGroup());
    }

    @Test
    void evidenceFromDifferentUsePeriodsCannotBeCombined() {
        var evidence = new NoSlowC.UseEvidence();
        assertFalse(evidence.add(false, 2));
        assertFalse(evidence.add(true, 2));
        evidence.reset();
        assertFalse(evidence.add(true, 2));
        assertFalse(evidence.add(true, 2));
        assertTrue(evidence.add(false, 2));
        assertFalse(evidence.add(true, 2));
    }

    @Test
    void releaseAndReuseBetweenMovementsChangeTheItemRevision() {
        var state = new PacketStateData();
        state.setSlowedByUsingItem(true);
        long firstUse = state.getItemUseRevision();
        state.setSlowedByUsingItem(true);
        assertEquals(firstUse, state.getItemUseRevision());

        state.setSlowedByUsingItem(false);
        state.setSlowedByUsingItem(true);
        assertEquals(firstUse + 2, state.getItemUseRevision());
        state.setItemInUseHand(InteractionHand.OFF_HAND);
        assertEquals(firstUse + 3, state.getItemUseRevision());
    }
}
