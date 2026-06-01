package org.valkyrienskies.skyriders.content

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import org.valkyrienskies.mod.api.dimensionId

class BikeSavedData : SavedData() {
    val records: MutableList<BikeSaveRecord> = mutableListOf()

    override fun save(tag: CompoundTag): CompoundTag {
        val bikes = ListTag()
        records.map(BikeSaveRecord::save).forEach(bikes::add)
        tag.put(BIKES_KEY, bikes)
        return tag
    }

    fun replaceFromManager(level: ServerLevel) {
        records.clear()
        records.addAll(BikeManager.loadRecords(BikeManager.save(level.dimensionId)))
        setDirty()
    }

    companion object {
        const val SAVED_DATA_ID = "skyriders_bikes"
        private const val BIKES_KEY = "bikes"

        fun createEmpty(): BikeSavedData = BikeSavedData()

        @JvmStatic
        fun load(tag: CompoundTag): BikeSavedData {
            val data = BikeSavedData()
            val bikes = tag.getList(BIKES_KEY, Tag.TAG_COMPOUND.toInt())
            for (index in 0 until bikes.size) {
                data.records.add(BikeSaveRecord.load(bikes.getCompound(index)))
            }
            return data
        }
    }
}
