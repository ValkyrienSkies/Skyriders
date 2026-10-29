package org.valkyrienskies.skyriders.content

import net.minecraft.world.level.Level
import net.minecraft.world.level.ClipContext
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.mod.common.world.clipIncludeShips
import kotlin.math.max

object VehicleInteractionPicker {
    fun findVehicleOnRay(level: Level, start: Vec3, end: Vec3): VehicleRayHit? {
        return visibleVehicleHits(level, start, end)
            .asSequence()
            .groupBy { it.vehicle.bodyId }
            .values
            .asSequence()
            .mapNotNull(::selectBestZoneHit)
            .minWithOrNull(
                compareBy<VehicleRayHit> { it.vehicleDistanceSqr }
                    .thenBy { it.distanceSqr }
            )
    }

    fun visibleVehicleHits(
        level: Level,
        start: Vec3,
        end: Vec3,
        vehicles: Iterable<IVehicle> = VehicleManager.getVehicles(level)
    ): List<VehicleRayHit> {
        val hits = vehicles.flatMap { hitVehicleInteractionZones(it, start, end) }
        if (hits.isEmpty()) return emptyList()
        val length = start.distanceTo(end)
        if (!length.isFinite() || length <= 1.0e-6) return emptyList()
        val furthestHit = kotlin.math.sqrt(hits.maxOf { it.distanceSqr })
        val candidateEnd = start.add(end.subtract(start).scale((furthestHit / length).coerceAtMost(1.0)))
        val visibleDistanceSqr = start.distanceToSqr(unobstructedEnd(level, start, candidateEnd))
        return hits.filter { it.distanceSqr <= visibleDistanceSqr + 1.0e-8 }
    }

    fun unobstructedEnd(level: Level, start: Vec3, end: Vec3): Vec3 = level.clipIncludeShips(
        ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null as Entity?)
    ).location

    fun hitVehicleInteractionZones(vehicle: IVehicle, start: Vec3, end: Vec3): List<VehicleRayHit> {
        val transform = try {
            vehicle.getRenderTransform() ?: return emptyList()
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
        val hitWorld = transform.toWorld.transformPosition(Vector3d(hitLocal.x, hitLocal.y, hitLocal.z))
        val distanceSqr = start.distanceToSqr(hitWorld.x, hitWorld.y, hitWorld.z)
        return VehicleRayHit(vehicle, zone, distanceSqr, distanceSqr)
    }

    private fun worldToLocal(
        transform: org.valkyrienskies.core.api.bodies.properties.BodyTransform,
        world: Vec3
    ): Vec3 {
        val local = transform.toModel.transformPosition(Vector3d(world.x, world.y, world.z))
        return Vec3(local.x, local.y, local.z)
    }

    private fun selectBestZoneHit(hits: List<VehicleRayHit>): VehicleRayHit? {
        if (hits.isEmpty()) return null
        val vehicleDistanceSqr = hits.minOf(VehicleRayHit::distanceSqr)
        val best = hits.minWithOrNull(
            compareByDescending<VehicleRayHit> { interactionPriority(it.vehicle, it.zone) }
                .thenBy { it.distanceSqr }
        ) ?: return null
        return best.copy(vehicleDistanceSqr = vehicleDistanceSqr)
    }

    private fun interactionPriority(vehicle: IVehicle, zone: VehicleInteractionZone): Int {
        return when {
            VehicleInteractionActions.OPEN_DOOR in zone.actions && !isPartOpen(vehicle, zone.partId) -> 35
            VehicleInteractionActions.MOUNT in zone.actions -> 30
            zone.partId != null -> 25
            VehicleInteractionActions.ENGINE_TOGGLE in zone.actions -> 20
            VehicleInteractionActions.PICK_UP in zone.actions -> 10
            else -> 0
        }
    }

    private fun isPartOpen(vehicle: IVehicle, partId: String?): Boolean {
        if (partId == null) return false
        vehicle.vehicleState.partStates[partId]?.data?.let { return it.getBoolean("open") }
        return vehicle.vehicleDefinition.parts
            .firstOrNull { it.id == partId }
            ?.defaultState
            ?.getBoolean("open")
            ?: false
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
