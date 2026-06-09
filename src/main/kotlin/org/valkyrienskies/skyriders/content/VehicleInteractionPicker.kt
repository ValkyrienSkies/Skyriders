package org.valkyrienskies.skyriders.content

import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import kotlin.math.max

object VehicleInteractionPicker {
    fun findVehicleOnRay(level: Level, start: Vec3, end: Vec3): VehicleRayHit? {
        return VehicleManager.getVehicles(level)
            .asSequence()
            .flatMap { vehicle -> hitVehicleInteractionZones(vehicle, start, end).asSequence() }
            .groupBy { it.vehicle.bodyId }
            .values
            .asSequence()
            .mapNotNull(::selectBestZoneHit)
            .minWithOrNull(
                compareBy<VehicleRayHit> { it.vehicleDistanceSqr }
                    .thenBy { it.distanceSqr }
            )
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
        val startLocal = worldToLocal(transform, start)
        val endLocal = worldToLocal(transform, end)
        val center = zone.center
        val body = vehicle.vehicleDefinition.body
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
        val hitLocal = aabb.clip(startLocal, endLocal).orElse(null) ?: return null
        val distanceSqr = hitLocal.distanceToSqr(startLocal)
        return VehicleRayHit(vehicle, zone, distanceSqr, distanceSqr)
    }

    private fun worldToLocal(
        transform: org.valkyrienskies.core.api.bodies.properties.BodyTransform,
        world: Vec3
    ): Vec3 {
        val local = Vector3d(
            world.x - transform.position.x(),
            world.y - transform.position.y(),
            world.z - transform.position.z()
        )
        Quaterniond(transform.rotation).invert().transform(local)
        return Vec3(local.x, local.y, local.z)
    }

    private fun selectBestZoneHit(hits: List<VehicleRayHit>): VehicleRayHit? {
        if (hits.isEmpty()) return null
        val vehicleDistanceSqr = hits.minOf(VehicleRayHit::distanceSqr)
        val best = hits.minWithOrNull(
            compareByDescending<VehicleRayHit> { interactionPriority(it.zone) }
                .thenBy { it.distanceSqr }
        ) ?: return null
        return best.copy(vehicleDistanceSqr = vehicleDistanceSqr)
    }

    private fun interactionPriority(zone: VehicleInteractionZone): Int {
        return when {
            VehicleInteractionActions.MOUNT in zone.actions -> 30
            zone.partId != null -> 25
            VehicleInteractionActions.ENGINE_TOGGLE in zone.actions -> 20
            VehicleInteractionActions.PICK_UP in zone.actions -> 10
            else -> 0
        }
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
            actions = setOf(VehicleInteractionActions.PICK_UP)
        )
    }
}

data class VehicleRayHit(
    val vehicle: IVehicle,
    val zone: VehicleInteractionZone,
    val distanceSqr: Double,
    val vehicleDistanceSqr: Double = distanceSqr
)
