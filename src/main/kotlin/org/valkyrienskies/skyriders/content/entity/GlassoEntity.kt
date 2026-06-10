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
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import java.util.UUID
import kotlin.math.sqrt

class GlassoEntity(type: EntityType<GlassoEntity>, level: Level) : Entity(type, level) {
    var ownerBodyId: BodyId
        get() = entityData.get(OWNER_BODY_ID)
        set(value) = entityData.set(OWNER_BODY_ID, value)

    private var ownerPlayerId: UUID? = null
    private var distanceTravelled = 0.0

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
        if (tickCount > MAX_LIFETIME_TICKS || distanceTravelled > MAX_RANGE) {
            shatter(serverLevel)
            return
        }

        val start = position()
        val velocity = deltaMovement
        if (velocity.lengthSqr() < 1.0e-8) {
            shatter(serverLevel)
            return
        }
        val next = start.add(velocity)

        findHitEntity(serverLevel, start, next)?.let { target ->
            yankEntity(serverLevel, target)
            shatter(serverLevel)
            return
        }

        findHitVehicle(serverLevel, next)?.let { targetBodyId ->
            yankVehicle(serverLevel, targetBodyId)
            shatter(serverLevel)
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
            setPos(blockHit.location.x, blockHit.location.y, blockHit.location.z)
            yankOwnerVehicle(serverLevel, blockHit.location)
            shatter(serverLevel)
            return
        }

