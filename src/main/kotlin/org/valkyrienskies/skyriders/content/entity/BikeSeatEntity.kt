package org.valkyrienskies.skyriders.content.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.ClientVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import kotlin.math.atan2

class BikeSeatEntity(type: EntityType<BikeSeatEntity>, level: Level) : Entity(type, level) {
    var bodyId: BodyId
        get() = entityData.get(BODY_ID)
        set(value) = entityData.set(BODY_ID, value)
    private var lastBikeYaw: Float? = null

    init {
        blocksBuilding = false
        noPhysics = true
        noCulling = true
    }

    override fun tick() {
        super.tick()
        if (!level().isClientSide && passengers.isEmpty()) {
            BikeManager.updateInput(level().dimensionId, bodyId) { BikeInput.EMPTY }
            kill()
            return
        }

        val previousYaw = yRot
        if (!updateSeatPose()) {
            if (!level().isClientSide) {
                passengers.forEach(Entity::stopRiding)
                kill()
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

    override fun defineSynchedData() {
        entityData.define(BODY_ID, 0L)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        bodyId = compound.getLong(BODY_ID_TAG)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putLong(BODY_ID_TAG, bodyId)
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> {
        return ClientboundAddEntityPacket(this)
    }

    private fun updateSeatPose(): Boolean {
        val body = level().shipWorld?.allBodies?.getById(bodyId) ?: return false
        val seatOffset = BikeManager.getBike(level(), bodyId)?.getSeatOffset() ?: DEFAULT_SEAT_OFFSET
        val transform = if (level().isClientSide && body is ClientVsBody) body.renderTransform else body.kinematics.transform
        val seatWorld = transform.toWorld.transformPosition(Vector3d(0.0, seatOffset, 0.0))
        val forward = transform.rotation.transform(Vector3d(0.0, 0.0, 1.0))
        if (!isFinite(seatWorld) || !isFinite(forward)) return false

        moveTo(seatWorld.x, seatWorld.y, seatWorld.z, yawFromForward(forward), xRot)
        return true
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

        val passenger = controllingPassenger ?: return
        BikeManager.updateInput(level().dimensionId, bodyId) {
            it.copy(riderPresent = true)
        }
    }

    private fun sendBikeDebugToPassenger() {
        if (level().isClientSide) return

        val player = controllingPassenger as? ServerPlayer ?: return
        val bike = BikeManager.getBike(level().dimensionId, bodyId) ?: return
        SkyridersNetwork.sendBikeDebug(player, bodyId, bike.state)
    }

    private fun yawFromForward(forward: Vector3dc): Float {
        return Math.toDegrees(atan2(-forward.x(), forward.z())).toFloat()
    }

    private fun isFinite(vector: Vector3dc): Boolean {
        return vector.x().isFinite() && vector.y().isFinite() && vector.z().isFinite()
    }

    companion object {
        private const val BODY_ID_TAG = "BodyId"
        private const val DEFAULT_SEAT_OFFSET = 0.35
        private val BODY_ID: EntityDataAccessor<Long> =
            SynchedEntityData.defineId(BikeSeatEntity::class.java, EntityDataSerializers.LONG)
    }
}
