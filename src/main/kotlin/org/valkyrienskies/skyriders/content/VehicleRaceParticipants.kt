package org.valkyrienskies.skyriders.content

import net.minecraft.world.item.DyeColor
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle

object VehicleRaceParticipants {
    fun mark(vehicle: IVehicle, color: DyeColor) {
        when (vehicle) {
            is IBike -> {
                vehicle.state.raceParticipant = true
                vehicle.state.raceColorId = color.id
            }
            is KartVehicle -> {
                vehicle.kartState.raceParticipant = true
                vehicle.kartState.raceColorId = color.id
            }
            is WheeledVehicle -> {
                vehicle.wheeledState.raceParticipant = true
                vehicle.wheeledState.raceColorId = color.id
            }
            else -> {
                vehicle.vehicleState.raceParticipant = true
                vehicle.vehicleState.raceColorId = color.id
            }
        }
    }

    fun isParticipant(vehicle: IVehicle): Boolean = vehicle.vehicleState.raceParticipant

    fun color(vehicle: IVehicle): DyeColor? {
        val state = vehicle.vehicleState
        if (!state.raceParticipant || state.raceColorId < 0) return null
        return DyeColor.byId(state.raceColorId)
    }

    fun matchesColor(vehicle: IVehicle, color: DyeColor): Boolean {
        return isParticipant(vehicle) && vehicle.vehicleState.raceColorId == color.id
    }
}
