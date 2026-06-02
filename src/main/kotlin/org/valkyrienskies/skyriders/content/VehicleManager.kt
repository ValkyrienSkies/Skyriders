package org.valkyrienskies.skyriders.content

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
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
