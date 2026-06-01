package org.valkyrienskies.skyriders.content

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import java.util.concurrent.ConcurrentHashMap

object BikeManager {
    private const val BIKES_KEY = "bikes"

    private val bikesByDimension = ConcurrentHashMap<DimensionId, ConcurrentHashMap<BodyId, IBike>>()

    fun addBike(dimensionId: DimensionId, bike: IBike) {
        bikesByDimension.getOrPut(dimensionId) { ConcurrentHashMap() }[bike.bodyId] = bike
    }

    fun removeBike(dimensionId: DimensionId, bodyId: BodyId): IBike? {
        return bikesByDimension[dimensionId]?.remove(bodyId)
    }

    fun getBike(dimensionId: DimensionId, bodyId: BodyId): IBike? {
        return bikesByDimension[dimensionId]?.get(bodyId)
    }

    fun tick(dimensionId: DimensionId) {
        bikesByDimension[dimensionId]?.values?.forEach(IBike::tick)
    }

    fun physTick(physLevel: PhysLevel, dt: Double) {
        bikesByDimension[physLevel.dimension]?.values?.forEach { bike ->
            val body = physLevel.getBodyById(bike.bodyId) ?: return@forEach
            bike.physTick(physLevel, body, dt)
        }
    }

    fun save(dimensionId: DimensionId): CompoundTag = CompoundTag().apply {
        val bikes = ListTag()
        bikesByDimension[dimensionId]?.values
            ?.map(IBike::toSaveRecord)
            ?.map(BikeSaveRecord::save)
            ?.forEach(bikes::add)
        put(BIKES_KEY, bikes)
    }

    fun loadRecords(tag: CompoundTag): List<BikeSaveRecord> {
        val list = tag.getList(BIKES_KEY, Tag.TAG_COMPOUND.toInt())
        return (0 until list.size).map { index -> BikeSaveRecord.load(list.getCompound(index)) }
    }
}
