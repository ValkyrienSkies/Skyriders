package org.valkyrienskies.skyriders.client

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.ClientVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyTransform
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import kotlin.math.atan2

object BikeClientMountTransforms {
    @JvmStatic
    fun getMountedBikeRenderTransform(entity: Entity?): BodyTransform? {
        val seat = getBikeSeat(entity) ?: return null
        return getBikeRenderTransform(seat)
    }

    @JvmStatic
    fun getBikeRenderTransform(seat: BikeSeatEntity?): BodyTransform? {
        if (seat == null) return null
        val body = seat.level().shipWorld?.allBodies?.getById(seat.bodyId) as? ClientVsBody ?: return null
        return body.renderTransform
    }

    @JvmStatic
    fun getMountedBikeSpeed(entity: Entity?): Double {
        val seat = getBikeSeat(entity) ?: return 0.0
        val body = seat.level().shipWorld?.allBodies?.getById(seat.bodyId) as? ClientVsBody ?: return 0.0
        val velocity = body.kinematics.velocity
        return if (isFinite(velocity)) velocity.length() else 0.0
    }

    @JvmStatic
    fun getMountedBikeSeatOffset(entity: Entity?): Double {
        val seat = getBikeSeat(entity) ?: return DEFAULT_SEAT_OFFSET
        val bike = BikeManager.getBike(seat.level().dimensionId, seat.bodyId)
        return bike?.getSeatOffset() ?: DEFAULT_SEAT_OFFSET
    }

    @JvmStatic
    fun getMountedBikeCameraPosition(entity: Entity?, eyeHeight: Double): Vec3? {
        val transform = getMountedBikeRenderTransform(entity) ?: return null
        val seatOffset = getMountedBikeSeatOffset(entity)
        val seatPosition = transform.toWorld.transformPosition(Vector3d(0.0, seatOffset, 0.0))
        val eyeOffset = transform.rotation.transform(Vector3d(0.0, eyeHeight, 0.0))
        if (!isFinite(seatPosition) || !isFinite(eyeOffset)) return null
        return Vec3(seatPosition.x + eyeOffset.x, seatPosition.y + eyeOffset.y, seatPosition.z + eyeOffset.z)
    }

    @JvmStatic
    fun getMountedBikeCenterPosition(entity: Entity?): Vec3? {
        val transform = getMountedBikeRenderTransform(entity) ?: return null
        val position = transform.toWorld.transformPosition(Vector3d(0.0, THIRD_PERSON_PIVOT_LOCAL_Y, 0.0))
        if (!isFinite(position)) return null
        return Vec3(position.x, position.y, position.z)
    }

    @JvmStatic
    fun getBikeMountedEntityRenderPosition(seat: BikeSeatEntity?, entity: Entity?): Vector3d? {
        if (seat == null) return null
        val transform = getBikeRenderTransform(seat) ?: return null
        val seatOffset = getMountedBikeSeatOffset(seat.controllingPassenger ?: seat.passengers.firstOrNull())
        val seatPosition = transform.toWorld.transformPosition(Vector3d(0.0, seatOffset, 0.0))
        if (entity != null && entity !== seat) {
            val riderOffset = transform.rotation.transform(
                Vector3d(0.0, seat.passengersRidingOffset + entity.myRidingOffset, 0.0)
            )
            seatPosition.add(riderOffset)
        }
        return if (isFinite(seatPosition)) seatPosition else null
    }

    @JvmStatic
    fun getBikeMountedEntityRenderYaw(seat: BikeSeatEntity?, entityYaw: Float): Float? {
        val transform = getBikeRenderTransform(seat) ?: return null
        val bikeYaw = getBikeYaw(transform)
        val localYaw = net.minecraft.util.Mth.wrapDegrees(entityYaw - (seat?.yRot ?: bikeYaw))
        if (!bikeYaw.isFinite() || !localYaw.isFinite()) return null
        return bikeYaw + localYaw
    }

    @JvmStatic
    fun getBikeMountedBodyRenderYaw(seat: BikeSeatEntity?): Float? {
        val transform = getBikeRenderTransform(seat) ?: return null
        val bikeYaw = getBikeYaw(transform)
        return if (bikeYaw.isFinite()) bikeYaw else null
    }

    @JvmStatic
    fun getBikeMountedEntityRenderTilt(seat: BikeSeatEntity?): Quaternionf? {
        val transform = getBikeRenderTransform(seat) ?: return null
        val bikeYaw = getBikeYaw(transform)
        if (!bikeYaw.isFinite()) return null

        val yawOnly = Quaterniond().rotateY(Math.toRadians(-bikeYaw.toDouble()))
        val tilt = transform.rotation.mul(yawOnly.invert(), Quaterniond())
        if (!isFinite(tilt)) return null
        return Quaternionf(tilt)
    }

    @JvmStatic
    fun getMountedBikeWheelTopSpeed(entity: Entity?): Double {
        val seat = getBikeSeat(entity) ?: return DEFAULT_WHEEL_TOP_SPEED
        val bike = BikeManager.getBike(seat.level().dimensionId, seat.bodyId)
        val topSpeed = bike?.config?.wheelTopSpeed ?: DEFAULT_WHEEL_TOP_SPEED
        return if (topSpeed.isFinite() && topSpeed > 0.0) topSpeed else DEFAULT_WHEEL_TOP_SPEED
    }

    @JvmStatic
    fun getBikeYaw(transform: BodyTransform): Float {
        val forward = transform.rotation.transform(Vector3d(0.0, 0.0, 1.0))
        return Math.toDegrees(atan2(-forward.x, forward.z)).toFloat()
    }

    @JvmStatic
    fun getMountedBikeSeatYaw(entity: Entity?, fallbackYaw: Float): Float {
        val seat = getBikeSeat(entity) ?: return fallbackYaw
        return seat.yRot
    }

    private fun getBikeSeat(entity: Entity?): BikeSeatEntity? {
        return when (entity) {
            is BikeSeatEntity -> entity
            else -> entity?.vehicle as? BikeSeatEntity
        }
    }

    private fun isFinite(vector: Vector3dc): Boolean {
        return vector.x().isFinite() && vector.y().isFinite() && vector.z().isFinite()
    }

    private fun isFinite(quaternion: Quaterniond): Boolean {
        return quaternion.x.isFinite() && quaternion.y.isFinite() && quaternion.z.isFinite() && quaternion.w.isFinite()
    }

    private const val DEFAULT_SEAT_OFFSET = 0.35
    private const val DEFAULT_WHEEL_TOP_SPEED = 24.0
    private const val THIRD_PERSON_PIVOT_LOCAL_Y = 1.45
}
