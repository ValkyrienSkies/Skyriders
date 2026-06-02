package org.valkyrienskies.skyriders.content

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId

/**
 * Generic vehicle facade over the current bike-backed implementation.
 *
 * Keep behavior-specific APIs on BikeManager until the generic layer owns
 * enough runtime and networking state to replace them directly.
 */
object VehicleManager {
    private const val VEHICLES_KEY = "vehicles"
    private const val LEGACY_BIKES_KEY = "bikes"

    val registeredVehicleIds: Set<ResourceLocation>
        get() = VehicleDefinitions.ids

    fun getDefinition(vehicleId: ResourceLocation): VehicleDefinition? {
        return VehicleDefinitions.get(vehicleId)
    }

    fun createVehicle(vehicleId: ResourceLocation, level: ServerLevel, position: Vector3dc): IVehicle {
        return BikeManager.createBike(vehicleId, level, position)
    }

    fun getVehicle(dimensionId: DimensionId, bodyId: BodyId): IVehicle? {
        return BikeManager.getBike(dimensionId, bodyId)
    }

    fun getVehicle(level: Level, bodyId: BodyId): IVehicle? {
        return BikeManager.getBike(level, bodyId)
    }

    fun getVehicles(dimensionId: DimensionId): List<IVehicle> {
        return BikeManager.getBikes(dimensionId)
    }

    fun getVehicles(level: Level): List<IVehicle> {
        return BikeManager.getBikes(level)
    }

    fun getInput(dimensionId: DimensionId, bodyId: BodyId): VehicleInput {
        return BikeManager.getInput(dimensionId, bodyId).toVehicleInput()
    }

    fun updateInput(
        dimensionId: DimensionId,
        bodyId: BodyId,
        updater: (VehicleInput) -> VehicleInput
    ): VehicleInput? {
        return BikeManager.updateInput(dimensionId, bodyId) { current ->
            updater(current.toVehicleInput()).toBikeInput()
        }?.toVehicleInput()
    }

    fun clearInput(dimensionId: DimensionId, bodyId: BodyId): VehicleInput? {
        return updateInput(dimensionId, bodyId) { VehicleInput.EMPTY }
    }

    fun getSaveRecords(dimensionId: DimensionId): List<VehicleSaveRecord> {
        return BikeManager.getBikes(dimensionId).map(IBike::toVehicleSaveRecord)
    }

    fun save(dimensionId: DimensionId): CompoundTag = CompoundTag().apply {
        val vehicles = ListTag()
        getSaveRecords(dimensionId)
            .map(VehicleSaveRecord::save)
            .forEach(vehicles::add)
        put(VEHICLES_KEY, vehicles)
    }

    fun loadRecords(tag: CompoundTag): List<VehicleSaveRecord> {
        val key = if (tag.contains(VEHICLES_KEY, Tag.TAG_LIST.toInt())) VEHICLES_KEY else LEGACY_BIKES_KEY
        val list = tag.getList(key, Tag.TAG_COMPOUND.toInt())
        return (0 until list.size).map { index -> VehicleSaveRecord.load(list.getCompound(index)) }
    }

    fun restoreVehicles(level: Level, records: Iterable<VehicleSaveRecord>): Int {
        return BikeManager.restoreBikes(level, records.map(VehicleSaveRecord::toBikeSaveRecord))
    }

    fun tick(dimensionId: DimensionId) {
        BikeManager.tick(dimensionId)
    }

    fun physTick(physLevel: PhysLevel, dt: Double) {
        BikeManager.physTick(physLevel, dt)
    }

    fun removeVehicle(level: ServerLevel, bodyId: BodyId, deleteBody: Boolean = true): IVehicle? {
        return BikeManager.removeBike(level, bodyId, deleteBody)
    }
}
