package org.valkyrienskies.skyriders.content.racing

import org.joml.Vector3d
import kotlin.math.abs

internal class RaceCrossingTracker(line: RaceLine, initialPosition: Vector3d) {
    private var previousPosition = Vector3d(initialPosition)
    private var lastSide = side(line.signedDistance(initialPosition))

    fun update(line: RaceLine, position: Vector3d): Crossing? {
        if (!position.isFinite) return null
        val previous = previousPosition
        previousPosition = Vector3d(position)
        val distance = line.signedDistance(position)
        val currentSide = side(distance)
        if (currentSide == 0) return null
        val crossed = lastSide != 0 && currentSide != lastSide
        lastSide = currentSide
        if (!crossed) return null
        val previousDistance = line.signedDistance(previous)
        val fraction = (previousDistance / (previousDistance - distance)).coerceIn(0.0, 1.0)
        val intersection = Vector3d(previous).lerp(position, fraction)
        return if (line.containsCrossing(intersection)) Crossing(fraction, intersection) else null
    }

    data class Crossing(val fraction: Double, val position: Vector3d)

    private fun side(distance: Double): Int = when {
        !distance.isFinite() || abs(distance) < 1.0e-4 -> 0
        distance > 0.0 -> 1
        else -> -1
    }
}