        setPos(next.x, next.y, next.z)
        distanceTravelled += velocity.length()
        deltaMovement = Vec3(velocity.x * AIR_DRAG, velocity.y * AIR_DRAG - GRAVITY, velocity.z * AIR_DRAG)
    }

    fun launch(origin: Vec3, direction: Vec3, inheritedVelocity: Vec3, ownerBodyId: BodyId, ownerPlayerId: UUID?) {
        val safeDirection = direction.normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: Vec3(0.0, 0.0, 1.0)
        this.ownerBodyId = ownerBodyId
        this.ownerPlayerId = ownerPlayerId
        moveTo(origin.x, origin.y, origin.z, yawFromDirection(safeDirection), pitchFromDirection(safeDirection))
        deltaMovement = safeDirection.scale(PROJECTILE_SPEED).add(inheritedVelocity.scale(INHERITED_VELOCITY_SCALE))
    }

    override fun defineSynchedData() {
        entityData.define(OWNER_BODY_ID, 0L)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        ownerBodyId = compound.getLong(OWNER_BODY_ID_KEY)
        if (compound.hasUUID(OWNER_PLAYER_ID_KEY)) {
            ownerPlayerId = compound.getUUID(OWNER_PLAYER_ID_KEY)
        }
        distanceTravelled = compound.getDouble(DISTANCE_TRAVELLED_KEY)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putLong(OWNER_BODY_ID_KEY, ownerBodyId)
        ownerPlayerId?.let { compound.putUUID(OWNER_PLAYER_ID_KEY, it) }
        compound.putDouble(DISTANCE_TRAVELLED_KEY, distanceTravelled)
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> = ClientboundAddEntityPacket(this)

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean {
        return distance < RENDER_DISTANCE * RENDER_DISTANCE
    }

    private fun findHitEntity(level: ServerLevel, start: Vec3, end: Vec3): LivingEntity? {
        val searchBox = AABB(start, end).inflate(ENTITY_HIT_RADIUS)
        return level.getEntitiesOfClass(LivingEntity::class.java, searchBox) { entity ->
            entity.isAlive && entity.uuid != ownerPlayerId && entity.vehicle !is BikeSeatEntity
        }.minByOrNull { it.distanceToSqr(start) }?.takeIf { entity ->
            entity.boundingBox.inflate(ENTITY_HIT_RADIUS).clip(start, end).isPresent
        }
    }

    private fun findHitVehicle(level: ServerLevel, position: Vec3): BodyId? {
        val shipWorld = level.shipWorld ?: return null
        val hookPos = Vector3d(position.x, position.y, position.z)
        return VehicleManager.getVehicles(level).firstOrNull { vehicle ->
            if (vehicle.bodyId == ownerBodyId) return@firstOrNull false
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: return@firstOrNull false
            val hitRadius = VEHICLE_HIT_RADIUS + vehicleApproxRadius(vehicle.vehicleDefinition.body.collisionBoxSize)
            body.kinematics.position.distanceSquared(hookPos) <= hitRadius * hitRadius
        }?.bodyId
    }

    private fun yankOwnerVehicle(level: ServerLevel, hitPosition: Vec3) {
        val vehicle = VehicleManager.getVehicle(level, ownerBodyId) ?: return
        VehicleStatusEffects.applyPullToPoint(
            vehicle = vehicle,
            target = Vector3d(hitPosition.x, hitPosition.y, hitPosition.z),
            duration = TERRAIN_PULL_DURATION,
            acceleration = TERRAIN_PULL_ACCELERATION,
            maxSpeed = PULL_MAX_SPEED
        )
    }

    private fun yankVehicle(level: ServerLevel, targetBodyId: BodyId) {
        val shipWorld = level.shipWorld ?: return
        val ownerBody = shipWorld.allBodies.getById(ownerBodyId) ?: return
        val target = VehicleManager.getVehicle(level, targetBodyId) ?: return
        VehicleStatusEffects.applyPullToPoint(
            vehicle = target,
            target = Vector3d(ownerBody.kinematics.position),
            duration = VEHICLE_PULL_DURATION,
            acceleration = VEHICLE_PULL_ACCELERATION,
            maxSpeed = PULL_MAX_SPEED
        )
        VehicleStatusEffects.applySpinOut(target, duration = 1.55, yawSpeed = 4.8)
    }

    private fun yankEntity(level: ServerLevel, entity: LivingEntity) {
        val shipWorld = level.shipWorld
        val source = shipWorld?.allBodies?.getById(ownerBodyId)?.kinematics?.position
            ?: Vector3d(x, y, z)
        val target = Vec3(source.x(), source.y() + 0.55, source.z())
        val direction = target.subtract(entity.position()).normalize().takeIf { it.lengthSqr() > 1.0e-8 } ?: return
        entity.deltaMovement = entity.deltaMovement.add(direction.scale(ENTITY_YANK_SPEED)).add(0.0, ENTITY_YANK_UPWARD, 0.0)
        entity.hasImpulse = true
    }

    private fun shatter(level: ServerLevel) {
        level.playSound(null, x, y, z, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.85f, 1.25f)
        discard()
    }

    private fun vehicleApproxRadius(size: Vector3d): Double {
        return sqrt(size.x * size.x + size.z * size.z) * 0.5
    }

    private fun yawFromDirection(direction: Vec3): Float {
        return -Math.toDegrees(kotlin.math.atan2(-direction.x, -direction.z)).toFloat()
    }

    private fun pitchFromDirection(direction: Vec3): Float {
        val horizontal = sqrt(direction.x * direction.x + direction.z * direction.z)
        return Math.toDegrees(kotlin.math.atan2(direction.y, horizontal)).toFloat()
    }

    private companion object {
        private const val OWNER_BODY_ID_KEY = "OwnerBodyId"
        private const val OWNER_PLAYER_ID_KEY = "OwnerPlayerId"
        private const val DISTANCE_TRAVELLED_KEY = "DistanceTravelled"
        private const val MAX_LIFETIME_TICKS = 40
        private const val MAX_RANGE = 20.0
        private const val PROJECTILE_SPEED = 1.55
        private const val INHERITED_VELOCITY_SCALE = 0.05
        private const val AIR_DRAG = 0.992
        private const val GRAVITY = 0.015
        private const val ENTITY_HIT_RADIUS = 0.35
        private const val VEHICLE_HIT_RADIUS = 0.25
        private const val TERRAIN_PULL_DURATION = 0.7
        private const val TERRAIN_PULL_ACCELERATION = 54.0
        private const val VEHICLE_PULL_DURATION = 0.75
        private const val VEHICLE_PULL_ACCELERATION = 44.0
        private const val PULL_MAX_SPEED = 26.0
        private const val ENTITY_YANK_SPEED = 1.45
        private const val ENTITY_YANK_UPWARD = 0.28
        private const val RENDER_DISTANCE = 128.0
        private val OWNER_BODY_ID: EntityDataAccessor<Long> =
            SynchedEntityData.defineId(GlassoEntity::class.java, EntityDataSerializers.LONG)
    }
}
