package org.valkyrienskies.skyriders.content

import net.minecraft.nbt.CompoundTag
import org.valkyrienskies.core.api.bodies.properties.BodyId

data class BikeSaveRecord(
    val bodyId: BodyId,
    val bikeType: String,
    val engineOn: Boolean = false,
    val fuelAmount: Double = Double.NaN,
    val raceParticipant: Boolean = false,
    val raceColorId: Int = -1,
    val visualLeanRad: Double = 0.0,
    val frontWheelSpin: Double = 0.0,
    val rearWheelSpin: Double = 0.0,
    val frontWheelAngularVelocity: Double = 0.0,
    val rearWheelAngularVelocity: Double = 0.0,
    val partStates: Map<String, VehiclePartState> = emptyMap()
) {
    fun save(): CompoundTag = CompoundTag().apply {
        putLong(BODY_ID_KEY, bodyId)
        putString(BIKE_TYPE_KEY, bikeType)
        putBoolean(ENGINE_ON_KEY, engineOn)
        if (fuelAmount.isFinite()) {
            putDouble(FUEL_AMOUNT_KEY, fuelAmount)
        }
        putBoolean(RACE_PARTICIPANT_KEY, raceParticipant)
        if (raceColorId >= 0) {
            putInt(RACE_COLOR_ID_KEY, raceColorId)
        }
        putDouble(VISUAL_LEAN_KEY, visualLeanRad)
        putDouble(FRONT_WHEEL_SPIN_KEY, frontWheelSpin)
        putDouble(REAR_WHEEL_SPIN_KEY, rearWheelSpin)
        putDouble(FRONT_WHEEL_ANGULAR_VELOCITY_KEY, frontWheelAngularVelocity)
        putDouble(REAR_WHEEL_ANGULAR_VELOCITY_KEY, rearWheelAngularVelocity)
        put(PART_STATES_KEY, savePartStates(partStates))
    }

    companion object {
        private const val BODY_ID_KEY = "body_id"
        private const val BIKE_TYPE_KEY = "bike_type"
        private const val ENGINE_ON_KEY = "engine_on"
        private const val FUEL_AMOUNT_KEY = "fuel_amount"
        private const val RACE_PARTICIPANT_KEY = "race_participant"
        private const val RACE_COLOR_ID_KEY = "race_color_id"
        private const val VISUAL_LEAN_KEY = "visual_lean"
        private const val FRONT_WHEEL_SPIN_KEY = "front_wheel_spin"
        private const val REAR_WHEEL_SPIN_KEY = "rear_wheel_spin"
        private const val FRONT_WHEEL_ANGULAR_VELOCITY_KEY = "front_wheel_angular_velocity"
        private const val REAR_WHEEL_ANGULAR_VELOCITY_KEY = "rear_wheel_angular_velocity"
        private const val PART_STATES_KEY = "part_states"

        fun load(tag: CompoundTag): BikeSaveRecord = BikeSaveRecord(
            bodyId = tag.getLong(BODY_ID_KEY),
            bikeType = tag.getString(BIKE_TYPE_KEY),
            engineOn = tag.getBoolean(ENGINE_ON_KEY),
            fuelAmount = if (tag.contains(FUEL_AMOUNT_KEY)) tag.getDouble(FUEL_AMOUNT_KEY) else Double.NaN,
            raceParticipant = tag.getBoolean(RACE_PARTICIPANT_KEY),
            raceColorId = if (tag.contains(RACE_COLOR_ID_KEY)) tag.getInt(RACE_COLOR_ID_KEY) else -1,
            visualLeanRad = tag.getDouble(VISUAL_LEAN_KEY),
            frontWheelSpin = tag.getDouble(FRONT_WHEEL_SPIN_KEY),
            rearWheelSpin = tag.getDouble(REAR_WHEEL_SPIN_KEY),
            frontWheelAngularVelocity = tag.getDouble(FRONT_WHEEL_ANGULAR_VELOCITY_KEY),
            rearWheelAngularVelocity = tag.getDouble(REAR_WHEEL_ANGULAR_VELOCITY_KEY),
            partStates = loadPartStates(tag.getCompound(PART_STATES_KEY))
        )

        private fun savePartStates(partStates: Map<String, VehiclePartState>): CompoundTag {
            return CompoundTag().apply {
                partStates.forEach { (id, state) -> put(id, state.data.copy()) }
            }
        }

        private fun loadPartStates(tag: CompoundTag): Map<String, VehiclePartState> {
            return tag.allKeys.associateWith { id -> VehiclePartState(id, tag.getCompound(id).copy()) }
        }
    }
}
