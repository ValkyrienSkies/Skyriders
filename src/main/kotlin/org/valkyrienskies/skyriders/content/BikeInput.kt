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
        steer = steer.coerceIn(-1.0, 1.0),
        throttle = throttle.coerceIn(-1.0, 1.0),
        brake = brake.coerceIn(0.0, 1.0),
        jump = jump.coerceIn(0.0, 1.0),
        pitch = pitch.coerceIn(-1.0, 1.0),
        riderPresent = riderPresent
    )

    companion object {
        val EMPTY = BikeInput()
    }
}
