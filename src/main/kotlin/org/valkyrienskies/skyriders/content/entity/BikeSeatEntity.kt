package org.valkyrienskies.skyriders.content.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Entity.RemovalReason
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.ClientVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.skyriders.content.VehicleInteractionDefinition
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleSeatDefinition
import org.valkyrienskies.skyriders.content.VehicleSeatRole
import org.valkyrienskies.skyriders.content.VehicleImpairmentEffects
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import kotlin.math.atan2

class BikeSeatEntity(type: EntityType<BikeSeatEntity>, level: Level) : Entity(type, level) {
    var bodyId: BodyId
        get() = entityData.get(BODY_ID)
        set(value) = entityData.set(BODY_ID, value)
    var seatId: String
        get() = entityData.get(SEAT_ID)
        set(value) = entityData.set(SEAT_ID, value)
    private var lastBikeYaw: Float? = null
    private var cleanedUp = false

    init {
        blocksBuilding = false
        noPhysics = true
        noCulling = true
    }

    override fun tick() {
        super.tick()
        if (!level().isClientSide && passengers.isEmpty()) {
            removeSeat(RemovalReason.DISCARDED)
            return
        }

        val previousYaw = yRot
        if (!updateSeatPose()) {
            if (!level().isClientSide) {
                removeSeat(RemovalReason.DISCARDED)
            }
            return
        }
        rotatePassengersByBikeYawDelta(previousYaw)
        updateBikeInputFromPassenger()
        sendBikeDebugToPassenger()
    }

    override fun getControllingPassenger(): LivingEntity? {
        return passengers.getOrNull(0) as? LivingEntity
    }

    override fun getDismountLocationForPassenger(livingEntity: LivingEntity): Vec3 {
        return position().add(0.0, 0.2, 0.0)
    }

    fun removeIfEmpty() {
        if (!level().isClientSide && passengers.isEmpty()) {
            removeSeat(RemovalReason.DISCARDED)
        }
    }

    override fun remove(reason: RemovalReason) {
        cleanupSeat()
        super.remove(reason)
    }

    override fun defineSynchedData() {
        entityData.define(BODY_ID, 0L)
        entityData.define(SEAT_ID, DEFAULT_SEAT_ID)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        bodyId = compound.getLong(BODY_ID_TAG)
        seatId = compound.getString(SEAT_ID_TAG).takeIf(String::isNotBlank) ?: DEFAULT_SEAT_ID
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putLong(BODY_ID_TAG, bodyId)
        compound.putString(SEAT_ID_TAG, seatId)
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> {
        return ClientboundAddEntityPacket(this)
    }

    private fun updateSeatPose(): Boolean {
        val body = level().shipWorld?.allBodies?.getById(bodyId) ?: return false
        val localPos = seatDefinition()?.localPos ?: Vector3d(0.0, DEFAULT_SEAT_OFFSET, 0.0)
        val transform = if (level().isClientSide && body is ClientVsBody) body.renderTransform else body.kinematics.transform
        val seatWorld = transform.toWorld.transformPosition(Vector3d(localPos))
        val forward = transform.rotation.transform(Vector3d(0.0, 0.0, 1.0))
        if (!isFinite(seatWorld) || !isFinite(forward)) return false

        moveTo(seatWorld.x, seatWorld.y, seatWorld.z, yawFromForward(forward), xRot)
        return true
    }

    private fun removeSeat(reason: RemovalReason) {
        cleanupSeat()
        remove(reason)
    }

    private fun cleanupSeat() {
        if (cleanedUp) return
        cleanedUp = true
        if (!level().isClientSide) {
            VehicleManager.clearInput(level().dimensionId, bodyId)
            (level() as? ServerLevel)?.let { VehicleImpairmentEffects.clear(it, bodyId) }
        }
        passengers.toList().forEach { passenger ->
            if (passenger.vehicle == this) {
                passenger.stopRiding()
            }
        }
    }

    private fun rotatePassengersByBikeYawDelta(fallbackPreviousYaw: Float) {
        val previousYaw = lastBikeYaw ?: fallbackPreviousYaw
        val deltaYaw = Mth.wrapDegrees(yRot - previousYaw)
        lastBikeYaw = yRot
        if (!deltaYaw.isFinite() || kotlin.math.abs(deltaYaw) < 1.0e-4f) return

        passengers.forEach { passenger ->
            passenger.yRot += deltaYaw
            passenger.yRotO += deltaYaw
            passenger.yHeadRot += deltaYaw
            if (passenger is LivingEntity) {
                passenger.yBodyRot += deltaYaw
                passenger.yBodyRotO += deltaYaw
            }
        }
    }

    private fun updateBikeInputFromPassenger() {
        if (level().isClientSide) return
        if (!isDriverSeat()) return

        val passenger = controllingPassenger ?: return
        val serverLevel = level() as? ServerLevel
        if (serverLevel != null) {
            VehicleImpairmentEffects.tickDriverInput(serverLevel, bodyId, passenger)
        } else {
            VehicleManager.updateInput(level().dimensionId, bodyId) {
                it.copy(riderPresent = true)
            }
        }
    }

    private fun sendBikeDebugToPassenger() {
        if (level().isClientSide) return
        if (!isDriverSeat()) return

        val player = controllingPassenger as? ServerPlayer ?: return
        val vehicle = VehicleManager.getVehicle(level().dimensionId, bodyId) ?: return
        SkyridersNetwork.sendVehicleDebug(player, vehicle)
    }

    fun seatDefinition(): VehicleSeatDefinition? {
        return VehicleManager.getVehicle(level(), bodyId)
            ?.vehicleDefinition
            ?.seats
            ?.firstOrNull { it.id == seatId }
    }

    fun isDriverSeat(): Boolean {
        return seatDefinition()?.role != VehicleSeatRole.PASSENGER
    }

    private fun yawFromForward(forward: Vector3dc): Float {
        return Math.toDegrees(atan2(-forward.x(), forward.z())).toFloat()
    }

    private fun isFinite(vector: Vector3dc): Boolean {
        return vector.x().isFinite() && vector.y().isFinite() && vector.z().isFinite()
    }

    companion object {
        private const val BODY_ID_TAG = "BodyId"
        private const val SEAT_ID_TAG = "SeatId"
        private const val DEFAULT_SEAT_ID = VehicleInteractionDefinition.SEAT
        private const val DEFAULT_SEAT_OFFSET = 0.35
        private val BODY_ID: EntityDataAccessor<Long> =
            SynchedEntityData.defineId(BikeSeatEntity::class.java, EntityDataSerializers.LONG)
        private val SEAT_ID: EntityDataAccessor<String> =
            SynchedEntityData.defineId(BikeSeatEntity::class.java, EntityDataSerializers.STRING)
    }
}
