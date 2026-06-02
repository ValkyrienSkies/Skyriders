package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.BikeSaveRecord

object ClientBikeSyncHandler {
    private var pendingRecords: List<BikeSaveRecord>? = null

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

    fun tick() {
        if (pendingRecords != null) {
            tryRestorePending()
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
}
