package org.valkyrienskies.skyriders.content.entity

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
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import kotlin.math.pow
import kotlin.math.sqrt

class ExtendingArmEntity(type: EntityType<ExtendingArmEntity>, level: Level) : Entity(type, level) {
    var ownerBodyId: BodyId
        get() = entityData.get(OWNER_BODY_ID)
        set(value) = entityData.set(OWNER_BODY_ID, value)

    var armKind: Int
        get() = entityData.get(ARM_KIND)
        set(value) = entityData.set(ARM_KIND, value.coerceIn(BOXING_GLOVE, GRABBY_HAND))

    val retracting: Boolean
        get() = entityData.get(RETRACTING)

    val attached: Boolean
        get() = entityData.get(ATTACHED)

    private var direction = Vec3(0.0, 0.0, 1.0)
    private var launchOrigin = Vec3.ZERO
    private var distanceTravelled = 0.0
    private var extendTicksElapsed = 0
    private var attachedTargetBodyId: BodyId? = null
    private var attachedTicksRemaining = 0
    private var retractTicksRemaining = 0

    init {
        blocksBuilding = false
        noPhysics = true
    }

    override fun tick() {
        super.tick()

        if (level().isClientSide) {
            setPos(x + deltaMovement.x, y + deltaMovement.y, z + deltaMovement.z)
            return
        }

        val serverLevel = level() as? ServerLevel ?: return
        if (attachedTargetBodyId != null) {
            tickAttached(serverLevel)
            return
        }
        if (retracting) {
            tickRetracting(serverLevel)
            return
        }
        if (tickCount > MAX_LIFETIME_TICKS) {
            startRetracting(serverLevel)
            return
        }

        val start = position()
        val next = nextEasedExtensionPosition()
        val velocity = next.subtract(start)
        if (velocity.lengthSqr() < 1.0e-8 && extendTicksElapsed > EXTEND_TICKS) {
            startRetracting(serverLevel)
            return
        }

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
            setPosition(blockHit.location)
            onTerrainHit(serverLevel, blockHit.location)
            return
        }

