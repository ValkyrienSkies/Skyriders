package org.valkyrienskies.skyriders.content

import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import kotlin.math.max

object VehicleInteractionPicker {
    fun findVehicleOnRay(level: Level, start: Vec3, end: Vec3): VehicleRayHit? {
        return VehicleManager.getVehicles(level)
            .asSequence()
            .flatMap { vehicle -> hitVehicleInteractionZones(vehicle, start, end).asSequence() }
            .minByOrNull(VehicleRayHit::distanceSqr)
    }

    fun hitVehicleInteractionZones(vehicle: IVehicle, start: Vec3, end: Vec3): List<VehicleRayHit> {
        val transform = try {
            vehicle.getRenderTransform()
        } catch (_: IllegalStateException) {
            return emptyList()
        }
        val definition = vehicle.vehicleDefinition
        val zones = definition.interactions.zones.ifEmpty {
            listOf(fallbackBodyZone(definition))
        }

        return zones.mapNotNull { zone ->
            hitVehicleZone(vehicle, zone, transform, start, end)
        }
    }

    private fun hitVehicleZone(
        vehicle: IVehicle,
        zone: VehicleInteractionZone,
        transform: org.valkyrienskies.core.api.bodies.properties.BodyTransform,
        start: Vec3,
        end: Vec3
    ): VehicleRayHit? {
        val body = vehicle.vehicleDefinition.body
        val center = transform.toWorld.transformPosition(Vector3d(zone.center))
        val halfX = max(zone.size.x * 0.5, body.collisionBoxSize.x * 0.25)
        val halfY = zone.size.y * 0.5
        val halfZ = zone.size.z * 0.5
        val aabb = AABB(
            center.x - halfX,
            center.y - halfY,
            center.z - halfZ,
            center.x + halfX,
            center.y + halfY,
            center.z + halfZ
        )
        val hit = aabb.clip(start, end).orElse(null) ?: return null
        return VehicleRayHit(vehicle, zone, hit.distanceToSqr(start))
    }

    private fun fallbackBodyZone(definition: VehicleDefinition): VehicleInteractionZone {
        val body = definition.body
        return VehicleInteractionZone(
            id = VehicleInteractionDefinition.BODY,
            center = Vector3d(body.collisionBoxOffset),
            size = Vector3d(
                body.collisionBoxSize.x,
                body.collisionBoxSize.y,
                body.collisionBoxSize.z
            ),
            actions = setOf(VehicleInteractionAction.PICK_UP)
        )
    }
}

data class VehicleRayHit(
    val vehicle: IVehicle,
    val zone: VehicleInteractionZone,
    val distanceSqr: Double
)
