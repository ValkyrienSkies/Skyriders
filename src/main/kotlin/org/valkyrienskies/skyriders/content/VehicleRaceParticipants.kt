package org.valkyrienskies.skyriders.content

import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle

object VehicleRaceParticipants {
    fun mark(vehicle: IVehicle, colorRgb: Int) {
        val normalizedColor = colorRgb and 0xFFFFFF
        when (vehicle) {
            is IBike -> {
                vehicle.state.raceParticipant = true
                vehicle.state.raceColorId = normalizedColor
            }
            is KartVehicle -> {
                vehicle.kartState.raceParticipant = true
                vehicle.kartState.raceColorId = normalizedColor
            }
            is WheeledVehicle -> {
                vehicle.wheeledState.raceParticipant = true
                vehicle.wheeledState.raceColorId = normalizedColor
            }
            else -> {
                vehicle.vehicleState.raceParticipant = true
                vehicle.vehicleState.raceColorId = normalizedColor
            }
        }
    }

    fun isParticipant(vehicle: IVehicle): Boolean = vehicle.vehicleState.raceParticipant

    fun color(vehicle: IVehicle): Int? {
        val state = vehicle.vehicleState
        if (!state.raceParticipant || state.raceColorId < 0) return null
        return state.raceColorId and 0xFFFFFF
    }

    fun matchesColor(vehicle: IVehicle, colorRgb: Int): Boolean {
        return isParticipant(vehicle) && (vehicle.vehicleState.raceColorId and 0xFFFFFF) == (colorRgb and 0xFFFFFF)
    }
}