        setPosition(next)
        distanceTravelled += velocity.length()
        deltaMovement = velocity
        extendTicksElapsed++
        findHitVehicle(serverLevel, next)?.let { vehicle ->
            onVehicleHit(serverLevel, vehicle)
        }
        if (!retracting && attachedTargetBodyId == null && extendTicksElapsed > EXTEND_TICKS) {
            startRetracting(serverLevel)
        }
    }

    fun launch(origin: Vec3, direction: Vec3, inheritedVelocity: Vec3, ownerBodyId: BodyId, kind: Int) {
        val safeDirection = direction.normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: Vec3(0.0, 0.0, 1.0)
        this.ownerBodyId = ownerBodyId
        this.armKind = kind
        this.direction = safeDirection
        this.launchOrigin = origin
        this.extendTicksElapsed = 0
        this.distanceTravelled = 0.0
        val rotation = rotationFromDirection(safeDirection)
        moveTo(origin.x, origin.y, origin.z, rotation.first, rotation.second)
        deltaMovement = safeDirection.scale(INITIAL_CLIENT_EXTEND_SPEED).add(inheritedVelocity.limitLength(INHERITED_SPEED_LIMIT))
        entityData.set(RETRACTING, false)
        entityData.set(ATTACHED, false)
    }

    override fun defineSynchedData() {
        entityData.define(OWNER_BODY_ID, 0L)
        entityData.define(ARM_KIND, BOXING_GLOVE)
        entityData.define(RETRACTING, false)
        entityData.define(ATTACHED, false)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        ownerBodyId = compound.getLong(OWNER_BODY_ID_KEY)
        armKind = compound.getInt(ARM_KIND_KEY)
        distanceTravelled = compound.getDouble(DISTANCE_TRAVELLED_KEY)
        extendTicksElapsed = compound.getInt(EXTEND_TICKS_ELAPSED_KEY)
        entityData.set(RETRACTING, compound.getBoolean(RETRACTING_KEY))
        entityData.set(ATTACHED, compound.getBoolean(ATTACHED_KEY))
        attachedTargetBodyId = if (compound.contains(ATTACHED_TARGET_BODY_ID_KEY)) compound.getLong(ATTACHED_TARGET_BODY_ID_KEY) else null
        attachedTicksRemaining = compound.getInt(ATTACHED_TICKS_REMAINING_KEY)
        retractTicksRemaining = compound.getInt(RETRACT_TICKS_REMAINING_KEY)
        direction = Vec3(
            compound.getDouble(DIRECTION_X_KEY),
            compound.getDouble(DIRECTION_Y_KEY),
            compound.getDouble(DIRECTION_Z_KEY)
        ).normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: Vec3(0.0, 0.0, 1.0)
        launchOrigin = Vec3(
            compound.getDouble(LAUNCH_ORIGIN_X_KEY),
            compound.getDouble(LAUNCH_ORIGIN_Y_KEY),
            compound.getDouble(LAUNCH_ORIGIN_Z_KEY)
        )
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putLong(OWNER_BODY_ID_KEY, ownerBodyId)
        compound.putInt(ARM_KIND_KEY, armKind)
        compound.putDouble(DISTANCE_TRAVELLED_KEY, distanceTravelled)
        compound.putInt(EXTEND_TICKS_ELAPSED_KEY, extendTicksElapsed)
        compound.putBoolean(RETRACTING_KEY, retracting)
        compound.putBoolean(ATTACHED_KEY, attached)
        attachedTargetBodyId?.let { compound.putLong(ATTACHED_TARGET_BODY_ID_KEY, it) }
        compound.putInt(ATTACHED_TICKS_REMAINING_KEY, attachedTicksRemaining)
        compound.putInt(RETRACT_TICKS_REMAINING_KEY, retractTicksRemaining)
        compound.putDouble(DIRECTION_X_KEY, direction.x)
        compound.putDouble(DIRECTION_Y_KEY, direction.y)
        compound.putDouble(DIRECTION_Z_KEY, direction.z)
        compound.putDouble(LAUNCH_ORIGIN_X_KEY, launchOrigin.x)
        compound.putDouble(LAUNCH_ORIGIN_Y_KEY, launchOrigin.y)
        compound.putDouble(LAUNCH_ORIGIN_Z_KEY, launchOrigin.z)
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> = ClientboundAddEntityPacket(this)

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean {
        return distance < RENDER_DISTANCE * RENDER_DISTANCE
    }

    private fun nextEasedExtensionPosition(): Vec3 {
        val maxTravel = if (armKind == GRABBY_HAND) GRABBY_NO_HIT_RANGE else BOXING_RANGE
        val nextTick = (extendTicksElapsed + 1).coerceAtMost(EXTEND_TICKS)
        val progress = (nextTick.toDouble() / EXTEND_TICKS.toDouble()).coerceIn(0.0, 1.0)
        val eased = easeOutBack(progress).coerceIn(0.0, 1.0)
        return launchOrigin.add(direction.scale(maxTravel * eased))
    }

    private fun onVehicleHit(level: ServerLevel, target: IVehicle) {
        if (armKind == BOXING_GLOVE) {
            smackVehicle(level, target)
            level.playSound(null, x, y, z, SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, 0.55f, 1.65f)
            startRetracting(level)
        } else {
            attachToVehicle(level, target.bodyId)
            level.playSound(null, x, y, z, SoundEvents.LEASH_KNOT_PLACE, SoundSource.NEUTRAL, 0.85f, 1.2f)
        }
    }

    private fun onTerrainHit(level: ServerLevel, hitPosition: Vec3) {
        val owner = VehicleManager.getVehicle(level, ownerBodyId) ?: return startRetracting(level)
        if (armKind == BOXING_GLOVE) {
            val ownerBody = level.shipWorld?.allBodies?.getById(ownerBodyId) ?: return startRetracting(level)
            val target = Vector3d(ownerBody.kinematics.position)
                .sub(direction.toJoml().mul(BOXING_RECOIL_DISTANCE))
                .add(0.0, BOXING_RECOIL_UP, 0.0)
            VehicleStatusEffects.applyPullToPoint(
                vehicle = owner,
                target = target,
                duration = BOXING_RECOIL_DURATION,
                acceleration = BOXING_RECOIL_ACCELERATION,
                maxSpeed = BOXING_RECOIL_MAX_SPEED
            )
            level.playSound(null, x, y, z, SoundEvents.SLIME_BLOCK_HIT, SoundSource.NEUTRAL, 0.9f, 0.8f)
        } else {
            VehicleStatusEffects.applyPullToPoint(
                vehicle = owner,
                target = Vector3d(hitPosition.x, hitPosition.y, hitPosition.z),
                duration = GRABBY_TERRAIN_PULL_DURATION,
                acceleration = GRABBY_TERRAIN_PULL_ACCELERATION,
                maxSpeed = GRABBY_TERRAIN_PULL_MAX_SPEED
            )
            level.playSound(null, x, y, z, SoundEvents.LEASH_KNOT_PLACE, SoundSource.NEUTRAL, 0.85f, 1.0f)
        }
        startRetracting(level)
    }

    private fun smackVehicle(level: ServerLevel, target: IVehicle) {
        val shipWorld = level.shipWorld ?: return
        val targetBody = shipWorld.allBodies.getById(target.bodyId) ?: return
        val ownerBody = shipWorld.allBodies.getById(ownerBodyId)
        val away = if (ownerBody != null) {
            Vector3d(targetBody.kinematics.position).sub(ownerBody.kinematics.position)
        } else {
            direction.toJoml()
        }
        if (away.lengthSquared() < 1.0e-8) {
            away.set(direction.x, direction.y, direction.z)
        }
        away.y = 0.0
        if (away.lengthSquared() < 1.0e-8) {
            away.set(direction.x, 0.0, direction.z)
        }
        val smackDirection = away.normalize().add(0.0, BOXING_SMACK_UP_BIAS, 0.0).normalize()
        val targetPoint = Vector3d(targetBody.kinematics.position).add(smackDirection.mul(BOXING_SMACK_DISTANCE))
        VehicleStatusEffects.applyPullToPoint(
            vehicle = target,
            target = targetPoint,
            duration = BOXING_SMACK_DURATION,
            acceleration = BOXING_SMACK_ACCELERATION,
            maxSpeed = BOXING_SMACK_MAX_SPEED
        )
        VehicleStatusEffects.applySpinOut(target, duration = 1.65, yawSpeed = 5.9)
    }

    private fun attachToVehicle(level: ServerLevel, targetBodyId: BodyId) {
        attachedTargetBodyId = targetBodyId
        attachedTicksRemaining = GRABBY_ATTACH_TICKS
        entityData.set(ATTACHED, true)
        tickAttached(level)
    }

    private fun tickAttached(level: ServerLevel) {
        val targetBodyId = attachedTargetBodyId ?: return startRetracting(level)
        val shipWorld = level.shipWorld ?: return startRetracting(level)
        val owner = VehicleManager.getVehicle(level, ownerBodyId) ?: return startRetracting(level)
        val target = VehicleManager.getVehicle(level, targetBodyId) ?: return startRetracting(level)
        val ownerBody = shipWorld.allBodies.getById(ownerBodyId) ?: return startRetracting(level)
        val targetBody = shipWorld.allBodies.getById(targetBodyId) ?: return startRetracting(level)
        val targetPosition = targetBody.kinematics.position
        val ownerPosition = ownerBody.kinematics.position
        if (ownerPosition.distanceSquared(targetPosition) > GRABBY_TETHER_RANGE * GRABBY_TETHER_RANGE) {
            startRetracting(level)
            return
        }

        setPosition(Vec3(targetPosition.x(), targetPosition.y() + ATTACHED_Y_OFFSET, targetPosition.z()))
        deltaMovement = Vec3(targetBody.kinematics.velocity.x(), targetBody.kinematics.velocity.y(), targetBody.kinematics.velocity.z())
        val targetSpeed = targetBody.kinematics.velocity.length().coerceAtLeast(GRABBY_MIN_TARGET_SPEED)
        VehicleStatusEffects.applyPullToPoint(
            vehicle = owner,
            target = Vector3d(targetPosition),
            duration = GRABBY_FOLLOW_PULL_DURATION,
            acceleration = GRABBY_FOLLOW_ACCELERATION,
            maxSpeed = targetSpeed + GRABBY_FOLLOW_SPEED_MARGIN
        )
        if (attachedTicksRemaining == GRABBY_ATTACH_TICKS) {
            VehicleStatusEffects.applySpinOut(target, duration = 0.65, yawSpeed = 2.2)
        }

        attachedTicksRemaining--
        if (attachedTicksRemaining <= 0) {
            startRetracting(level)
        }
    }

    private fun tickRetracting(level: ServerLevel) {
        val ownerPosition = ownerPosition(level) ?: return discard()
        val current = position()
        val toOwner = Vec3(ownerPosition.x - current.x, ownerPosition.y + OWNER_TETHER_Y_OFFSET - current.y, ownerPosition.z - current.z)
        if (toOwner.lengthSqr() < RETRACT_DONE_DISTANCE * RETRACT_DONE_DISTANCE || retractTicksRemaining <= 0) {
            discard()
            return
        }
        val velocity = toOwner.normalize().scale(RETRACT_SPEED)
        deltaMovement = velocity
        setPosition(current.add(velocity))
        retractTicksRemaining--
    }

    private fun startRetracting(level: ServerLevel) {
        attachedTargetBodyId = null
        entityData.set(ATTACHED, false)
        if (!retracting) {
            entityData.set(RETRACTING, true)
            retractTicksRemaining = RETRACT_TICKS
            level.playSound(null, x, y, z, SoundEvents.LEASH_KNOT_BREAK, SoundSource.NEUTRAL, 0.55f, 1.25f)
        }
        val ownerPosition = ownerPosition(level) ?: return discard()
        val current = position()
        val toOwner = Vec3(ownerPosition.x - current.x, ownerPosition.y + OWNER_TETHER_Y_OFFSET - current.y, ownerPosition.z - current.z)
        deltaMovement = if (toOwner.lengthSqr() > 1.0e-8) toOwner.normalize().scale(RETRACT_SPEED) else Vec3.ZERO
    }

    private fun findHitVehicle(level: ServerLevel, position: Vec3): IVehicle? {
        val shipWorld = level.shipWorld ?: return null
        val projectilePos = Vector3d(position.x, position.y, position.z)
        return VehicleManager.getVehicles(level).firstOrNull { vehicle ->
            if (vehicle.bodyId == ownerBodyId) return@firstOrNull false
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: return@firstOrNull false
            val hitRadius = VEHICLE_HIT_RADIUS + vehicleApproxRadius(vehicle.vehicleDefinition.body.collisionBoxSize)
            body.kinematics.position.distanceSquared(projectilePos) <= hitRadius * hitRadius
        }
    }

    private fun ownerPosition(level: ServerLevel): Vector3d? {
        val ownerBody = level.shipWorld?.allBodies?.getById(ownerBodyId) ?: return null
        return Vector3d(ownerBody.kinematics.position)
    }

    private fun setPosition(position: Vec3) {
        setPos(position.x, position.y, position.z)
    }

    private fun vehicleApproxRadius(size: Vector3d): Double {
        return sqrt(size.x * size.x + size.z * size.z) * 0.5
    }

    private fun rotationFromDirection(direction: Vec3): Pair<Float, Float> {
        val horizontal = sqrt(direction.x * direction.x + direction.z * direction.z)
        val yaw = -Math.toDegrees(kotlin.math.atan2(-direction.x, -direction.z)).toFloat()
        val pitch = Math.toDegrees(kotlin.math.atan2(direction.y, horizontal)).toFloat()
        return yaw to pitch
    }

    private fun Vec3.limitLength(maxLength: Double): Vec3 {
        val length = length()
        if (length <= maxLength || length < 1.0e-8) return this
        return scale(maxLength / length)
    }

    private fun Vec3.toJoml(): Vector3d = Vector3d(x, y, z)

    private fun easeOutBack(t: Double): Double {
        val c1 = EXTEND_BACK_OVERSHOOT
        val c3 = c1 + 1.0
        val x = t - 1.0
        return 1.0 + c3 * x.pow(3.0) + c1 * x.pow(2.0)
    }

    companion object {
        const val BOXING_GLOVE = 0
        const val GRABBY_HAND = 1

        private const val OWNER_BODY_ID_KEY = "OwnerBodyId"
        private const val ARM_KIND_KEY = "ArmKind"
        private const val RETRACTING_KEY = "Retracting"
        private const val ATTACHED_KEY = "Attached"
        private const val ATTACHED_TARGET_BODY_ID_KEY = "AttachedTargetBodyId"
        private const val ATTACHED_TICKS_REMAINING_KEY = "AttachedTicksRemaining"
        private const val RETRACT_TICKS_REMAINING_KEY = "RetractTicksRemaining"
        private const val DISTANCE_TRAVELLED_KEY = "DistanceTravelled"
        private const val EXTEND_TICKS_ELAPSED_KEY = "ExtendTicksElapsed"
        private const val DIRECTION_X_KEY = "DirectionX"
        private const val DIRECTION_Y_KEY = "DirectionY"
        private const val DIRECTION_Z_KEY = "DirectionZ"
        private const val LAUNCH_ORIGIN_X_KEY = "LaunchOriginX"
        private const val LAUNCH_ORIGIN_Y_KEY = "LaunchOriginY"
        private const val LAUNCH_ORIGIN_Z_KEY = "LaunchOriginZ"

        private const val MAX_LIFETIME_TICKS = 20 * 6
        private const val INITIAL_CLIENT_EXTEND_SPEED = 1.8
        private const val EXTEND_TICKS = 8
        private const val EXTEND_BACK_OVERSHOOT = 1.65
        private const val RETRACT_SPEED = 1.55
        private const val RETRACT_TICKS = 18
        private const val RETRACT_DONE_DISTANCE = 0.85
        private const val INHERITED_SPEED_LIMIT = 0.65
        private const val OWNER_TETHER_Y_OFFSET = 0.5
        private const val VEHICLE_HIT_RADIUS = 0.4
        private const val BOXING_RANGE = 15.0
        private const val GRABBY_NO_HIT_RANGE = 15.0
        private const val GRABBY_TETHER_RANGE = 20.0
        private const val ATTACHED_Y_OFFSET = 0.55

        private const val BOXING_SMACK_DISTANCE = 10.0
        private const val BOXING_SMACK_UP_BIAS = 0.16
        private const val BOXING_SMACK_DURATION = 0.32
        private const val BOXING_SMACK_ACCELERATION = 95.0
        private const val BOXING_SMACK_MAX_SPEED = 32.0
        private const val BOXING_RECOIL_DISTANCE = 8.0
        private const val BOXING_RECOIL_UP = 1.5
        private const val BOXING_RECOIL_DURATION = 0.38
        private const val BOXING_RECOIL_ACCELERATION = 88.0
        private const val BOXING_RECOIL_MAX_SPEED = 30.0

        private const val GRABBY_TERRAIN_PULL_DURATION = 0.72
        private const val GRABBY_TERRAIN_PULL_ACCELERATION = 74.0
        private const val GRABBY_TERRAIN_PULL_MAX_SPEED = 28.0
        private const val GRABBY_ATTACH_TICKS = 20 * 3
        private const val GRABBY_FOLLOW_PULL_DURATION = 0.18
        private const val GRABBY_FOLLOW_ACCELERATION = 58.0
        private const val GRABBY_FOLLOW_SPEED_MARGIN = 4.0
        private const val GRABBY_MIN_TARGET_SPEED = 8.0
        private const val RENDER_DISTANCE = 192.0

        private val OWNER_BODY_ID: EntityDataAccessor<Long> =
            SynchedEntityData.defineId(ExtendingArmEntity::class.java, EntityDataSerializers.LONG)
        private val ARM_KIND: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(ExtendingArmEntity::class.java, EntityDataSerializers.INT)
        private val RETRACTING: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(ExtendingArmEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val ATTACHED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(ExtendingArmEntity::class.java, EntityDataSerializers.BOOLEAN)
    }
}
