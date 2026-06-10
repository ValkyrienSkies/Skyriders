package org.valkyrienskies.skyriders.content.entity

import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.SkyridersSounds
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import org.valkyrienskies.skyriders.util.VehiclePhysicsMath
import kotlin.math.sqrt

class SugarRocketEntity(type: EntityType<SugarRocketEntity>, level: Level) : Entity(type, level) {
    var ownerBodyId: BodyId = 0L

    var homing: Boolean
        get() = entityData.get(HOMING)
        set(value) = entityData.set(HOMING, value)

    private var distanceTravelled = 0.0

    init {
        blocksBuilding = false
        noPhysics = true
    }

    override fun tick() {
        super.tick()

        if (level().isClientSide) {
            setPosition(nextPosition(deltaMovement))
            return
        }

        val serverLevel = level() as? ServerLevel ?: return
        if (tickCount > MAX_LIFETIME_TICKS || distanceTravelled >= MAX_TRAVEL_DISTANCE) {
            explode(serverLevel, position())
            return
        }

        if (homing) {
            updateHoming(serverLevel)
        }

        val start = position()
        val velocity = deltaMovement
        if (velocity.lengthSqr() < 1.0e-8) {
            discard()
            return
        }
        val next = start.add(velocity)
        val blockHit = serverLevel.clip(
            ClipContext(
                start,
                next,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this
            )
        )
        if (blockHit.type != HitResult.Type.MISS) {
            explode(serverLevel, blockHit.location)
            return
        }

        setPosition(next)
        distanceTravelled += velocity.length()
        findHitVehicle(serverLevel, next)?.let { target ->
            explode(serverLevel, next, target)
        }
    }

    fun launch(origin: Vec3, direction: Vec3, speed: Double = ROCKET_SPEED) {
        val safeDirection = direction.normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: Vec3(0.0, 0.0, 1.0)
        moveTo(origin.x, origin.y, origin.z, yRot, xRot)
        deltaMovement = safeDirection.scale(speed)
    }

    override fun defineSynchedData() {
        entityData.define(HOMING, false)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        ownerBodyId = compound.getLong(OWNER_BODY_ID_KEY)
        homing = compound.getBoolean(HOMING_KEY)
        distanceTravelled = compound.getDouble(DISTANCE_TRAVELLED_KEY)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putLong(OWNER_BODY_ID_KEY, ownerBodyId)
        compound.putBoolean(HOMING_KEY, homing)
        compound.putDouble(DISTANCE_TRAVELLED_KEY, distanceTravelled)
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> {
        return ClientboundAddEntityPacket(this)
    }

    private fun updateHoming(level: ServerLevel) {
        val target = findHomingTarget(level) ?: return
        val current = deltaMovement
        if (current.lengthSqr() < 1.0e-8) return

        val desired = target.subtract(position()).normalize()
        val blended = current.normalize().scale(1.0 - HOMING_TURN_RATE).add(desired.scale(HOMING_TURN_RATE)).normalize()
        deltaMovement = blended.scale(ROCKET_SPEED)
    }

    private fun findHomingTarget(level: ServerLevel): Vec3? {
        val shipWorld = level.shipWorld ?: return null
        val pos = position()
        val forward = deltaMovement.normalize()
        var bestDistanceSq = HOMING_RANGE * HOMING_RANGE
        var bestTarget: Vec3? = null

        VehicleManager.getVehicles(level).forEach { vehicle ->
            if (vehicle.bodyId == ownerBodyId) return@forEach
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: return@forEach
            val target = Vec3(body.kinematics.position.x(), body.kinematics.position.y(), body.kinematics.position.z())
            val toTarget = target.subtract(pos)
            val distanceSq = toTarget.lengthSqr()
            if (distanceSq > bestDistanceSq) return@forEach
            if (VehiclePhysicsMath.safeDot(Vector3d(forward.x, forward.y, forward.z), Vector3d(toTarget.x, toTarget.y, toTarget.z).normalize()) < HOMING_MIN_DOT) {
                return@forEach
            }
            bestDistanceSq = distanceSq
            bestTarget = target
        }
        return bestTarget
    }

    private fun findHitVehicle(level: ServerLevel, position: Vec3): IVehicle? {
        val shipWorld = level.shipWorld ?: return null
        val rocketPos = Vector3d(position.x, position.y, position.z)
        return VehicleManager.getVehicles(level).firstOrNull { vehicle ->
            if (vehicle.bodyId == ownerBodyId) return@firstOrNull false
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: return@firstOrNull false
            val hitRadius = DIRECT_HIT_RADIUS + vehicleApproxRadius(vehicle.vehicleDefinition.body.collisionBoxSize)
            body.kinematics.position.distanceSquared(rocketPos) <= hitRadius * hitRadius
        }
    }

    private fun explode(level: ServerLevel, position: Vec3, directTarget: IVehicle? = null) {
        level.playSound(
            null,
            position.x,
            position.y,
            position.z,
            SkyridersSounds.SUGAR_ROCKET_EXPLODE_SOUND.get(),
            SoundSource.NEUTRAL,
            0.95f,
            1.0f
        )
        SkyridersMod.BAD_EXPLOSION_ENTITY.get().create(level)?.let { effect ->
            effect.moveTo(position.x, position.y, position.z, random.nextFloat() * 360.0f, 0.0f)
            level.addFreshEntity(effect)
        }
        level.sendParticles(ParticleTypes.CLOUD, position.x, position.y, position.z, 18, 0.65, 0.45, 0.65, 0.08)

        val shipWorld = level.shipWorld
        val explosionPos = Vector3d(position.x, position.y, position.z)
        VehicleManager.getVehicles(level).forEach { vehicle ->
            if (vehicle.bodyId == ownerBodyId) return@forEach
            val body = shipWorld?.allBodies?.getById(vehicle.bodyId) ?: return@forEach
            val radius = BLAST_RADIUS + vehicleApproxRadius(vehicle.vehicleDefinition.body.collisionBoxSize)
            if (body.kinematics.position.distanceSquared(explosionPos) > radius * radius) return@forEach
            val duration = if (vehicle.bodyId == directTarget?.bodyId) 2.25 else 1.45
            VehicleStatusEffects.applySpinOut(vehicle, duration = duration, yawSpeed = 12.0)
        }
        discard()
    }

    private fun nextPosition(velocity: Vec3): Vec3 {
        return position().add(velocity)
    }

    private fun setPosition(position: Vec3) {
        setPos(position.x, position.y, position.z)
    }

    private fun vehicleApproxRadius(size: Vector3d): Double {
        return sqrt(size.x * size.x + size.z * size.z) * 0.5
    }

    companion object {
        private const val OWNER_BODY_ID_KEY = "OwnerBodyId"
        private const val HOMING_KEY = "Homing"
        private const val DISTANCE_TRAVELLED_KEY = "DistanceTravelled"
        private const val MAX_LIFETIME_TICKS = 20 * 5
        private const val MAX_TRAVEL_DISTANCE = 56.0
        private const val ROCKET_SPEED = 1.65
        private const val DIRECT_HIT_RADIUS = 0.35
        private const val BLAST_RADIUS = 3.25
        private const val HOMING_RANGE = 28.0
        private const val HOMING_TURN_RATE = 0.12
        private const val HOMING_MIN_DOT = -0.15
        private val HOMING: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(SugarRocketEntity::class.java, EntityDataSerializers.BOOLEAN)
    }
}
