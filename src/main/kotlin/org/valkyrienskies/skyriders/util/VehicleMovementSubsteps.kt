package org.valkyrienskies.skyriders.util

import net.minecraft.world.phys.Vec3
import java.util.function.BiFunction

object VehicleMovementSubsteps {
    @JvmStatic
    fun resolve(movement: Vec3, collide: BiFunction<Vec3, Vec3, Vec3>): Vec3 {
        val subdivisions = (movement.length() / 0.5).toInt() + 1
        val step = movement.scale(1.0 / subdivisions)
        var offset = Vec3.ZERO
        repeat(subdivisions) {
            offset = offset.add(collide.apply(offset, step))
        }
        return offset
    }
}
