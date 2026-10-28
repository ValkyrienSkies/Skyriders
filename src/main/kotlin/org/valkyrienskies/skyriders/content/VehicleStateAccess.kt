package org.valkyrienskies.skyriders.content

internal inline fun <T> withVehicleState(vehicle: IVehicle, action: () -> T): T = synchronized(vehicle, action)

internal fun Map<String, VehiclePartState>.snapshotParts(): Map<String, VehiclePartState> =
    mapValues { (_, state) -> state.copy(data = state.data.copy()) }
