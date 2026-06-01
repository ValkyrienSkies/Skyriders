package org.valkyrienskies.skyriders.content

data class BikeInput(
    val steer: Double = 0.0,
    val throttle: Double = 0.0,
    val brake: Double = 0.0
) {
    companion object {
        val EMPTY = BikeInput()
    }
}
