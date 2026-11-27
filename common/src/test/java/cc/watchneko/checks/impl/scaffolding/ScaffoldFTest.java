package cc.watchneko.checks.impl.scaffolding;

import com.github.retrooper.packetevents.util.Vector3i;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScaffoldFTest {
    @Test
    void scoresSustainedRapidPlacementsAlongTheSameBridge() {
        var chain = new ScaffoldF.Chain();
        for (int block = 0; block < 7; block++) {
            assertNull(place(chain, block, block + 1, block * 100L, true));
        }
        var match = place(chain, 7, 8, 700, true);
        assertNotNull(match);
        assertEquals(8, match.placements());
    }

    @Test
    void ignoresDisconnectedPlacementsNormalCadenceAndBunchedPackets() {
        var disconnected = new ScaffoldF.Chain();
        var walking = new ScaffoldF.Chain();
        var sneaking = new ScaffoldF.Chain();
        var batched = new ScaffoldF.Chain();
        for (int block = 0; block < 20; block++) {
            assertNull(place(disconnected, 2 * block, 2 * block + 1, block * 100L, true));
            assertNull(place(walking, block, block + 1, block * 220L, true));
            assertNull(place(sneaking, block, block + 1, block * 100L, false));
            assertNull(place(batched, block, block + 1, block * 2L, true));
        }
    }

    private static ScaffoldF.Match place(ScaffoldF.Chain chain, int support, int placed, long millis, boolean grounded) {
        return chain.accept(new ScaffoldF.Placement(new Vector3i(support, 70, 0), new Vector3i(placed, 70, 0),
                TimeUnit.MILLISECONDS.toNanos(millis), grounded, 28));
    }
}
