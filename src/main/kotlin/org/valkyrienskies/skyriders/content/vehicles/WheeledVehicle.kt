package org.valkyrienskies.skyriders.content.vehicles

import org.valkyrienskies.skyriders.content.withVehicleState
import org.valkyrienskies.skyriders.content.snapshotParts
import org.valkyrienskies.skyriders.content.finiteDouble
import org.valkyrienskies.skyriders.content.transmissionGear

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.Level
import org.valkyrienskies.core.api.bodies.ClientVsBody
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.bodies.properties.BodyTransform
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.VehicleDefinition
import org.valkyrienskies.skyriders.content.VehicleFuel
import org.valkyrienskies.skyriders.content.VehicleInput
import org.valkyrienskies.skyriders.content.VehiclePartState
import org.valkyrienskies.skyriders.content.VehicleRuntimeState
import org.valkyrienskies.skyriders.content.VehicleSaveRecord
import org.valkyrienskies.skyriders.content.WheeledVehicleBehaviorDefinition
import org.valkyrienskies.skyriders.content.WheeledVehicleRuntimeState
import org.valkyrienskies.skyriders.content.initialPartStates
import org.valkyrienskies.skyriders.util.WheeledVehiclePhysicsSolver

class WheeledVehicle(
    override val bodyId: BodyId,
    override val level: Level,
    override val vehicleDefinition: VehicleDefinition,
    val wheeledState: WheeledVehicleRuntimeState = WheeledVehicleRuntimeState()
) : IVehicle {
    private val collisionBoxes = vehicleDefinition.body.resolvedCollisionBoxes()
    override val id: String
        get() = vehicleDefinition.id.toString()

    override val vehicleState: VehicleRuntimeState
        get() = VehicleRuntimeState(
            engineOn = wheeledState.engineOn,
            fuelAmount = wheeledState.fuelAmount,
            raceParticipant = wheeledState.raceParticipant,
            raceColorId = wheeledState.raceColorId,
            partStates = wheeledState.partStates
        )

    override fun getRenderTransform(): BodyTransform? {
        val body = level.shipWorld?.allBodies?.getById(bodyId) ?: return null
        return if (body is ClientVsBody) body.renderTransform else body.kinematics.transform
    }

    override fun tick() {
    }

    override fun physTick(physLevel: PhysLevel, body: PhysVsBody, input: VehicleInput, dt: Double) {
        val behavior = vehicleDefinition.behavior as? WheeledVehicleBehaviorDefinition ?: return
        val activeInput = input.withEngineState(wheeledState.engineOn)
        WheeledVehiclePhysicsSolver.updatePhysics(body, physLevel, activeInput, behavior.physics, wheeledState, dt, collisionBoxes)
    }

    override fun toVehicleSaveRecord(): VehicleSaveRecord = withVehicleState(this) { VehicleSaveRecord(
        bodyId = bodyId,
        vehicleType = id,
        engineOn = wheeledState.engineOn,
        fuelAmount = wheeledState.fuelAmount,
        raceParticipant = wheeledState.raceParticipant,
        raceColorId = wheeledState.raceColorId,
        behaviorTag = CompoundTag().apply {
            putString("behavior_type", "wheeled")
            putDouble("front_wheel_spin", wheeledState.frontWheelSpin)
            putDouble("rear_wheel_spin", wheeledState.rearWheelSpin)
            putDouble("front_wheel_angular_velocity", wheeledState.frontWheelAngularVelocity)
            putDouble("rear_wheel_angular_velocity", wheeledState.rearWheelAngularVelocity)
            putBoolean("parking_brake_engaged", wheeledState.parkingBrakeEngaged)
            putInt("transmission_gear", wheeledState.transmissionGear)
            putDouble("engine_rpm", wheeledState.engineRpm)
            putBoolean("engine_stalled", wheeledState.engineStalled)
            val wheelSpin = CompoundTag()
            val wheelAngularVelocity = CompoundTag()
            val visualWheelAngularVelocity = CompoundTag()
            wheeledState.wheelSpinById.forEach { (id, value) -> wheelSpin.putDouble(id, value) }
            wheeledState.wheelAngularVelocityById.forEach { (id, value) -> wheelAngularVelocity.putDouble(id, value) }
            wheeledState.visualWheelAngularVelocityById.forEach { (id, value) -> visualWheelAngularVelocity.putDouble(id, value) }
            put("wheel_spin_by_id", wheelSpin)
            put("wheel_angular_velocity_by_id", wheelAngularVelocity)
            put("visual_wheel_angular_velocity_by_id", visualWheelAngularVelocity)
        },
        partStates = wheeledState.partStates.snapshotParts()
    ) }

}

fun wheeledRuntimeStateFromTag(
    definition: VehicleDefinition,
    tag: CompoundTag,
    engineOn: Boolean,
    fuelAmount: Double,
    raceParticipant: Boolean = false,
    raceColorId: Int = -1,
    partStates: Map<String, VehiclePartState>
): WheeledVehicleRuntimeState {
    val physics = (definition.behavior as WheeledVehicleBehaviorDefinition).physics
    val state = WheeledVehicleRuntimeState(
        engineOn = engineOn,
        fuelAmount = VehicleFuel.initialAmount(definition, fuelAmount),
        raceParticipant = raceParticipant,
        raceColorId = raceColorId,
        parkingBrakeEngaged = tag.getBoolean("parking_brake_engaged"),
        transmissionGear = tag.transmissionGear(physics.transmission),
        engineRpm = if (tag.contains("engine_rpm")) tag.finiteDouble("engine_rpm", physics.engine.idleRpm)
            .coerceIn(0.0, physics.engine.revLimiterRpm) else physics.engine.idleRpm,
        engineStalled = tag.getBoolean("engine_stalled"),
        frontWheelSpin = tag.finiteDouble("front_wheel_spin"),
        rearWheelSpin = tag.finiteDouble("rear_wheel_spin"),
        frontWheelAngularVelocity = tag.finiteDouble("front_wheel_angular_velocity"),
        rearWheelAngularVelocity = tag.finiteDouble("rear_wheel_angular_velocity")
    )
    state.partStates.putAll(definition.initialPartStates(partStates))
    val wheelSpin = tag.getCompound("wheel_spin_by_id")
    wheelSpin.allKeys.forEach { key -> state.wheelSpinById[key] = wheelSpin.finiteDouble(key) }
    val wheelAngularVelocity = tag.getCompound("wheel_angular_velocity_by_id")
    wheelAngularVelocity.allKeys.forEach { key -> state.wheelAngularVelocityById[key] = wheelAngularVelocity.finiteDouble(key) }
    val visualWheelAngularVelocity = tag.getCompound("visual_wheel_angular_velocity_by_id")
    if (visualWheelAngularVelocity.isEmpty) {
        wheelAngularVelocity.allKeys.forEach { key -> state.visualWheelAngularVelocityById[key] = wheelAngularVelocity.finiteDouble(key) }
    } else {
        visualWheelAngularVelocity.allKeys.forEach { key -> state.visualWheelAngularVelocityById[key] = visualWheelAngularVelocity.finiteDouble(key) }
    }
    return state
}
