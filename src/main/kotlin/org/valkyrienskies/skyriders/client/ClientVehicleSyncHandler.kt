package org.valkyrienskies.skyriders.client

import org.valkyrienskies.skyriders.content.VehicleSaveRecord

object ClientVehicleSyncHandler {
    fun handleVehicleSync(records: List<VehicleSaveRecord>) {
        ClientBikeSyncHandler.handleVehicleSync(records)
    }

    fun tick() {
        ClientBikeSyncHandler.tick()
    }
}
