package org.valkyrienskies.skyriders.client

import net.minecraft.world.entity.Entity
import org.valkyrienskies.core.api.bodies.ClientVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyTransform
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity

object BikeClientMountTransforms {
    @JvmStatic
    fun getMountedBikeRenderTransform(entity: Entity?): BodyTransform? {
        val seat = entity?.vehicle as? BikeSeatEntity ?: return null
        val body = seat.level().shipWorld?.allBodies?.getById(seat.bodyId) as? ClientVsBody ?: return null
        return body.renderTransform
    }
}
