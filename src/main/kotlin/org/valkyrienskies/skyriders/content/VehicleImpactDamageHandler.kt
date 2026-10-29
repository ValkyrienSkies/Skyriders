package org.valkyrienskies.skyriders.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.events.CollisionEvent
import org.valkyrienskies.core.api.event.RegisteredListener
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.util.VehicleImpactMath
import org.valkyrienskies.skyriders.util.VehicleCollisionSweep
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
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
    private const val MOONDROP_SPINOUT_DURATION = 2.35
    private const val MOONDROP_SPINOUT_YAW_SPEED = 7.0

    private val lastDamageTickByImpact = HashMap<ImpactKey, Long>()
    private val lastCrashDamageTickByVehicle = HashMap<VehicleKey, Long>()
    private val pendingCollisionCrashDamage = ConcurrentLinkedQueue<CollisionCrashDamage>()
    private var collisionListener: RegisteredListener? = null

    fun updateCollisionListener() {
        if (VehicleManager.hasServerVehicles()) {
            if (collisionListener == null) collisionListener = vsApi.collisionStartEvent.on(::onCollisionStart)
        } else {
            stopCollisionListener()
        }
    }

    fun stopCollisionListener() {
        collisionListener?.unregister()
        collisionListener = null
        pendingCollisionCrashDamage.clear()
    }

    fun clear(level: ServerLevel) {
        lastDamageTickByImpact.keys.removeIf { it.dimensionId == level.dimensionId }
        lastCrashDamageTickByVehicle.keys.removeIf { it.dimensionId == level.dimensionId }
        pendingCollisionCrashDamage.removeIf { it.dimensionId == level.dimensionId }
    }

    fun onCollisionStart(event: CollisionEvent) {
        val vehicleA = VehicleManager.getVehicle(event.dimensionId, event.shipIdA) != null
        val vehicleB = VehicleManager.getVehicle(event.dimensionId, event.shipIdB) != null
        if (!vehicleA && !vehicleB) return

        val previousA = event.physLevel.getBodyById(event.shipIdA)?.prevTickKinematics
        val previousB = event.physLevel.getBodyById(event.shipIdB)?.prevTickKinematics
        val contactSpeed = event.contactPoints
            .maxOfOrNull { contact ->
                val previousRelative = VehicleImpactMath.pointVelocity(previousA, contact.position)
                    .sub(VehicleImpactMath.pointVelocity(previousB, contact.position))
                max(
                    VehicleImpactMath.normalSpeed(contact.velocity, contact.normal),
                    VehicleImpactMath.normalSpeed(previousRelative, contact.normal)
                )
            }
            ?: 0.0

        if (vehicleA) pendingCollisionCrashDamage.add(CollisionCrashDamage(event.dimensionId, event.shipIdA, event.shipIdB, contactSpeed))
        if (vehicleB) pendingCollisionCrashDamage.add(CollisionCrashDamage(event.dimensionId, event.shipIdB, event.shipIdA, contactSpeed))
    }

    fun tick(level: ServerLevel, vehicles: Iterable<IVehicle>) {
        val shipWorld = level.shipWorld ?: return
        val now = level.gameTime
        cleanupOldCooldowns(level.dimensionId, now)
        applyPendingCollisionCrashDamage(level, now)
        val targets by lazy {
            level.allEntities.filterIsInstance<LivingEntity>().filter(::canDamage).map { target ->
                val movement = Vector3d(target.x - target.xo, target.y - target.yo, target.z - target.zo)
                val start = target.boundingBox.move(-movement.x, -movement.y, -movement.z)
                MovingTarget(target, start, movement, start.minmax(target.boundingBox))
            }
        }

        for (vehicle in vehicles) {
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: continue
            val bodyDef = vehicle.vehicleDefinition.body
            val shapes = bodyDef.resolvedCollisionBoxes()
            val sweptBox = VehicleCollisionSweep.sweptBounds(shapes, body.prevTickKinematics.transform,
                body.kinematics.transform).inflate(QUERY_INFLATION)
            val driver = findDriver(level, vehicle, sweptBox)
            for (moving in targets) {
                if (!sweptBox.intersects(moving.bounds)) continue
                val target = moving.entity
                if (isOnCooldown(vehicle, target, now)) continue

                val hit = VehicleCollisionSweep.cast(shapes, body.prevTickKinematics.transform, body.kinematics.transform,
                    moving.start, moving.movement) ?: continue
                val relativeVelocity = VehicleImpactMath.pointVelocity(body.prevTickKinematics, hit.point)
                    .sub(Vector3d(moving.movement).mul(ENTITY_VELOCITY_TICKS_PER_SECOND))
                val relativeSpeed = relativeVelocity.dot(hit.normal).coerceAtLeast(0.0)
                if (relativeSpeed < MIN_DAMAGE_SPEED) continue
                val damage = impactDamage(vehicle, relativeSpeed, driver)
                if (damage <= 0.0) continue

                if (target.hurt(SkyridersDamageTypes.vehicleImpact(level, driver), damage.toFloat())) {
                    lastDamageTickByImpact[ImpactKey(vehicle.level.dimensionId, vehicle.bodyId, target.id)] = now
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

            val lastTick = lastCrashDamageTickByVehicle[VehicleKey(pending.dimensionId, pending.bodyId)]
            if (lastTick != null && now - lastTick < VEHICLE_CRASH_DAMAGE_COOLDOWN_TICKS) continue
            val vehicle = VehicleManager.getVehicle(level.dimensionId, pending.bodyId) ?: continue
            applyMoondropCollisionSpinOut(level, vehicle, pending.otherBodyId)
            val impactSpeed = pending.relativeNormalSpeed
            if (impactSpeed < COLLISION_START_MIN_RELATIVE_SPEED) continue
            val massScale = sqrt(vehicle.vehicleDefinition.body.mass / 300.0).coerceIn(0.65, 2.4)
            val severity = (impactSpeed - COLLISION_START_MIN_RELATIVE_SPEED) *
                massScale *
                COLLISION_START_DAMAGE_PER_SPEED
            if (severity <= 0.0) continue
            VehicleDamage.damageCrash(level, vehicle, severity)
            lastCrashDamageTickByVehicle[VehicleKey(pending.dimensionId, pending.bodyId)] = now
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
        val lastTick = lastDamageTickByImpact[ImpactKey(vehicle.level.dimensionId, vehicle.bodyId, target.id)] ?: return false
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

    private fun cleanupOldCooldowns(dimensionId: DimensionId, now: Long) {
        lastDamageTickByImpact.entries.removeIf { (key, tick) ->
            key.dimensionId == dimensionId && (now < tick || now - tick > DAMAGE_COOLDOWN_TICKS * 4)
        }
        lastCrashDamageTickByVehicle.entries.removeIf { (key, tick) ->
            key.dimensionId == dimensionId && (now < tick || now - tick > VEHICLE_CRASH_DAMAGE_COOLDOWN_TICKS * 4)
        }
    }

    private data class VehicleKey(val dimensionId: DimensionId, val bodyId: Long)
    private data class MovingTarget(val entity: LivingEntity, val start: AABB, val movement: Vector3d, val bounds: AABB)
    private data class ImpactKey(val dimensionId: DimensionId, val bodyId: Long, val entityId: Int)
    private data class CollisionCrashDamage(
        val dimensionId: DimensionId,
        val bodyId: Long,
        val otherBodyId: Long,
        val relativeNormalSpeed: Double
    )
}
