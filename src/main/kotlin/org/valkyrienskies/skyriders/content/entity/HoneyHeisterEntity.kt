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
import net.minecraft.sounds.SoundEvents
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
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.VehicleFuel
import org.valkyrienskies.skyriders.content.VehicleManager
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

class HoneyHeisterEntity(type: EntityType<HoneyHeisterEntity>, level: Level) : Entity(type, level) {
    var ownerBodyId: BodyId
        get() = entityData.get(OWNER_BODY_ID)
        set(value) = entityData.set(OWNER_BODY_ID, value)

    val hasFuel: Boolean
        get() = entityData.get(HAS_FUEL)

    val ignited: Boolean
        get() = entityData.get(IGNITED)

    val lineConnected: Boolean
        get() = entityData.get(LINE_CONNECTED)

    val attached: Boolean
        get() = entityData.get(ATTACHED)

    private var distanceTravelled = 0.0
    private var motorDirection = Vec3(0.0, 0.0, 1.0)
    private var attachedTargetBodyId: BodyId? = null
    private var attachedTicksRemaining = 0

    init {
        blocksBuilding = false
        noPhysics = true
    }

    override fun tick() {
        super.tick()

        if (level().isClientSide) {
            spawnHoneyParticles()
            if (!ignited) {
                deltaMovement = updateUnlitVelocity(deltaMovement)
            }
            setPosition(position().add(deltaMovement))
            return
        }

        val serverLevel = level() as? ServerLevel ?: return
        if (attachedTargetBodyId != null) {
            tickAttached(serverLevel)
            return
        }

        if (tickCount > MAX_LIFETIME_TICKS) {
            discard()
            return
        }

        if (lineConnected && distanceFromOwner(serverLevel, position()) > TETHER_RANGE) {
            snapLine()
        }

        if (!ignited) {
            tickUnlit(serverLevel)
            return
        }

        val fueled = distanceTravelled < FUEL_DISTANCE
        entityData.set(HAS_FUEL, fueled)
        if (fueled && lineConnected) {
            updateHoming(serverLevel)
        }
        updateVelocity(fueled)

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
            discard()
            return
        }

