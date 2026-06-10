package org.valkyrienskies.skyriders.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import org.joml.Matrix4dc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object VehicleImpactDamageHandler {
    private const val MIN_DAMAGE_SPEED = 3.5
    private const val DAMAGE_COOLDOWN_TICKS = 10L
    private const val QUERY_INFLATION = 0.25
    private const val DRIVER_QUERY_INFLATION = 4.0
    private const val DAMAGE_PER_SPEED_OVER_THRESHOLD = 1.15
    private const val MAX_DAMAGE = 20.0
    private const val ENTITY_VELOCITY_TICKS_PER_SECOND = 20.0

    private val lastDamageTickByImpact = HashMap<ImpactKey, Long>()

    fun tick(level: ServerLevel, vehicles: Iterable<IVehicle>) {
        val shipWorld = level.shipWorld ?: return
        val now = level.gameTime
        cleanupOldCooldowns(now)

        for (vehicle in vehicles) {
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: continue
            val velocity = body.kinematics.velocity
            val vehicleSpeed = planarLength(velocity)
            if (vehicleSpeed < MIN_DAMAGE_SPEED) continue

            val bodyDef = vehicle.vehicleDefinition.body
            val currentBox = transformedBodyBox(bodyDef, body.kinematics.transform.toWorld)
            val previousBox = transformedBodyBox(bodyDef, body.prevTickKinematics.transform.toWorld)
            val sweptBox = union(currentBox, previousBox).inflate(QUERY_INFLATION)
            val driver = findDriver(level, vehicle, sweptBox)
            val targets = level.getEntitiesOfClass(LivingEntity::class.java, sweptBox) { target ->
                target != null && canDamage(target)
            }

            for (target in targets) {
                if (isOnCooldown(vehicle, target, now)) continue

                val targetVelocity = target.deltaMovement.scale(ENTITY_VELOCITY_TICKS_PER_SECOND)
                val relativeVelocity = Vector3d(
                    velocity.x() - targetVelocity.x,
                    0.0,
                    velocity.z() - targetVelocity.z
                )
                val relativeSpeed = relativeVelocity.length()
                if (relativeSpeed < MIN_DAMAGE_SPEED) continue

                if (!sweptBox.intersects(target.boundingBox.inflate(0.05))) continue

                val damage = impactDamage(vehicle, relativeSpeed)
                if (damage <= 0.0) continue

                if (target.hurt(SkyridersDamageTypes.vehicleImpact(level, driver), damage.toFloat())) {
                    lastDamageTickByImpact[ImpactKey(vehicle.bodyId, target.id)] = now
                    pushTarget(target, relativeVelocity, relativeSpeed, damage)
                }
            }
        }
    }

    private fun impactDamage(vehicle: IVehicle, relativeSpeed: Double): Double {
        val body = vehicle.vehicleDefinition.body
        val speedOverThreshold = relativeSpeed - MIN_DAMAGE_SPEED
        val massScale = sqrt(body.mass / 300.0).coerceIn(0.65, 2.3)
        val footprint = max(0.1, body.collisionBoxSize.x * body.collisionBoxSize.z)
        val sizeScale = sqrt(footprint / 1.6).coerceIn(0.75, 1.9)
        return (speedOverThreshold * DAMAGE_PER_SPEED_OVER_THRESHOLD * massScale * sizeScale)
            .coerceIn(0.0, MAX_DAMAGE)
    }

    private fun canDamage(target: LivingEntity): Boolean {
        if (!target.isAlive || target.isRemoved) return false
        return target.vehicle !is BikeSeatEntity
    }

    private fun findDriver(level: ServerLevel, vehicle: IVehicle, sweptBox: AABB): LivingEntity? {
        return level.getEntitiesOfClass(BikeSeatEntity::class.java, sweptBox.inflate(DRIVER_QUERY_INFLATION)) { seat ->
            seat != null && seat.bodyId == vehicle.bodyId && seat.isDriverSeat()
        }.firstNotNullOfOrNull { seat -> seat.controllingPassenger }
    }

    private fun isOnCooldown(vehicle: IVehicle, target: LivingEntity, now: Long): Boolean {
        val lastTick = lastDamageTickByImpact[ImpactKey(vehicle.bodyId, target.id)] ?: return false
        return now - lastTick < DAMAGE_COOLDOWN_TICKS
    }

    private fun pushTarget(target: LivingEntity, relativeVelocity: Vector3dc, relativeSpeed: Double, damage: Double) {
        if (relativeSpeed <= 1.0e-6) return
        val knockback = (0.08 + damage * 0.018).coerceIn(0.08, 0.35)
        target.push(
            relativeVelocity.x() / relativeSpeed * knockback,
            (0.04 + damage * 0.006).coerceIn(0.04, 0.18),
            relativeVelocity.z() / relativeSpeed * knockback
        )
    }

    private fun transformedBodyBox(body: VehicleBodyDefinition, transform: Matrix4dc): AABB {
        val half = Vector3d(body.collisionBoxSize).mul(0.5)
        val offset = body.collisionBoxOffset
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY

        for (x in doubleArrayOf(offset.x - half.x, offset.x + half.x)) {
            for (y in doubleArrayOf(offset.y - half.y, offset.y + half.y)) {
                for (z in doubleArrayOf(offset.z - half.z, offset.z + half.z)) {
                    val point = transform.transformPosition(Vector3d(x, y, z))
                    minX = min(minX, point.x)
                    minY = min(minY, point.y)
                    minZ = min(minZ, point.z)
                    maxX = max(maxX, point.x)
                    maxY = max(maxY, point.y)
                    maxZ = max(maxZ, point.z)
                }
            }
        }

        return AABB(minX, minY, minZ, maxX, maxY, maxZ)
    }

    private fun union(a: AABB, b: AABB): AABB {
        return AABB(
            min(a.minX, b.minX),
            min(a.minY, b.minY),
            min(a.minZ, b.minZ),
            max(a.maxX, b.maxX),
            max(a.maxY, b.maxY),
            max(a.maxZ, b.maxZ)
        )
    }

    private fun planarLength(vector: Vector3dc): Double {
        return sqrt(vector.x() * vector.x() + vector.z() * vector.z())
    }

    private fun cleanupOldCooldowns(now: Long) {
        lastDamageTickByImpact.entries.removeIf { (_, tick) ->
            now - tick > DAMAGE_COOLDOWN_TICKS * 4
        }
    }

    private data class ImpactKey(val bodyId: Long, val entityId: Int)
}
