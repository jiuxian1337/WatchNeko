package cc.watchneko.predictionengine;

import cc.watchneko.utils.math.Vector3dm;
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Client;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementSimulationTest {
    private final MovementSimulation simulation = new MovementSimulation(true);

    @Test
    void aPassengerTickAdvancesWithoutAMovePacket() {
        assertFalse(simulation.endsTick(Client.PLAYER_ROTATION, false, true));
        assertFalse(simulation.endsTick(Client.STEER_VEHICLE, false, true));
        assertTrue(simulation.endsTick(Client.PLAYER_ROTATION, false, true));
        assertTrue(simulation.endsTick(Client.PLAYER_ROTATION, false, true));
    }

    @Test
    void aVehicleMoveClosesTheTickBeforeTheNextRotation() {
        assertFalse(simulation.endsTick(Client.PLAYER_ROTATION, false, true));
        assertTrue(simulation.endsTick(Client.VEHICLE_MOVE, false, true));
        assertFalse(simulation.endsTick(Client.PLAYER_ROTATION, false, true));
        assertTrue(simulation.endsTick(Client.VEHICLE_MOVE, false, true));
    }

    @Test
    void anExplicitTickEndTakesPrecedenceOverBothVehiclePackets() {
        assertFalse(simulation.endsTick(Client.PLAYER_ROTATION, true, true));
        assertFalse(simulation.endsTick(Client.VEHICLE_MOVE, true, true));
        assertTrue(simulation.endsTick(Client.CLIENT_TICK_END, true, true));
    }

    @Test
    void theNextFrameDoesNotReuseTravelSelectedDuringAnExternalPush() {
        var travel = new Vector3dm(0.03, 0, 0);
        var displacement = new Vector3dm(0.53, 0, 0);
        simulation.selectTravel(travel);
        assertSame(travel, simulation.selectedTravel(displacement));
        simulation.finishFrame();
        assertSame(displacement, simulation.selectedTravel(displacement));
    }
}
