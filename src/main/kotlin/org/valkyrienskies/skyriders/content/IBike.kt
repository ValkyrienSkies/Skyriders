package org.valkyrienskies.skyriders.content

import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.bodies.properties.BodyKinematics
import org.valkyrienskies.core.api.bodies.properties.BodyTransform
import org.valkyrienskies.core.api.world.PhysLevel

interface IBike : IVehicle {
    override val id: String
    val definition: BikeDefinition

    override val bodyId: BodyId

    val boundingBox: AABB
    override val level: Level
    val config: BikePhysicsConfig
    val state: BikeRuntimeState

    fun getSeatOffset(): Double

    fun getKinematics(): BodyKinematics
    fun getTransform(): BodyTransform

    override fun getRenderTransform(): BodyTransform?

    fun getTilt(): Double

    override fun tick()
    fun physTick(physLevel: PhysLevel, body: PhysVsBody, input: BikeInput, dt: Double)

    override val vehicleDefinition: VehicleDefinition
        get() = definition.toVehicleDefinition()

    override val vehicleState: VehicleRuntimeState
        get() = state.toVehicleRuntimeState()

    override fun physTick(physLevel: PhysLevel, body: PhysVsBody, input: VehicleInput, dt: Double) {
        physTick(physLevel, body, input.toBikeInput(), dt)
    }

    override fun toVehicleSaveRecord(): VehicleSaveRecord = toSaveRecord().toVehicleSaveRecord().copy(
        partStates = state.partStates
    )

    fun toSaveRecord(): BikeSaveRecord = BikeSaveRecord(
        bodyId = bodyId,
        bikeType = id,
        engineOn = state.engineOn,
        fuelAmount = state.fuelAmount,
        raceParticipant = state.raceParticipant,
        raceColorId = state.raceColorId,
        visualLeanRad = state.visualLeanRad,
        frontWheelSpin = state.frontWheelSpin,
        rearWheelSpin = state.rearWheelSpin,
        frontWheelAngularVelocity = state.frontWheelAngularVelocity,
        rearWheelAngularVelocity = state.rearWheelAngularVelocity,
        partStates = state.partStates
    )
}
