package org.valkyrienskies.skyriders.content.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import kotlin.math.sqrt

class CavendishEntity(type: EntityType<CavendishEntity>, level: Level) : Entity(type, level) {
    var ownerBodyId: BodyId = 0L
    private var settled: Boolean
        get() = entityData.get(SETTLED)
        set(value) = entityData.set(SETTLED, value)

    init {
        blocksBuilding = false
        noPhysics = true
        noCulling = true
    }

    override fun tick() {
        super.tick()
        if (!settled) {
            tickTossMotion()
        }
        if (level().isClientSide) return

        if (tickCount > DESPAWN_TICKS) {
            discard()
            return
        }
        if (!settled || tickCount < ARMING_TICKS) return

        val serverLevel = level() as? ServerLevel ?: return
        val shipWorld = serverLevel.shipWorld ?: return
        val peelPos = Vector3d(x, y, z)
        for (vehicle in VehicleManager.getVehicles(serverLevel)) {
            if (vehicle.bodyId == ownerBodyId && tickCount < OWNER_IGNORE_TICKS) continue
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: continue
            val hitRadius = TRIGGER_RADIUS + vehicleApproxRadius(vehicle.vehicleDefinition.body.collisionBoxSize)
            if (body.kinematics.position.distanceSquared(peelPos) > hitRadius * hitRadius) continue

            VehicleStatusEffects.applySpinOut(vehicle, duration = 1.85, yawSpeed = 5.75)
            discard()
            return
        }
    }

    override fun defineSynchedData() {
        entityData.define(SETTLED, false)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        ownerBodyId = compound.getLong(OWNER_BODY_ID_KEY)
        settled = compound.getBoolean(SETTLED_KEY)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putLong(OWNER_BODY_ID_KEY, ownerBodyId)
        compound.putBoolean(SETTLED_KEY, settled)
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> {
        return ClientboundAddEntityPacket(this)
    }

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean {
        return distance < RENDER_DISTANCE * RENDER_DISTANCE
    }

    fun toss(origin: Vec3, velocity: Vec3, yaw: Float) {
        settled = false
        moveTo(origin.x, origin.y, origin.z, yaw, 0.0f)
        deltaMovement = velocity
    }

    private fun tickTossMotion() {
        val start = position()
        val velocity = deltaMovement
        val next = start.add(velocity)
        val hit = level().clip(
            ClipContext(
                start,
                next,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this
            )
        )
        if (hit.type != HitResult.Type.MISS) {
            setPos(hit.location.x, hit.location.y + FLOOR_SETTLE_OFFSET, hit.location.z)
            deltaMovement = Vec3.ZERO
            settled = true
            return
        }

        setPos(next.x, next.y, next.z)
        deltaMovement = Vec3(
            velocity.x * AIR_DRAG,
            velocity.y * AIR_DRAG - GRAVITY,
            velocity.z * AIR_DRAG
        )
    }

    private fun vehicleApproxRadius(size: Vector3d): Double {
        return sqrt(size.x * size.x + size.z * size.z) * 0.5
    }

    private companion object {
        const val OWNER_BODY_ID_KEY = "OwnerBodyId"
        const val SETTLED_KEY = "Settled"
        const val ARMING_TICKS = 8
        const val OWNER_IGNORE_TICKS = 45
        const val DESPAWN_TICKS = 20 * 60 * 3
        const val TRIGGER_RADIUS = 0.45
        const val GRAVITY = 0.055
        const val AIR_DRAG = 0.985
        const val FLOOR_SETTLE_OFFSET = 0.015
        const val RENDER_DISTANCE = 192.0
        val SETTLED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(CavendishEntity::class.java, EntityDataSerializers.BOOLEAN)
    }
}
