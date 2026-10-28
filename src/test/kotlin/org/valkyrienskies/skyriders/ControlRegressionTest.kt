package org.valkyrienskies.skyriders

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.valkyrienskies.skyriders.client.VehicleInputTracker
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.VehicleInput
import java.util.UUID

class ControlRegressionTest {
    @Test
    fun engineShutdownPreservesMechanicalControls() {
        val input = VehicleInput(steer = -0.7, throttle = 1.0, brake = 0.8, handbrake = 1.0, clutch = 0.9, riderPresent = true)
        val stopped = input.withEngineState(false)
        assertEquals(0.0, stopped.throttle)
        assertEquals(input.copy(throttle = 0.0), stopped)
        assertEquals(input, input.withEngineState(true))
        val bike = input.toBikeInput()
        assertEquals(bike.copy(throttle = 0.0), bike.withEngineState(false))
    }

    @Test
    fun invalidControlsAreNeutralizedBeforeTheyEnterRuntimeState() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(VehicleInput.EMPTY, VehicleInput(invalid, invalid, invalid, invalid, invalid, invalid, invalid).clamped())
            assertEquals(BikeInput.EMPTY, BikeInput(invalid, invalid, invalid, invalid, invalid).clamped())
        }
        assertEquals(1.0, VehicleInput(throttle = 100.0).clamped().throttle)
        assertEquals(-1.0, VehicleInput(steer = -100.0).clamped().steer)
    }

    @Test
    fun newDriverSessionSendsHeldControlsAndPeriodicallyRefreshesThem() {
        val tracker = VehicleInputTracker()
        val firstSeat = UUID.randomUUID()
        val secondSeat = UUID.randomUUID()
        val held = VehicleInput(throttle = 1.0, brake = 0.5)
        assertTrue(tracker.shouldSend(firstSeat, held))
        repeat(9) { assertFalse(tracker.shouldSend(firstSeat, held)) }
        assertTrue(tracker.shouldSend(firstSeat, held))
        assertTrue(tracker.shouldSend(secondSeat, held))
        assertFalse(tracker.shouldSend(null, held))
        assertTrue(tracker.shouldSend(secondSeat, held))
        assertTrue(tracker.shouldSend(secondSeat, VehicleInput.EMPTY))
    }
}
