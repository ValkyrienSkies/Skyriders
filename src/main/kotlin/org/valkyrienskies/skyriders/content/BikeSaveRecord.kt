package org.valkyrienskies.skyriders.content

import net.minecraft.nbt.CompoundTag
import org.valkyrienskies.core.api.bodies.properties.BodyId

data class BikeSaveRecord(
    val bodyId: BodyId,
    val bikeType: String,
    val engineOn: Boolean = false,
    val visualLeanRad: Double = 0.0,
    val frontWheelSpin: Double = 0.0,
    val rearWheelSpin: Double = 0.0,
    val frontWheelAngularVelocity: Double = 0.0,
    val rearWheelAngularVelocity: Double = 0.0
) {
    fun save(): CompoundTag = CompoundTag().apply {
        putLong(BODY_ID_KEY, bodyId)
        putString(BIKE_TYPE_KEY, bikeType)
        putBoolean(ENGINE_ON_KEY, engineOn)
        putDouble(VISUAL_LEAN_KEY, visualLeanRad)
        putDouble(FRONT_WHEEL_SPIN_KEY, frontWheelSpin)
        putDouble(REAR_WHEEL_SPIN_KEY, rearWheelSpin)
        putDouble(FRONT_WHEEL_ANGULAR_VELOCITY_KEY, frontWheelAngularVelocity)
        putDouble(REAR_WHEEL_ANGULAR_VELOCITY_KEY, rearWheelAngularVelocity)
    }

    companion object {
        private const val BODY_ID_KEY = "body_id"
        private const val BIKE_TYPE_KEY = "bike_type"
        private const val ENGINE_ON_KEY = "engine_on"
        private const val VISUAL_LEAN_KEY = "visual_lean"
        private const val FRONT_WHEEL_SPIN_KEY = "front_wheel_spin"
        private const val REAR_WHEEL_SPIN_KEY = "rear_wheel_spin"
        private const val FRONT_WHEEL_ANGULAR_VELOCITY_KEY = "front_wheel_angular_velocity"
        private const val REAR_WHEEL_ANGULAR_VELOCITY_KEY = "rear_wheel_angular_velocity"

        fun load(tag: CompoundTag): BikeSaveRecord = BikeSaveRecord(
            bodyId = tag.getLong(BODY_ID_KEY),
            bikeType = tag.getString(BIKE_TYPE_KEY),
            engineOn = tag.getBoolean(ENGINE_ON_KEY),
            visualLeanRad = tag.getDouble(VISUAL_LEAN_KEY),
            frontWheelSpin = tag.getDouble(FRONT_WHEEL_SPIN_KEY),
            rearWheelSpin = tag.getDouble(REAR_WHEEL_SPIN_KEY),
            frontWheelAngularVelocity = tag.getDouble(FRONT_WHEEL_ANGULAR_VELOCITY_KEY),
            rearWheelAngularVelocity = tag.getDouble(REAR_WHEEL_ANGULAR_VELOCITY_KEY)
        )
    }
}
