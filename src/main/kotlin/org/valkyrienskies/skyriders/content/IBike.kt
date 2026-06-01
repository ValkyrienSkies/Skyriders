package org.valkyrienskies.skyriders.content

import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.bodies.properties.BodyKinematics
import org.valkyrienskies.core.api.bodies.properties.BodyTransform
import org.valkyrienskies.core.api.world.PhysLevel

interface IBike {
    val id: String

    val bodyId: BodyId

    val boundingBox: AABB
    val level: Level
    val config: BikePhysicsConfig
    val state: BikeRuntimeState

    fun getSeatOffset(): Double

    fun getKinematics(): BodyKinematics
    fun getTransform(): BodyTransform

    fun getRenderTransform(): BodyTransform

    fun getTilt(): Double

    fun tick()
    fun physTick(physLevel: PhysLevel, body: PhysVsBody, dt: Double)

    fun toSaveRecord(): BikeSaveRecord = BikeSaveRecord(
        bodyId = bodyId,
        bikeType = id,
        visualLeanRad = state.visualLeanRad,
        frontWheelSpin = state.frontWheelSpin,
        rearWheelSpin = state.rearWheelSpin
    )
}
