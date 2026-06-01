package org.valkyrienskies.skyriders.client

import net.minecraft.world.entity.Entity
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
        val seat = entity?.vehicle as? BikeSeatEntity ?: return null
        val body = seat.level().shipWorld?.allBodies?.getById(seat.bodyId) as? ClientVsBody ?: return null
        return body.renderTransform
    }

    @JvmStatic
    fun getMountedBikeSpeed(entity: Entity?): Double {
        val seat = entity?.vehicle as? BikeSeatEntity ?: return 0.0
        val body = seat.level().shipWorld?.allBodies?.getById(seat.bodyId) as? ClientVsBody ?: return 0.0
        val velocity = body.kinematics.velocity
        return if (isFinite(velocity)) velocity.length() else 0.0
    }

    @JvmStatic
    fun getMountedBikeSeatOffset(entity: Entity?): Double {
        val seat = entity?.vehicle as? BikeSeatEntity ?: return DEFAULT_SEAT_OFFSET
        val bike = BikeManager.getBike(seat.level().dimensionId, seat.bodyId)
        return bike?.getSeatOffset() ?: DEFAULT_SEAT_OFFSET
    }

    @JvmStatic
    fun getMountedBikeWheelTopSpeed(entity: Entity?): Double {
        val seat = entity?.vehicle as? BikeSeatEntity ?: return DEFAULT_WHEEL_TOP_SPEED
        val bike = BikeManager.getBike(seat.level().dimensionId, seat.bodyId)
        val topSpeed = bike?.config?.wheelTopSpeed ?: DEFAULT_WHEEL_TOP_SPEED
        return if (topSpeed.isFinite() && topSpeed > 0.0) topSpeed else DEFAULT_WHEEL_TOP_SPEED
    }

    @JvmStatic
    fun getBikeYaw(transform: BodyTransform): Float {
        val forward = transform.rotation.transform(Vector3d(0.0, 0.0, 1.0))
        return Math.toDegrees(atan2(-forward.x, forward.z)).toFloat()
    }

    private fun isFinite(vector: Vector3dc): Boolean {
        return vector.x().isFinite() && vector.y().isFinite() && vector.z().isFinite()
    }

    private const val DEFAULT_SEAT_OFFSET = 0.35
    private const val DEFAULT_WHEEL_TOP_SPEED = 24.0
}
