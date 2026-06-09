package org.valkyrienskies.skyriders.content.vehicles

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
    override val id: String
        get() = vehicleDefinition.id.toString()

    override val vehicleState: VehicleRuntimeState
        get() = VehicleRuntimeState(engineOn = wheeledState.engineOn, partStates = wheeledState.partStates)

    override fun getRenderTransform(): BodyTransform {
        val body = requireBody()
        return if (body is ClientVsBody) body.renderTransform else body.kinematics.transform
    }

    override fun tick() {
    }

    override fun physTick(physLevel: PhysLevel, body: PhysVsBody, input: VehicleInput, dt: Double) {
        val behavior = vehicleDefinition.behavior as? WheeledVehicleBehaviorDefinition ?: return
        val activeInput = if (wheeledState.engineOn) input else VehicleInput.EMPTY.copy(riderPresent = input.riderPresent)
        WheeledVehiclePhysicsSolver.updatePhysics(body, physLevel, activeInput, behavior.physics, wheeledState, dt)
    }

    override fun toVehicleSaveRecord(): VehicleSaveRecord = VehicleSaveRecord(
        bodyId = bodyId,
        vehicleType = id,
        engineOn = wheeledState.engineOn,
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
            wheeledState.wheelSpinById.forEach { (id, value) -> wheelSpin.putDouble(id, value) }
            wheeledState.wheelAngularVelocityById.forEach { (id, value) -> wheelAngularVelocity.putDouble(id, value) }
            put("wheel_spin_by_id", wheelSpin)
            put("wheel_angular_velocity_by_id", wheelAngularVelocity)
        },
        partStates = wheeledState.partStates
    )

    private fun requireBody() =
        requireNotNull(level.shipWorld?.allBodies?.getById(bodyId)) {
            "Vehicle $id is bound to missing VS body $bodyId"
        }
}

fun wheeledRuntimeStateFromTag(
    definition: VehicleDefinition,
    tag: CompoundTag,
    engineOn: Boolean,
    partStates: Map<String, VehiclePartState>
): WheeledVehicleRuntimeState {
    val state = WheeledVehicleRuntimeState(
        engineOn = engineOn,
        parkingBrakeEngaged = tag.getBoolean("parking_brake_engaged"),
        transmissionGear = tag.getInt("transmission_gear").takeIf { it != 0 } ?: 1,
        engineRpm = tag.getDouble("engine_rpm").takeIf { it > 0.0 } ?: 850.0,
        engineStalled = tag.getBoolean("engine_stalled"),
        frontWheelSpin = tag.getDouble("front_wheel_spin"),
        rearWheelSpin = tag.getDouble("rear_wheel_spin"),
        frontWheelAngularVelocity = tag.getDouble("front_wheel_angular_velocity"),
        rearWheelAngularVelocity = tag.getDouble("rear_wheel_angular_velocity")
    )
    state.partStates.putAll(definition.initialPartStates(partStates))
    val wheelSpin = tag.getCompound("wheel_spin_by_id")
    wheelSpin.allKeys.forEach { key -> state.wheelSpinById[key] = wheelSpin.getDouble(key) }
    val wheelAngularVelocity = tag.getCompound("wheel_angular_velocity_by_id")
    wheelAngularVelocity.allKeys.forEach { key -> state.wheelAngularVelocityById[key] = wheelAngularVelocity.getDouble(key) }
    return state
}
