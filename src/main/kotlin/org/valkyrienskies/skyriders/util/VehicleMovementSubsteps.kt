package org.valkyrienskies.skyriders.util

import net.minecraft.world.phys.Vec3
import java.util.function.BiFunction

object VehicleMovementSubsteps {
    private const val MAX_SUBDIVISIONS = 256

    @JvmStatic
    fun resolve(movement: Vec3, collide: BiFunction<Vec3, Vec3, Vec3>): Vec3 {
        val subdivisions = (movement.length() / 0.5).coerceIn(0.0, MAX_SUBDIVISIONS - 1.0).toInt() + 1
        val step = movement.scale(1.0 / subdivisions)
        var correction = Vec3.ZERO
        repeat(subdivisions) { index ->
            val offset = step.scale(index.toDouble()).add(correction)
            correction = correction.add(collide.apply(offset, step).subtract(step))
        }
        return movement.add(correction)
    }
}
