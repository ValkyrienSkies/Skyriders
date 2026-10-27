package org.valkyrienskies.skyriders.content

data class BikeInput(
    val steer: Double = 0.0,
    val throttle: Double = 0.0,
    val brake: Double = 0.0,
    val jump: Double = 0.0,
    val pitch: Double = 0.0,
    val riderPresent: Boolean = false
) {
    fun clamped(): BikeInput = BikeInput(
        steer = finiteControlValue(steer, -1.0, 1.0),
        throttle = finiteControlValue(throttle, -1.0, 1.0),
        brake = finiteControlValue(brake, 0.0, 1.0),
        jump = finiteControlValue(jump, 0.0, 1.0),
        pitch = finiteControlValue(pitch, -1.0, 1.0),
        riderPresent = riderPresent
    )

    fun withEngineState(engineOn: Boolean): BikeInput = if (engineOn) this else copy(throttle = 0.0)

    companion object {
        val EMPTY = BikeInput()
    }
}