        setPosition(next)
        distanceTravelled += velocity.length()
        val hitVehicle = findHitVehicle(serverLevel, next)
        if (hitVehicle != null) {
            if (lineConnected) {
                attachToVehicle(serverLevel, hitVehicle.bodyId)
            } else {
                discard()
            }
        }
    }

    fun launch(origin: Vec3, direction: Vec3, inheritedVelocity: Vec3, ownerBodyId: BodyId) {
        val safeDirection = direction.normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: Vec3(0.0, 0.0, 1.0)
        this.ownerBodyId = ownerBodyId
        motorDirection = safeDirection
        val rotation = rotationFromDirection(safeDirection)
        moveTo(origin.x, origin.y, origin.z, rotation.first, rotation.second)
        entityData.set(IGNITED, false)
        entityData.set(HAS_FUEL, false)
        entityData.set(LINE_CONNECTED, true)
        entityData.set(ATTACHED, false)
        deltaMovement = inheritedVelocity.limitLength(PRE_IGNITION_INHERITED_SPEED_LIMIT)
            .add(sideTossVelocity(safeDirection))
    }

    override fun defineSynchedData() {
        entityData.define(OWNER_BODY_ID, 0L)
        entityData.define(HAS_FUEL, true)
        entityData.define(IGNITED, false)
        entityData.define(LINE_CONNECTED, true)
        entityData.define(ATTACHED, false)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        ownerBodyId = compound.getLong(OWNER_BODY_ID_KEY)
        distanceTravelled = compound.getDouble(DISTANCE_TRAVELLED_KEY)
        entityData.set(HAS_FUEL, compound.getBoolean(HAS_FUEL_KEY).takeIf { compound.contains(HAS_FUEL_KEY) } ?: true)
        entityData.set(IGNITED, compound.getBoolean(IGNITED_KEY).takeIf { compound.contains(IGNITED_KEY) } ?: true)
        entityData.set(LINE_CONNECTED, compound.getBoolean(LINE_CONNECTED_KEY).takeIf { compound.contains(LINE_CONNECTED_KEY) } ?: true)
        entityData.set(ATTACHED, compound.getBoolean(ATTACHED_KEY).takeIf { compound.contains(ATTACHED_KEY) } ?: false)
        attachedTargetBodyId = if (compound.contains(ATTACHED_TARGET_BODY_ID_KEY)) compound.getLong(ATTACHED_TARGET_BODY_ID_KEY) else null
        attachedTicksRemaining = compound.getInt(ATTACHED_TICKS_REMAINING_KEY)
        motorDirection = Vec3(
            compound.getDouble(MOTOR_DIRECTION_X_KEY),
            compound.getDouble(MOTOR_DIRECTION_Y_KEY),
            compound.getDouble(MOTOR_DIRECTION_Z_KEY)
        ).normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: Vec3(0.0, 0.0, 1.0)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putLong(OWNER_BODY_ID_KEY, ownerBodyId)
        compound.putDouble(DISTANCE_TRAVELLED_KEY, distanceTravelled)
        compound.putBoolean(HAS_FUEL_KEY, hasFuel)
        compound.putBoolean(IGNITED_KEY, ignited)
        compound.putBoolean(LINE_CONNECTED_KEY, lineConnected)
        compound.putBoolean(ATTACHED_KEY, attached)
        attachedTargetBodyId?.let { compound.putLong(ATTACHED_TARGET_BODY_ID_KEY, it) }
        compound.putInt(ATTACHED_TICKS_REMAINING_KEY, attachedTicksRemaining)
        compound.putDouble(MOTOR_DIRECTION_X_KEY, motorDirection.x)
        compound.putDouble(MOTOR_DIRECTION_Y_KEY, motorDirection.y)
        compound.putDouble(MOTOR_DIRECTION_Z_KEY, motorDirection.z)
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> = ClientboundAddEntityPacket(this)

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean {
        return distance < RENDER_DISTANCE * RENDER_DISTANCE
    }

    private fun tickUnlit(level: ServerLevel) {
        val start = position()
        val nextVelocity = updateUnlitVelocity(deltaMovement)
        val next = start.add(nextVelocity)
        if (lineConnected && distanceFromOwner(level, next) > TETHER_RANGE) {
            snapLine()
        }

        val blockHit = level.clip(
            ClipContext(
                start,
                next,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this
            )
        )
        if (blockHit.type != HitResult.Type.MISS) {
            setPosition(blockHit.location)
            deltaMovement = Vec3.ZERO
            if (tickCount >= IGNITION_DELAY_TICKS) {
                ignite(level)
            }
            return
        }

        deltaMovement = nextVelocity
        setPosition(next)
        if (tickCount >= IGNITION_DELAY_TICKS) {
            ignite(level)
        }
    }

    private fun ignite(level: ServerLevel) {
        entityData.set(IGNITED, true)
        entityData.set(HAS_FUEL, true)
        distanceTravelled = 0.0
        deltaMovement = deltaMovement.scale(IGNITION_VELOCITY_CARRY)
            .add(motorDirection.scale(INITIAL_THRUST_SPEED))
            .limitLength(MAX_SPEED)
        level.playSound(null, x, y, z, SoundEvents.HONEY_BLOCK_STEP, SoundSource.NEUTRAL, 1.0f, 1.15f)
        level.sendParticles(ParticleTypes.FALLING_HONEY, x, y, z, 14, 0.14, 0.14, 0.14, 0.04)
    }

    private fun updateHoming(level: ServerLevel) {
        val target = findHomingTarget(level) ?: return
        val desired = target.subtract(position()).normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: return
        val current = motorDirection.normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: desired
        val speed = deltaMovement.length().coerceAtLeast(INITIAL_THRUST_SPEED)
        val maxTurnRadians = (HOMING_LATERAL_ACCELERATION / speed)
            .coerceIn(HOMING_MIN_TURN_RADIANS, HOMING_MAX_TURN_RADIANS)
        motorDirection = rotateTowards(current, desired, maxTurnRadians)
    }

    private fun findHomingTarget(level: ServerLevel): Vec3? {
        val shipWorld = level.shipWorld ?: return null
        val pos = position()
        var bestDistanceSq = HOMING_RANGE * HOMING_RANGE
        var bestTarget: Vec3? = null

        VehicleManager.getVehicles(level).forEach { vehicle ->
            if (vehicle.bodyId == ownerBodyId) return@forEach
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: return@forEach
            val target = Vec3(body.kinematics.position.x(), body.kinematics.position.y(), body.kinematics.position.z())
            val distanceSq = target.distanceToSqr(pos)
            if (distanceSq > bestDistanceSq) return@forEach
            bestDistanceSq = distanceSq
            bestTarget = target
        }
        return bestTarget
    }

    private fun findHitVehicle(level: ServerLevel, position: Vec3): IVehicle? {
        val shipWorld = level.shipWorld ?: return null
        val heisterPos = Vector3d(position.x, position.y, position.z)
        return VehicleManager.getVehicles(level).firstOrNull { vehicle ->
            if (vehicle.bodyId == ownerBodyId) return@firstOrNull false
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: return@firstOrNull false
            val hitRadius = DIRECT_HIT_RADIUS + vehicleApproxRadius(vehicle.vehicleDefinition.body.collisionBoxSize)
            body.kinematics.position.distanceSquared(heisterPos) <= hitRadius * hitRadius
        }
    }

    private fun attachToVehicle(level: ServerLevel, targetBodyId: BodyId) {
        attachedTargetBodyId = targetBodyId
        attachedTicksRemaining = ATTACHED_DURATION_TICKS
        entityData.set(ATTACHED, true)
        tickAttached(level)
    }

    private fun tickAttached(level: ServerLevel) {
        val targetBodyId = attachedTargetBodyId ?: return discard()
        val shipWorld = level.shipWorld ?: return snapAndDiscard()
        val ownerBody = shipWorld.allBodies.getById(ownerBodyId) ?: return snapAndDiscard()
        val targetBody = shipWorld.allBodies.getById(targetBodyId) ?: return snapAndDiscard()
        val ownerVehicle = VehicleManager.getVehicle(level, ownerBodyId) ?: return snapAndDiscard()
        val targetVehicle = VehicleManager.getVehicle(level, targetBodyId) ?: return snapAndDiscard()
        val ownerPosition = ownerBody.kinematics.position
        val targetPosition = targetBody.kinematics.position
        if (ownerPosition.distanceSquared(targetPosition) > TETHER_RANGE * TETHER_RANGE) {
            snapAndDiscard()
            return
        }

        setPosition(Vec3(targetPosition.x(), targetPosition.y() + ATTACHED_Y_OFFSET, targetPosition.z()))
        deltaMovement = Vec3(targetBody.kinematics.velocity.x(), targetBody.kinematics.velocity.y(), targetBody.kinematics.velocity.z())
        val transferred = VehicleFuel.transfer(targetVehicle, ownerVehicle, FUEL_STEAL_PER_TICK)
        if (transferred > 0.0) {
            level.sendParticles(ParticleTypes.FALLING_HONEY, x, y, z, 2, 0.18, 0.12, 0.18, 0.025)
        }
        if (tickCount % ATTACHED_SOUND_INTERVAL_TICKS == 0) {
            level.playSound(null, x, y, z, SoundEvents.HONEY_DRINK, SoundSource.NEUTRAL, 0.55f, 1.15f)
        }

        attachedTicksRemaining--
        if (attachedTicksRemaining <= 0) {
            snapAndDiscard()
        }
    }

    private fun updateVelocity(fueled: Boolean) {
        deltaMovement = if (fueled) {
            val currentSpeed = deltaMovement.length().coerceAtLeast(INITIAL_THRUST_SPEED)
            val targetSpeed = (currentSpeed + ACCELERATION).coerceAtMost(MAX_SPEED)
            val currentDirection = deltaMovement.normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: motorDirection
            val thrustDirection = motorDirection.normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: currentDirection
            currentDirection
                .scale(1.0 - VELOCITY_ALIGNMENT_RATE)
                .add(thrustDirection.scale(VELOCITY_ALIGNMENT_RATE))
                .normalize()
                .scale(targetSpeed)
        } else {
            Vec3(
                deltaMovement.x * OUT_OF_FUEL_DRAG,
                deltaMovement.y * OUT_OF_FUEL_DRAG - OUT_OF_FUEL_GRAVITY,
                deltaMovement.z * OUT_OF_FUEL_DRAG
            )
        }
    }

    private fun updateUnlitVelocity(velocity: Vec3): Vec3 {
        return Vec3(
            velocity.x * PRE_IGNITION_DRAG,
            velocity.y * PRE_IGNITION_DRAG - PRE_IGNITION_GRAVITY,
            velocity.z * PRE_IGNITION_DRAG
        )
    }

    private fun spawnHoneyParticles() {
        if (!ignited && !attached) return
        val velocity = deltaMovement
        val back = if (velocity.lengthSqr() > 1.0e-8) velocity.normalize().scale(-0.38) else Vec3.ZERO
        val px = x + back.x + (random.nextDouble() - 0.5) * 0.14
        val py = y + back.y + (random.nextDouble() - 0.5) * 0.14
        val pz = z + back.z + (random.nextDouble() - 0.5) * 0.14
        level().addParticle(ParticleTypes.FALLING_HONEY, px, py, pz, -velocity.x * 0.015, -velocity.y * 0.015, -velocity.z * 0.015)
        if (attached && random.nextBoolean()) {
            level().addParticle(ParticleTypes.DRIPPING_HONEY, x, y + 0.1, z, 0.0, -0.01, 0.0)
        }
    }

    private fun distanceFromOwner(level: ServerLevel, position: Vec3): Double {
        val ownerBody = level.shipWorld?.allBodies?.getById(ownerBodyId) ?: return Double.POSITIVE_INFINITY
        return sqrt(ownerBody.kinematics.position.distanceSquared(Vector3d(position.x, position.y, position.z)))
    }

    private fun snapLine() {
        entityData.set(LINE_CONNECTED, false)
    }

    private fun snapAndDiscard() {
        snapLine()
        discard()
    }

    private fun setPosition(position: Vec3) {
        setPos(position.x, position.y, position.z)
    }

    private fun vehicleApproxRadius(size: Vector3d): Double {
        return sqrt(size.x * size.x + size.z * size.z) * 0.5
    }

    private fun sideTossVelocity(direction: Vec3): Vec3 {
        val side = Vec3(-direction.z, 0.0, direction.x).normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: Vec3(1.0, 0.0, 0.0)
        val sideSign = if (random.nextBoolean()) 1.0 else -1.0
        val sideSpeed = PRE_IGNITION_MIN_SIDE_TOSS + random.nextDouble() * (PRE_IGNITION_MAX_SIDE_TOSS - PRE_IGNITION_MIN_SIDE_TOSS)
        val forwardSpeed = PRE_IGNITION_FORWARD_TOSS * random.nextDouble()
        val upSpeed = PRE_IGNITION_MIN_UPWARD_TOSS + random.nextDouble() * (PRE_IGNITION_MAX_UPWARD_TOSS - PRE_IGNITION_MIN_UPWARD_TOSS)
        return side.scale(sideSign * sideSpeed).add(direction.scale(forwardSpeed)).add(0.0, upSpeed, 0.0)
    }

    private fun rotationFromDirection(direction: Vec3): Pair<Float, Float> {
        val horizontal = sqrt(direction.x * direction.x + direction.z * direction.z)
        val yaw = -Math.toDegrees(kotlin.math.atan2(-direction.x, -direction.z)).toFloat()
        val pitch = Math.toDegrees(kotlin.math.atan2(direction.y, horizontal)).toFloat()
        return yaw to pitch
    }

    private fun rotateTowards(current: Vec3, desired: Vec3, maxRadians: Double): Vec3 {
        val dot = current.dot(desired).coerceIn(-1.0, 1.0)
        val angle = acos(dot)
        if (angle <= maxRadians || angle < 1.0e-6) return desired
        val sinAngle = sin(angle)
        if (sinAngle < 1.0e-6) return current
        val t = maxRadians / angle
        return current.scale(sin((1.0 - t) * angle) / sinAngle)
            .add(desired.scale(sin(t * angle) / sinAngle))
            .normalize()
    }

    private fun Vec3.limitLength(maxLength: Double): Vec3 {
        val length = length()
        if (length <= maxLength || length < 1.0e-8) return this
        return scale(maxLength / length)
    }

    companion object {
        private const val OWNER_BODY_ID_KEY = "OwnerBodyId"
        private const val HAS_FUEL_KEY = "HasFuel"
        private const val IGNITED_KEY = "Ignited"
        private const val LINE_CONNECTED_KEY = "LineConnected"
        private const val ATTACHED_KEY = "Attached"
        private const val ATTACHED_TARGET_BODY_ID_KEY = "AttachedTargetBodyId"
        private const val ATTACHED_TICKS_REMAINING_KEY = "AttachedTicksRemaining"
        private const val DISTANCE_TRAVELLED_KEY = "DistanceTravelled"
        private const val MOTOR_DIRECTION_X_KEY = "MotorDirectionX"
        private const val MOTOR_DIRECTION_Y_KEY = "MotorDirectionY"
        private const val MOTOR_DIRECTION_Z_KEY = "MotorDirectionZ"
        private const val MAX_LIFETIME_TICKS = 20 * 16
        private const val ATTACHED_DURATION_TICKS = 20 * 10
        private const val TETHER_RANGE = 30.0
        private const val FUEL_DISTANCE = 30.0
        private const val ACCELERATION = 0.105
        private const val MAX_SPEED = 2.25
        private const val IGNITION_DELAY_TICKS = 9
        private const val PRE_IGNITION_GRAVITY = 0.045
        private const val PRE_IGNITION_DRAG = 0.985
        private const val PRE_IGNITION_INHERITED_SPEED_LIMIT = 1.0
        private const val PRE_IGNITION_MIN_SIDE_TOSS = 0.16
        private const val PRE_IGNITION_MAX_SIDE_TOSS = 0.34
        private const val PRE_IGNITION_FORWARD_TOSS = 0.08
        private const val PRE_IGNITION_MIN_UPWARD_TOSS = 0.24
        private const val PRE_IGNITION_MAX_UPWARD_TOSS = 0.4
        private const val IGNITION_VELOCITY_CARRY = 0.25
        private const val INITIAL_THRUST_SPEED = 0.42
        private const val VELOCITY_ALIGNMENT_RATE = 0.28
        private const val OUT_OF_FUEL_DRAG = 0.975
        private const val OUT_OF_FUEL_GRAVITY = 0.055
        private const val DIRECT_HIT_RADIUS = 0.35
        private const val HOMING_RANGE = 30.0
        private const val HOMING_LATERAL_ACCELERATION = 0.17
        private const val HOMING_MIN_TURN_RADIANS = 0.035
        private const val HOMING_MAX_TURN_RADIANS = 0.19
        private const val ATTACHED_Y_OFFSET = 0.45
        private const val FUEL_STEAL_PER_SECOND = 3.0
        private const val FUEL_STEAL_PER_TICK = FUEL_STEAL_PER_SECOND / 20.0
        private const val ATTACHED_SOUND_INTERVAL_TICKS = 8
        private const val RENDER_DISTANCE = 192.0
        private val OWNER_BODY_ID: EntityDataAccessor<Long> =
            SynchedEntityData.defineId(HoneyHeisterEntity::class.java, EntityDataSerializers.LONG)
        private val HAS_FUEL: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(HoneyHeisterEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val IGNITED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(HoneyHeisterEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val LINE_CONNECTED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(HoneyHeisterEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val ATTACHED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(HoneyHeisterEntity::class.java, EntityDataSerializers.BOOLEAN)
    }
}
