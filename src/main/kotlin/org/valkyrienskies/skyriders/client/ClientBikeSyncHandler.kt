package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.BikeSaveRecord

object ClientBikeSyncHandler {
    fun handleBikeSync(records: List<BikeSaveRecord>) {
        val level = Minecraft.getInstance().level ?: return
        BikeManager.restoreBikes(level, records)
    }
}
