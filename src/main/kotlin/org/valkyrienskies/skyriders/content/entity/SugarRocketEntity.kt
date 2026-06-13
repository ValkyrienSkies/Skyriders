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
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.SkyridersSounds
import org.valkyrienskies.skyriders.content.VehicleDamage
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.sqrt

class SugarRocketEntity(type: EntityType<SugarRocketEntity>, level: Level) : Entity(type, level) {
    var ownerBodyId: BodyId = 0L

    var homing: Boolean
        get() = entityData.get(HOMING)
        set(value) = entityData.set(HOMING, value)

    val hasFuel: Boolean
        get() = entityData.get(HAS_FUEL)

    val ignited: Boolean
        get() = entityData.get(IGNITED)

    private var fuelTicksRemaining = 0
    private var motorDirection = Vec3(0.0, 0.0, 1.0)

    init {
        blocksBuilding = false
        noPhysics = true
    }

    override fun tick() {
        super.tick()

        if (level().isClientSide) {
            spawnTrailParticles()
            if (!ignited) {
                deltaMovement = updateUnlitVelocity(deltaMovement)
            }
            setPosition(nextPosition(deltaMovement))
            return
        }

        val serverLevel = level() as? ServerLevel ?: return
        if (tickCount > MAX_LIFETIME_TICKS) {
            discard()
            return
        }

        if (!ignited) {
            tickUnlit(serverLevel)
            return
        }

        val fueled = fuelTicksRemaining > 0
        entityData.set(HAS_FUEL, fueled)
        if (fueled && homing) {
            updateHoming(serverLevel)
        }
        updateVelocity(fueled)
        if (fueled) {
            fuelTicksRemaining--
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
        findHitVehicle(serverLevel, next)?.let { target ->
            explode(serverLevel, next, target)
        }
    }

    fun launch(origin: Vec3, direction: Vec3, inheritedVelocity: Vec3 = Vec3.ZERO) {
        val safeDirection = direction.normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: Vec3(0.0, 0.0, 1.0)
        motorDirection = safeDirection
        val rotation = rotationFromDirection(safeDirection)
        moveTo(origin.x, origin.y, origin.z, rotation.first, rotation.second)
        entityData.set(IGNITED, false)
        entityData.set(HAS_FUEL, false)
        fuelTicksRemaining = 0
        deltaMovement = inheritedVelocity.limitLength(PRE_IGNITION_INHERITED_SPEED_LIMIT)
            .add(randomTossVelocity())
    }

    override fun defineSynchedData() {
        entityData.define(HOMING, false)
        entityData.define(HAS_FUEL, true)
        entityData.define(IGNITED, false)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        ownerBodyId = compound.getLong(OWNER_BODY_ID_KEY)
        homing = compound.getBoolean(HOMING_KEY)
        val savedHasFuel = compound.getBoolean(HAS_FUEL_KEY).takeIf { compound.contains(HAS_FUEL_KEY) } ?: true
        val savedIgnited = compound.getBoolean(IGNITED_KEY).takeIf { compound.contains(IGNITED_KEY) } ?: true
        fuelTicksRemaining = savedFuelTicksRemaining(compound, savedIgnited, savedHasFuel)
        entityData.set(HAS_FUEL, fuelTicksRemaining > 0)
        entityData.set(IGNITED, savedIgnited)
        motorDirection = Vec3(
            compound.getDouble(MOTOR_DIRECTION_X_KEY),
            compound.getDouble(MOTOR_DIRECTION_Y_KEY),
            compound.getDouble(MOTOR_DIRECTION_Z_KEY)
        ).normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: Vec3(0.0, 0.0, 1.0)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putLong(OWNER_BODY_ID_KEY, ownerBodyId)
        compound.putBoolean(HOMING_KEY, homing)
        compound.putInt(FUEL_TICKS_REMAINING_KEY, fuelTicksRemaining)
        compound.putBoolean(HAS_FUEL_KEY, hasFuel)
        compound.putBoolean(IGNITED_KEY, ignited)
        compound.putDouble(MOTOR_DIRECTION_X_KEY, motorDirection.x)
        compound.putDouble(MOTOR_DIRECTION_Y_KEY, motorDirection.y)
        compound.putDouble(MOTOR_DIRECTION_Z_KEY, motorDirection.z)
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> {
        return ClientboundAddEntityPacket(this)
    }

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean {
        return distance < RENDER_DISTANCE * RENDER_DISTANCE
    }

    private fun tickUnlit(level: ServerLevel) {
        if (!homing) {
            updatePreIgnitionAimAssist(level)
        }

        val start = position()
        val nextVelocity = updateUnlitVelocity(deltaMovement)
        val next = start.add(nextVelocity)
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
        fuelTicksRemaining = FUEL_TICKS
        deltaMovement = deltaMovement.scale(IGNITION_VELOCITY_CARRY)
            .add(motorDirection.scale(INITIAL_THRUST_SPEED))
            .limitLength(MAX_ROCKET_SPEED)
        level.playSound(
            null,
            x,
            y,
            z,
            SoundEvents.FIREWORK_ROCKET_LAUNCH,
            SoundSource.NEUTRAL,
            0.9f,
            1.25f
        )
        level.sendParticles(ParticleTypes.FLAME, x, y, z, 10, 0.12, 0.12, 0.12, 0.08)
        level.sendParticles(ParticleTypes.SMOKE, x, y, z, 8, 0.12, 0.12, 0.12, 0.04)
    }

    private fun updateHoming(level: ServerLevel) {
        val currentPosition = position()
        val speed = deltaMovement.length().coerceAtLeast(INITIAL_THRUST_SPEED)
        val target = findHomingTarget(level) ?: return
        val predictedTarget = predictedTargetPosition(target, currentPosition, speed)
        val desired = predictedTarget.subtract(currentPosition).normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: return
        val current = motorDirection.normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: desired
        val maxTurnRadians = (HOMING_LATERAL_ACCELERATION / speed)
            .coerceIn(HOMING_MIN_TURN_RADIANS, HOMING_MAX_TURN_RADIANS)
        motorDirection = rotateTowards(current, desired, maxTurnRadians)
    }

    private fun updatePreIgnitionAimAssist(level: ServerLevel) {
        val currentPosition = position()
        val current = motorDirection.normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: return
        val target = findPreIgnitionAssistTarget(level, currentPosition, current) ?: return
        val predictedTarget = predictedTargetPosition(target, currentPosition, MAX_ROCKET_SPEED)
        val desired = predictedTarget.subtract(currentPosition).normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: return
        val distance = target.position.distanceTo(currentPosition).coerceAtLeast(1.0)
        val allowedAngle = PRE_IGNITION_ASSIST_MAX_ANGLE_RADIANS + atan(target.radius / distance)
        val angle = angleBetween(current, desired)
        if (angle > allowedAngle) return

        motorDirection = rotateTowards(current, desired, PRE_IGNITION_ASSIST_TURN_RADIANS)
        applyMotorRotation()
    }

    private fun findHomingTarget(level: ServerLevel): RocketTarget? {
        val shipWorld = level.shipWorld ?: return null
        val pos = position()
        var bestDistanceSq = HOMING_RANGE * HOMING_RANGE
        var bestTarget: RocketTarget? = null

        VehicleManager.getVehicles(level).forEach { vehicle ->
            if (vehicle.bodyId == ownerBodyId) return@forEach
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: return@forEach
            val target = rocketTarget(vehicle, body.kinematics.position, body.kinematics.velocity)
            val toTarget = target.position.subtract(pos)
            val distanceSq = toTarget.lengthSqr()
            if (distanceSq > bestDistanceSq) return@forEach
            bestDistanceSq = distanceSq
            bestTarget = target
        }
        return bestTarget
    }

    private fun findPreIgnitionAssistTarget(level: ServerLevel, pos: Vec3, current: Vec3): RocketTarget? {
        val shipWorld = level.shipWorld ?: return null
        var bestAngle = Double.POSITIVE_INFINITY
        var bestTarget: RocketTarget? = null

        VehicleManager.getVehicles(level).forEach { vehicle ->
            if (vehicle.bodyId == ownerBodyId) return@forEach
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: return@forEach
            val target = rocketTarget(vehicle, body.kinematics.position, body.kinematics.velocity)
            val distanceSq = target.position.distanceToSqr(pos)
            if (distanceSq > PRE_IGNITION_ASSIST_RANGE * PRE_IGNITION_ASSIST_RANGE) return@forEach
            val predictedTarget = predictedTargetPosition(target, pos, MAX_ROCKET_SPEED)
            val desired = predictedTarget.subtract(pos).normalize().takeIf { it.lengthSqr() > 1.0e-8 }
                ?: return@forEach
            val distance = sqrt(distanceSq).coerceAtLeast(1.0)
            val allowedAngle = PRE_IGNITION_ASSIST_MAX_ANGLE_RADIANS + atan(target.radius / distance)
            val angle = angleBetween(current, desired)
            if (angle > allowedAngle || angle >= bestAngle) return@forEach
            bestAngle = angle
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
        playExplosionSound(level, position)
        SkyridersMod.BAD_EXPLOSION_ENTITY.get().create(level)?.let { effect ->
            effect.moveTo(position.x, position.y + EXPLOSION_VISUAL_Y_OFFSET, position.z, random.nextFloat() * 360.0f, 0.0f)
            level.addFreshEntity(effect)
        }
        level.sendParticles(ParticleTypes.CLOUD, position.x, position.y, position.z, 18, 0.65, 0.45, 0.65, 0.08)

        val shipWorld = level.shipWorld
        val explosionPos = Vector3d(position.x, position.y, position.z)
        VehicleDamage.damageExplosion(
            level = level,
            origin = position,
            baseDamage = if (directTarget != null) 42.0 else 30.0,
            radius = BLAST_RADIUS,
            directTarget = directTarget,
            ignoredBodyId = ownerBodyId.takeIf { it != 0L }
        )
        VehicleManager.getVehicles(level).forEach { vehicle ->
            if (vehicle.bodyId == ownerBodyId) return@forEach
            val body = shipWorld?.allBodies?.getById(vehicle.bodyId) ?: return@forEach
            val radius = BLAST_RADIUS + vehicleApproxRadius(vehicle.vehicleDefinition.body.collisionBoxSize)
            if (body.kinematics.position.distanceSquared(explosionPos) > radius * radius) return@forEach
            val duration = if (vehicle.bodyId == directTarget?.bodyId) 2.25 else 1.45
            VehicleStatusEffects.applySpinOut(vehicle, duration = duration, yawSpeed = 6.0)
            VehicleStatusEffects.applyPullToPoint(
                vehicle = vehicle,
                target = explosionShoveTarget(body.kinematics.position, explosionPos),
                duration = EXPLOSION_SHOVE_DURATION,
                acceleration = EXPLOSION_SHOVE_ACCELERATION,
                maxSpeed = EXPLOSION_SHOVE_MAX_SPEED
            )
        }
        discard()
    }

    private fun playExplosionSound(level: ServerLevel, position: Vec3) {
        level.players().forEach { player ->
            val distance = player.position().distanceTo(position)
            if (distance > EXPLOSION_SOUND_RADIUS) return@forEach
            val falloff = (1.0 - distance / EXPLOSION_SOUND_RADIUS).coerceIn(0.0, 1.0)
            val volume = (0.25 + falloff * 0.75).toFloat()
            val randomPitch = 0.9f + level.random.nextFloat() * 0.1f // pitch between 0.9 and 1.0
            SkyridersNetwork.sendRocketExplosionSound(player, position, volume, randomPitch)
        }
    }

    private fun updateVelocity(fueled: Boolean) {
        deltaMovement = if (fueled) {
            val currentSpeed = deltaMovement.length().coerceAtLeast(INITIAL_THRUST_SPEED)
            val targetSpeed = (currentSpeed + ROCKET_ACCELERATION).coerceAtMost(MAX_ROCKET_SPEED)
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

    private fun spawnTrailParticles() {
        if (!ignited) return
        val velocity = deltaMovement
        val back = if (velocity.lengthSqr() > 1.0e-8) velocity.normalize().scale(-0.42) else Vec3.ZERO
        val px = x + back.x + (random.nextDouble() - 0.5) * 0.12
        val py = y + back.y + (random.nextDouble() - 0.5) * 0.12
        val pz = z + back.z + (random.nextDouble() - 0.5) * 0.12
        level().addParticle(ParticleTypes.SMOKE, px, py, pz, -velocity.x * 0.03, -velocity.y * 0.03, -velocity.z * 0.03)
        if (hasFuel) {
            level().addParticle(ParticleTypes.FLAME, px, py, pz, -velocity.x * 0.015, -velocity.y * 0.015, -velocity.z * 0.015)
        }
    }

    private fun nextPosition(velocity: Vec3): Vec3 {
        return position().add(velocity)
    }

    private fun savedFuelTicksRemaining(compound: CompoundTag, savedIgnited: Boolean, savedHasFuel: Boolean): Int {
        if (!savedIgnited || !savedHasFuel) return 0
        if (compound.contains(FUEL_TICKS_REMAINING_KEY)) {
            return compound.getInt(FUEL_TICKS_REMAINING_KEY).coerceAtLeast(0)
        }
        if (!compound.contains(DISTANCE_TRAVELLED_KEY)) return FUEL_TICKS

        val remainingFraction = 1.0 - (compound.getDouble(DISTANCE_TRAVELLED_KEY) / LEGACY_FUEL_DISTANCE)
            .coerceIn(0.0, 1.0)
        return (remainingFraction * FUEL_TICKS).toInt().coerceAtLeast(1)
    }

    private fun setPosition(position: Vec3) {
        setPos(position.x, position.y, position.z)
    }

    private fun vehicleApproxRadius(size: Vector3d): Double {
        return sqrt(size.x * size.x + size.z * size.z) * 0.5
    }

    private fun explosionShoveTarget(vehiclePosition: Vector3dc, explosionPosition: Vector3d): Vector3d {
        val away = Vector3d(vehiclePosition).sub(explosionPosition)
        away.y = 0.0
        if (away.lengthSquared() < 1.0e-6) {
            away.set(0.0, 0.0, 1.0)
        }
        return Vector3d(vehiclePosition)
            .add(away.normalize().mul(EXPLOSION_SHOVE_DISTANCE))
            .add(0.0, EXPLOSION_SHOVE_UP, 0.0)
    }

    private fun randomTossVelocity(): Vec3 {
        val angle = random.nextDouble() * Math.PI * 2.0
        val horizontalSpeed = PRE_IGNITION_MIN_HORIZONTAL_TOSS +
            random.nextDouble() * (PRE_IGNITION_MAX_HORIZONTAL_TOSS - PRE_IGNITION_MIN_HORIZONTAL_TOSS)
        val verticalSpeed = PRE_IGNITION_MIN_UPWARD_TOSS +
            random.nextDouble() * (PRE_IGNITION_MAX_UPWARD_TOSS - PRE_IGNITION_MIN_UPWARD_TOSS)
        return Vec3(
            kotlin.math.cos(angle) * horizontalSpeed,
            verticalSpeed,
            kotlin.math.sin(angle) * horizontalSpeed
        )
    }

    private fun rotationFromDirection(direction: Vec3): Pair<Float, Float> {
        val horizontal = sqrt(direction.x * direction.x + direction.z * direction.z)
        val yaw = -Math.toDegrees(kotlin.math.atan2(-direction.x, -direction.z)).toFloat()
        val pitch = Math.toDegrees(kotlin.math.atan2(direction.y, horizontal)).toFloat()
        return yaw to pitch
    }

    private fun applyMotorRotation() {
        val rotation = rotationFromDirection(motorDirection)
        yRotO = rotation.first
        xRotO = rotation.second
        yRot = rotation.first
        xRot = rotation.second
    }

    private fun rocketTarget(
        vehicle: IVehicle,
        position: Vector3dc,
        velocity: Vector3dc
    ): RocketTarget {
        return RocketTarget(
            position = Vec3(position.x(), position.y(), position.z()),
            velocity = Vec3(
                velocity.x() / TICKS_PER_SECOND,
                velocity.y() / TICKS_PER_SECOND,
                velocity.z() / TICKS_PER_SECOND
            ),
            radius = vehicleApproxRadius(vehicle.vehicleDefinition.body.collisionBoxSize)
        )
    }

    private fun predictedTargetPosition(target: RocketTarget, origin: Vec3, projectileSpeed: Double): Vec3 {
        val relativePosition = target.position.subtract(origin)
        val speed = projectileSpeed.coerceAtLeast(PREDICTION_MIN_PROJECTILE_SPEED)
        val a = target.velocity.lengthSqr() - speed * speed
        val b = 2.0 * relativePosition.dot(target.velocity)
        val c = relativePosition.lengthSqr()
        val interceptTicks = interceptTicks(a, b, c, speed)
            .coerceIn(0.0, PREDICTIVE_AIM_MAX_TICKS)
        return target.position.add(target.velocity.scale(interceptTicks))
    }

    private fun interceptTicks(a: Double, b: Double, c: Double, projectileSpeed: Double): Double {
        val fallback = sqrt(c) / projectileSpeed
        if (abs(a) < 1.0e-6) {
            if (abs(b) < 1.0e-6) return fallback
            return (-c / b).takeIf { it.isFinite() && it > 0.0 } ?: fallback
        }

        val discriminant = b * b - 4.0 * a * c
        if (discriminant < 0.0) return fallback
        val sqrtDiscriminant = sqrt(discriminant)
        val first = (-b - sqrtDiscriminant) / (2.0 * a)
        val second = (-b + sqrtDiscriminant) / (2.0 * a)
        return listOf(first, second)
            .filter { it.isFinite() && it > 0.0 }
            .minOrNull()
            ?: fallback
    }

    private fun angleBetween(current: Vec3, desired: Vec3): Double {
        return acos(current.dot(desired).coerceIn(-1.0, 1.0))
    }

    private fun rotateTowards(current: Vec3, desired: Vec3, maxRadians: Double): Vec3 {
        val angle = angleBetween(current, desired)
        if (angle <= maxRadians || angle < 1.0e-6) {
            return desired
        }

        val sinAngle = sin(angle)
        if (sinAngle < 1.0e-6) {
            return current
        }

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
        private const val HOMING_KEY = "Homing"
        private const val HAS_FUEL_KEY = "HasFuel"
        private const val IGNITED_KEY = "Ignited"
        private const val DISTANCE_TRAVELLED_KEY = "DistanceTravelled"
        private const val FUEL_TICKS_REMAINING_KEY = "FuelTicksRemaining"
        private const val MOTOR_DIRECTION_X_KEY = "MotorDirectionX"
        private const val MOTOR_DIRECTION_Y_KEY = "MotorDirectionY"
        private const val MOTOR_DIRECTION_Z_KEY = "MotorDirectionZ"
        private const val MAX_LIFETIME_TICKS = 20 * 14
        private const val FUEL_TICKS = 20 * 3
        private const val LEGACY_FUEL_DISTANCE = 30.0
        private const val TICKS_PER_SECOND = 20.0
        private const val ROCKET_ACCELERATION = 0.16
        private const val MAX_ROCKET_SPEED = 4.0
        private const val IGNITION_DELAY_TICKS = 9
        private const val PRE_IGNITION_GRAVITY = 0.045
        private const val PRE_IGNITION_DRAG = 0.985
        private const val PRE_IGNITION_INHERITED_SPEED_LIMIT = 3.0
        private const val PRE_IGNITION_MIN_HORIZONTAL_TOSS = 0.08
        private const val PRE_IGNITION_MAX_HORIZONTAL_TOSS = 0.24
        private const val PRE_IGNITION_MIN_UPWARD_TOSS = 0.26
        private const val PRE_IGNITION_MAX_UPWARD_TOSS = 0.42
        private const val IGNITION_VELOCITY_CARRY = 0.65
        private const val INITIAL_THRUST_SPEED = 0.65
        private const val VELOCITY_ALIGNMENT_RATE = 0.28
        private const val OUT_OF_FUEL_DRAG = 0.975
        private const val OUT_OF_FUEL_GRAVITY = 0.055
        private const val DIRECT_HIT_RADIUS = 0.35
        private const val BLAST_RADIUS = 4.75
        private const val EXPLOSION_SHOVE_DISTANCE = 12.0
        private const val EXPLOSION_SHOVE_UP = 1.8
        private const val EXPLOSION_SHOVE_DURATION = 0.42
        private const val EXPLOSION_SHOVE_ACCELERATION = 118.0
        private const val EXPLOSION_SHOVE_MAX_SPEED = 40.0
        private const val EXPLOSION_SOUND_RADIUS = 128.0
        private const val EXPLOSION_VISUAL_Y_OFFSET = 0.85
        private const val HOMING_RANGE = 28.0
        private const val HOMING_LATERAL_ACCELERATION = 0.18
        private const val HOMING_MIN_TURN_RADIANS = 0.035
        private const val HOMING_MAX_TURN_RADIANS = 0.2
        private const val PRE_IGNITION_ASSIST_RANGE = 24.0
        private val PRE_IGNITION_ASSIST_MAX_ANGLE_RADIANS = Math.toRadians(6.5)
        private val PRE_IGNITION_ASSIST_TURN_RADIANS = Math.toRadians(1.1)
        private const val PREDICTIVE_AIM_MAX_TICKS = 26.0
        private const val PREDICTION_MIN_PROJECTILE_SPEED = 0.25
        private const val RENDER_DISTANCE = 192.0
        private val HOMING: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(SugarRocketEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val HAS_FUEL: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(SugarRocketEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val IGNITED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(SugarRocketEntity::class.java, EntityDataSerializers.BOOLEAN)
    }

    private data class RocketTarget(
        val position: Vec3,
        val velocity: Vec3,
        val radius: Double
    )
}
