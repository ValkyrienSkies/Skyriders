package org.valkyrienskies.skyriders.client

import org.valkyrienskies.skyriders.content.VehicleInput
import java.util.UUID

internal class VehicleInputTracker {
    private var seatId: UUID? = null
    private var lastInput = VehicleInput.EMPTY
    private var ticksSinceSend = 0

    fun shouldSend(controlledSeat: UUID?, input: VehicleInput): Boolean {
        if (controlledSeat == null) {
            reset()
            return false
        }
        ticksSinceSend++
        if (controlledSeat == seatId && input == lastInput && ticksSinceSend < 10) return false
        seatId = controlledSeat
        lastInput = input
        ticksSinceSend = 0
        return true
    }

    fun reset() {
        seatId = null
        lastInput = VehicleInput.EMPTY
        ticksSinceSend = 0
    }
}
