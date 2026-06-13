package org.valkyrienskies.skyriders.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import org.joml.Matrix4dc
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.events.CollisionEvent
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import java.util.concurrent.ConcurrentLinkedQueue
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
    private const val VEHICLE_CRASH_DAMAGE_COOLDOWN_TICKS = 12L
    private const val COLLISION_START_MIN_RELATIVE_SPEED = 7.0
    private const val COLLISION_START_DAMAGE_PER_SPEED = 1.15
    private const val COLLISION_START_STEP_VERTICAL_COMPENSATION = 0.65
    private const val COLLISION_START_MAX_STEP_COMPENSATION = 2.5
    private const val COLLISION_START_TERRAIN_STEP_MIN_VERTICAL_SPEED = 0.35
    private const val COLLISION_START_TERRAIN_STEP_VERTICAL_RATIO = 0.06
    private const val COLLISION_START_TERRAIN_STEP_IGNORE_SPEED = 10.0
    private const val COLLISION_START_TERRAIN_STEP_DAMAGE_SCALE = 0.28
    private const val MOONDROP_SPINOUT_DURATION = 2.35
    private const val MOONDROP_SPINOUT_YAW_SPEED = 7.0

    private val lastDamageTickByImpact = HashMap<ImpactKey, Long>()
    private val lastCrashDamageTickByVehicle = HashMap<Long, Long>()
    private val pendingCollisionCrashDamage = ConcurrentLinkedQueue<CollisionCrashDamage>()

    fun onCollisionStart(event: CollisionEvent) {
        val contactSpeed = event.contactPoints
            .map { contact ->
                CollisionContactSpeed(
                    planarSpeed = planarLength(contact.velocity),
                    verticalSpeed = kotlin.math.abs(contact.velocity.y())
                )
            }
            .maxByOrNull { it.planarSpeed }
            ?: CollisionContactSpeed.ZERO

        pendingCollisionCrashDamage.add(CollisionCrashDamage(event.dimensionId, event.shipIdA, event.shipIdB, contactSpeed.planarSpeed, contactSpeed.verticalSpeed))
        pendingCollisionCrashDamage.add(CollisionCrashDamage(event.dimensionId, event.shipIdB, event.shipIdA, contactSpeed.planarSpeed, contactSpeed.verticalSpeed))
    }

    fun tick(level: ServerLevel, vehicles: Iterable<IVehicle>) {
        val shipWorld = level.shipWorld ?: return
        val now = level.gameTime
        cleanupOldCooldowns(now)
        applyPendingCollisionCrashDamage(level, now)

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

                val damage = impactDamage(vehicle, relativeSpeed, driver)
                if (damage <= 0.0) continue

                if (target.hurt(SkyridersDamageTypes.vehicleImpact(level, driver), damage.toFloat())) {
                    lastDamageTickByImpact[ImpactKey(vehicle.bodyId, target.id)] = now
                    VehicleDamage.damageCrash(level, vehicle, damage * 0.16)
                    pushTarget(target, relativeVelocity, relativeSpeed, damage)
                }
            }
        }
    }

    private fun applyPendingCollisionCrashDamage(level: ServerLevel, now: Long) {
        val deferred = ArrayList<CollisionCrashDamage>()
        while (true) {
            val pending = pendingCollisionCrashDamage.poll() ?: break
            if (pending.dimensionId != level.dimensionId) {
                deferred.add(pending)
                continue
            }

            val lastTick = lastCrashDamageTickByVehicle[pending.bodyId]
            if (lastTick != null && now - lastTick < VEHICLE_CRASH_DAMAGE_COOLDOWN_TICKS) continue
            val vehicle = VehicleManager.getVehicle(level.dimensionId, pending.bodyId) ?: continue
            applyMoondropCollisionSpinOut(level, vehicle, pending.otherBodyId)
            val otherVehicle = VehicleManager.getVehicle(level.dimensionId, pending.otherBodyId)
            val body = level.shipWorld?.allBodies?.getById(vehicle.bodyId)
            val rawImpactSpeed = max(pending.contactPlanarSpeed, body?.kinematics?.velocity?.let(::planarLength) ?: 0.0)
            val stepCompensation = (pending.contactVerticalSpeed * COLLISION_START_STEP_VERTICAL_COMPENSATION)
                .coerceIn(0.0, COLLISION_START_MAX_STEP_COMPENSATION)
            val impactSpeed = (rawImpactSpeed - stepCompensation).coerceAtLeast(0.0)
            if (impactSpeed < COLLISION_START_MIN_RELATIVE_SPEED) continue
            val terrainStepClip = otherVehicle == null &&
                pending.contactVerticalSpeed >= COLLISION_START_TERRAIN_STEP_MIN_VERTICAL_SPEED &&
                pending.contactVerticalSpeed >= pending.contactPlanarSpeed * COLLISION_START_TERRAIN_STEP_VERTICAL_RATIO
            if (terrainStepClip && impactSpeed < COLLISION_START_TERRAIN_STEP_IGNORE_SPEED) continue
            val terrainStepScale = if (terrainStepClip) COLLISION_START_TERRAIN_STEP_DAMAGE_SCALE else 1.0
            val massScale = sqrt(vehicle.vehicleDefinition.body.mass / 300.0).coerceIn(0.65, 2.4)
            val severity = (impactSpeed - COLLISION_START_MIN_RELATIVE_SPEED) *
                massScale *
                COLLISION_START_DAMAGE_PER_SPEED *
                terrainStepScale
            if (severity <= 0.0) continue
            VehicleDamage.damageCrash(level, vehicle, severity)
            lastCrashDamageTickByVehicle[pending.bodyId] = now
        }
        deferred.forEach(pendingCollisionCrashDamage::add)
    }

    private fun applyMoondropCollisionSpinOut(level: ServerLevel, source: IVehicle, targetBodyId: Long) {
        if (!VehicleStatusEffects.isMoondropActive(source)) return
        val target = VehicleManager.getVehicle(level.dimensionId, targetBodyId) ?: return
        if (VehicleStatusEffects.isMoondropActive(target)) return
        VehicleStatusEffects.applySpinOut(target, duration = MOONDROP_SPINOUT_DURATION, yawSpeed = MOONDROP_SPINOUT_YAW_SPEED)
    }

    private fun impactDamage(vehicle: IVehicle, relativeSpeed: Double, driver: LivingEntity?): Double {
        val body = vehicle.vehicleDefinition.body
        val speedOverThreshold = relativeSpeed - MIN_DAMAGE_SPEED
        val massScale = sqrt(body.mass / 300.0).coerceIn(0.65, 2.3)
        val footprint = max(0.1, body.collisionBoxSize.x * body.collisionBoxSize.z)
        val sizeScale = sqrt(footprint / 1.6).coerceIn(0.75, 1.9)
        val impactScale = body.impactDamageScale.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val maxDamage = body.impactDamageCap.takeIf { it.isFinite() && it > 0.0 } ?: MAX_DAMAGE
        return (speedOverThreshold * DAMAGE_PER_SPEED_OVER_THRESHOLD * massScale * sizeScale *
            impactScale * VehicleImpairmentEffects.impactDamageMultiplier(driver))
            .coerceIn(0.0, maxDamage)
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
        return body.resolvedCollisionBoxes()
            .map { transformedCollisionBox(it, transform) }
            .reduce(::union)
    }

    private fun transformedCollisionBox(box: VehicleCollisionBoxDefinition, transform: Matrix4dc): AABB {
        val half = Vector3d(box.size).mul(0.5)
        val offset = box.offset
        val rotation = box.rotationQuaternion()
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY

        for (x in doubleArrayOf(offset.x - half.x, offset.x + half.x)) {
            for (y in doubleArrayOf(offset.y - half.y, offset.y + half.y)) {
                for (z in doubleArrayOf(offset.z - half.z, offset.z + half.z)) {
                    val point = transform.transformPosition(rotateAroundOffset(Vector3d(x, y, z), offset, rotation))
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

    private fun rotateAroundOffset(point: Vector3d, offset: Vector3dc, rotation: Quaterniond): Vector3d {
        return rotation.transform(point.sub(offset)).add(offset)
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
        lastCrashDamageTickByVehicle.entries.removeIf { (_, tick) ->
            now - tick > VEHICLE_CRASH_DAMAGE_COOLDOWN_TICKS * 4
        }
    }

    private data class ImpactKey(val bodyId: Long, val entityId: Int)
    private data class CollisionCrashDamage(
        val dimensionId: DimensionId,
        val bodyId: Long,
        val otherBodyId: Long,
        val contactPlanarSpeed: Double,
        val contactVerticalSpeed: Double
    )

    private data class CollisionContactSpeed(
        val planarSpeed: Double,
        val verticalSpeed: Double
    ) {
        companion object {
            val ZERO = CollisionContactSpeed(0.0, 0.0)
        }
    }
}
