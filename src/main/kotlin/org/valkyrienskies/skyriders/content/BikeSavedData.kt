package org.valkyrienskies.skyriders.content

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import org.valkyrienskies.mod.api.dimensionId

class BikeSavedData : SavedData() {
    val records: MutableList<VehicleSaveRecord> = mutableListOf()

    override fun save(tag: CompoundTag): CompoundTag {
        val vehicles = ListTag()
        records.map(VehicleSaveRecord::save).forEach(vehicles::add)
        tag.put(VEHICLES_KEY, vehicles)

        val legacyBikes = ListTag()
        records.map(VehicleSaveRecord::toBikeSaveRecord).map(BikeSaveRecord::save).forEach(legacyBikes::add)
        tag.put(LEGACY_BIKES_KEY, legacyBikes)
        return tag
    }

    fun replaceFromManager(level: ServerLevel) {
        records.clear()
        records.addAll(VehicleManager.loadRecords(VehicleManager.save(level.dimensionId)))
        setDirty()
    }

    companion object {
        const val SAVED_DATA_ID = "skyriders_bikes"
        private const val VEHICLES_KEY = "vehicles"
        private const val LEGACY_BIKES_KEY = "bikes"

        fun createEmpty(): BikeSavedData = BikeSavedData()

        fun get(level: ServerLevel): BikeSavedData {
            return level.dataStorage.computeIfAbsent(::load, ::createEmpty, SAVED_DATA_ID)
        }

        @JvmStatic
        fun load(tag: CompoundTag): BikeSavedData {
            val data = BikeSavedData()
            data.records.addAll(VehicleManager.loadRecords(tag))
            return data
        }
    }
}
