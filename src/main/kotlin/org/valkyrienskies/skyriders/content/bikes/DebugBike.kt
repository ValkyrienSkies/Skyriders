package org.valkyrienskies.skyriders.content.bikes

import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.bodies.properties.BodyKinematics
import org.valkyrienskies.core.api.bodies.properties.BodyTransform
import org.valkyrienskies.skyriders.content.IBike

class DebugBike(override val bodyId: BodyId,
                override val boundingBox: AABB,
                override val level: Level
) : IBike {

    override fun getSeatOffset(): Double {
        TODO("Not yet implemented")
    }

    override fun getKinematics(): BodyKinematics {
        TODO("Not yet implemented")
    }

    override fun getTransform(): BodyTransform {
        TODO("Not yet implemented")
    }

    override fun getRenderTransform(): BodyTransform {
        TODO("Not yet implemented")
    }

    override fun getTilt(): Double {
        TODO("Not yet implemented")
    }

    override fun tick() {
        TODO("Not yet implemented")
    }

    override fun physTick() {
        TODO("Not yet implemented")
    }


}