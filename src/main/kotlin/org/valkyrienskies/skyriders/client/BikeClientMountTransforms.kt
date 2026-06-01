package org.valkyrienskies.skyriders.client

import net.minecraft.world.entity.Entity
import org.joml.Vector3d
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
    fun getMountedBikeSeatOffset(entity: Entity?): Double {
        val seat = entity?.vehicle as? BikeSeatEntity ?: return DEFAULT_SEAT_OFFSET
        val bike = BikeManager.getBike(seat.level().dimensionId, seat.bodyId)
        return bike?.getSeatOffset() ?: DEFAULT_SEAT_OFFSET
    }

    @JvmStatic
    fun getBikeYaw(transform: BodyTransform): Float {
        val forward = transform.rotation.transform(Vector3d(0.0, 0.0, 1.0))
        return Math.toDegrees(atan2(-forward.x, forward.z)).toFloat()
    }

    private const val DEFAULT_SEAT_OFFSET = 0.55
}
