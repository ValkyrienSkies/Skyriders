package org.valkyrienskies.skyriders.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.mod.common.world.clipIncludeShips
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.util.VehicleCollisionSweep
import java.util.UUID

internal object VehicleProjectileHits {
    data class Hit(val position: Vec3, val fraction: Double, val vehicle: IVehicle? = null, val entity: LivingEntity? = null)

    fun firstHit(level: ServerLevel, projectile: Entity, start: Vec3, end: Vec3, radius: Double,
                 ownerBodyId: Long, ownerPlayerId: UUID? = null, hitEntities: Boolean = false): Hit? {
        val movement = end.subtract(start)
        val lengthSquared = movement.lengthSqr()
        if (lengthSquared < 1.0e-12) return null
        val block = level.clipIncludeShips(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, projectile))
        var nearest = if (block.type == HitResult.Type.MISS) null else Hit(block.location,
            block.location.subtract(start).dot(movement) / lengthSquared)
        val half = Vector3d(radius)
        val projectileBox = AABB(start.x - half.x, start.y - half.y, start.z - half.z,
            start.x + half.x, start.y + half.y, start.z + half.z)
        val swept = projectileBox.expandTowards(movement)
        val vector = Vector3d(movement.x, movement.y, movement.z)
        val shipWorld = level.shipWorld
        for (vehicle in VehicleManager.getVehicles(level)) {
            if (vehicle.bodyId == ownerBodyId) continue
            val body = shipWorld?.allBodies?.getById(vehicle.bodyId) ?: continue
            val shapes = vehicle.vehicleDefinition.body.resolvedCollisionBoxes()
            if (!VehicleCollisionSweep.sweptBounds(shapes, body.prevTickKinematics.transform,
                    body.kinematics.transform).intersects(swept)) continue
            val hit = VehicleCollisionSweep.cast(shapes, body.prevTickKinematics.transform,
                body.kinematics.transform, projectileBox, vector) ?: continue
            if (hit.fraction < (nearest?.fraction ?: Double.POSITIVE_INFINITY)) {
                nearest = Hit(start.add(movement.scale(hit.fraction)), hit.fraction, vehicle = vehicle)
            }
        }
        if (!hitEntities) return nearest
        for (entity in level.getEntitiesOfClass(LivingEntity::class.java, swept.inflate(radius)) {
            it.isAlive && it.uuid != ownerPlayerId && it.vehicle !is BikeSeatEntity
        }) {
            val box = entity.boundingBox.inflate(radius)
            val point = if (box.contains(start)) start else box.clip(start, end).orElse(null) ?: continue
            val fraction = point.subtract(start).dot(movement) / lengthSquared
            if (fraction < (nearest?.fraction ?: Double.POSITIVE_INFINITY)) nearest = Hit(point, fraction, entity = entity)
        }
        return nearest
    }
}
