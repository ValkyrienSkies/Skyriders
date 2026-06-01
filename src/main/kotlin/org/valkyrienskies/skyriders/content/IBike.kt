package org.valkyrienskies.skyriders.content

import com.mrcrayfish.framework.client.model.IOpenModel
import com.mrcrayfish.framework.client.model.OpenModelHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.bodies.properties.BodyKinematics
import org.valkyrienskies.core.api.bodies.properties.BodyTransform

interface IBike {
    val id: String

    val bodyId: BodyId

    val boundingBox: AABB
    val level: Level

    fun getSeatOffset(): Double

    fun getKinematics(): BodyKinematics
    fun getTransform(): BodyTransform

    fun getRenderTransform(): BodyTransform

    fun getTilt(): Double

    fun tick()
    fun physTick()
}