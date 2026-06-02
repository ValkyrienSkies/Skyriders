package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.BikeSaveRecord
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleSaveRecord

object ClientBikeSyncHandler {
    private var pendingRecords: List<BikeSaveRecord>? = null
    private var pendingVehicleRecords: List<VehicleSaveRecord>? = null

    fun handleBikeSync(records: List<BikeSaveRecord>) {
        val level = Minecraft.getInstance().level ?: return
        if (records.isEmpty()) {
            pendingRecords = null
            BikeManager.restoreBikes(level, emptyList())
            return
        }

        pendingRecords = records
        tryRestorePending()
    }

    fun handleVehicleSync(records: List<VehicleSaveRecord>) {
        val level = Minecraft.getInstance().level ?: return
        if (records.isEmpty()) {
            pendingVehicleRecords = null
            VehicleManager.restoreVehicles(level, emptyList())
            return
        }

        pendingVehicleRecords = records
        tryRestorePendingVehicles()
    }

    fun tick() {
        if (pendingRecords != null) {
            tryRestorePending()
        }
        if (pendingVehicleRecords != null) {
            tryRestorePendingVehicles()
        }
    }

    private fun tryRestorePending() {
        val records = pendingRecords ?: return
        val level = Minecraft.getInstance().level ?: return
        val restoredCount = BikeManager.restoreBikes(level, records)
        if (restoredCount == records.size) {
            pendingRecords = null
        }
    }

    private fun tryRestorePendingVehicles() {
        val records = pendingVehicleRecords ?: return
        val level = Minecraft.getInstance().level ?: return
        val restoredCount = VehicleManager.restoreVehicles(level, records)
        if (restoredCount == records.size) {
            pendingVehicleRecords = null
        }
    }
}
