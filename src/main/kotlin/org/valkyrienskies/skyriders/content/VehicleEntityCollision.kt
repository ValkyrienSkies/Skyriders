package org.valkyrienskies.skyriders.content

import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.primitives.AABBd
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity

object VehicleEntityCollision {
    private const val CANDIDATE_INFLATION = 0.6
    private const val COLLISION_EPSILON = 1.0e-12

    @JvmStatic
    fun adjustEntityMovementForVehicleBodyCollisions(
        entity: Entity,
        movement: Vec3,
        entityBoundingBox: AABB,
        level: Level
    ): Vec3 {
        if (movement.lengthSqr() <= COLLISION_EPSILON) return movement
        if (shouldSkip(entity)) return movement

        val shipWorld = level.shipWorld ?: return movement
        val stepHeight = entity.maxUpStep().toDouble()
        val queryBox = entityBoundingBox
            .expandTowards(movement)
            .inflate(CANDIDATE_INFLATION, CANDIDATE_INFLATION + stepHeight, CANDIDATE_INFLATION)
            .toAabbd()

        var adjustedMovement = movement
        val vehicleBodies = shipWorld.allBodies
            .getIntersecting(queryBox, level.dimensionId)
            .filter { body -> VehicleManager.getVehicle(level, body.id) != null }
            .sortedBy { body -> body.id }

        for (body in vehicleBodies) {
            val shape = body.collisionShape ?: continue
            adjustedMovement = EntityShipCollisionUtils.adjustEntityMovementForPrimitiveShapeCollision(
                entity,
                adjustedMovement,
                entityBoundingBox,
                shape,
                body.kinematics.transform.toWorld
            )
            if (adjustedMovement.lengthSqr() <= COLLISION_EPSILON) break
        }

        return adjustedMovement
    }

    private fun shouldSkip(entity: Entity): Boolean {
        return entity is BikeSeatEntity || entity.vehicle is BikeSeatEntity
    }

    private fun AABB.toAabbd(): AABBd {
        return AABBd(minX, minY, minZ, maxX, maxY, maxZ)
    }
}
