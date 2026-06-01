package org.valkyrienskies.skyriders.content.bikes

import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import org.valkyrienskies.core.api.bodies.ClientVsBody
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.bodies.properties.BodyKinematics
import org.valkyrienskies.core.api.bodies.properties.BodyTransform
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.BikePhysicsConfig
import org.valkyrienskies.skyriders.content.BikeRuntimeState
import org.valkyrienskies.skyriders.content.IBike
import org.valkyrienskies.skyriders.util.BikePhysicsSolver

class DebugBike(override val bodyId: BodyId,
                override val boundingBox: AABB,
                override val level: Level,
                override val config: BikePhysicsConfig = BikePhysicsConfig.DEBUG_MOTORCYCLE,
                override val state: BikeRuntimeState = BikeRuntimeState()
) : IBike {

    override val id: String
        get() = "debug_bike"

    override fun getSeatOffset(): Double {
        return 0.55
    }

    override fun getKinematics(): BodyKinematics {
        return requireBody().kinematics
    }

    override fun getTransform(): BodyTransform {
        return getKinematics().transform
    }

    override fun getRenderTransform(): BodyTransform {
        val body = requireBody()
        return if (body is ClientVsBody) body.renderTransform else body.kinematics.transform
    }

    override fun getTilt(): Double {
        return state.visualLeanRad
    }

    override fun tick() {
    }

    override fun physTick(physLevel: PhysLevel, body: PhysVsBody, input: BikeInput, dt: Double) {
        BikePhysicsSolver.updateBikePhysics(body, physLevel, input, config, state, dt)
    }

    private fun requireBody() =
        requireNotNull(level.shipWorld?.allBodies?.getById(bodyId)) {
            "Bike $id is bound to missing VS body $bodyId"
        }

}
