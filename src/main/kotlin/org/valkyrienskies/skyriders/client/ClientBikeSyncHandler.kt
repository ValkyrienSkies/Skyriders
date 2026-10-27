package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.BikeDefinitions
import org.valkyrienskies.skyriders.content.BikeSaveRecord
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleSaveRecord
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import org.valkyrienskies.skyriders.network.SyncSnapshotAssembler

object ClientBikeSyncHandler {
    private var pendingLevel: ClientLevel? = null
    private var pendingRecords = emptyList<BikeSaveRecord>()
    private var pendingVehicleRecords = emptyList<VehicleSaveRecord>()
    private val bikeSnapshot = SyncSnapshotAssembler<BikeSaveRecord>()
    private val vehicleSnapshot = SyncSnapshotAssembler<VehicleSaveRecord>()

    fun handleBikeSync(batch: List<BikeSaveRecord>, first: Boolean = true, last: Boolean = true) {
        val level = Minecraft.getInstance().level ?: return
        bindLevel(level)
        val records = bikeSnapshot.accept(batch, first, last) ?: return
        BikeManager.restoreBikes(level, records)
        pendingRecords = records.filter { BikeManager.getBike(level, it.bodyId) == null &&
            BikeDefinitions.resolveSavedId(it.bikeType)?.let(BikeDefinitions::get) != null }
    }

    fun handleVehicleSync(batch: List<VehicleSaveRecord>, first: Boolean = true, last: Boolean = true) {
        val level = Minecraft.getInstance().level ?: return
        bindLevel(level)
        val records = vehicleSnapshot.accept(batch, first, last) ?: return
        VehicleManager.restoreVehicles(level, records)
        pendingVehicleRecords = records.filter { VehicleManager.getVehicle(level, it.bodyId) == null &&
            VehicleManager.isKnownType(it.vehicleType) }
    }

    fun tick() {
        val level = Minecraft.getInstance().level
        if (level == null || level !== pendingLevel) {
            clear()
            return
        }
        pendingRecords = pendingRecords.filter { BikeManager.restoreBike(level, it) == null }
        pendingVehicleRecords = pendingVehicleRecords.filter { VehicleManager.restoreVehicle(level, it) == null }
    }

    private fun bindLevel(level: ClientLevel) {
        if (pendingLevel !== level) clear()
        pendingLevel = level
    }

    fun clear() {
        pendingLevel?.let {
            BikeManager.clearBikes(it)
            VehicleManager.clearVehicles(it)
        }
        pendingLevel = null
        bikeSnapshot.clear()
        vehicleSnapshot.clear()
        pendingRecords = emptyList()
        pendingVehicleRecords = emptyList()
        VehicleStatusEffects.clearClient()
        BikeWorldRenderer.clear()
        BikeClientEffects.clear()
    }
}
