package org.valkyrienskies.skyriders

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.valkyrienskies.skyriders.util.VehicleMovementSubsteps

class MovementRegressionTest {
    @Test
    fun blockedAxisDoesNotDiscardRemainingSlide() {
        for (speed in listOf(1.0, 3.0, 8.0)) {
            val result = VehicleMovementSubsteps.resolve(Vec3(speed, 0.0, speed)) { position, step ->
                Vec3(minOf(step.x, (0.7 - position.x).coerceAtLeast(0.0)), step.y, step.z)
            }
            assertEquals(0.7, result.x, 1e-9)
            assertEquals(speed, result.z, 1e-9)
        }
    }

    @Test
    fun slidingCanReachASecondWall() {
        val result = VehicleMovementSubsteps.resolve(Vec3(5.0, 0.0, 5.0)) { position, step ->
            Vec3(minOf(step.x, (0.7 - position.x).coerceAtLeast(0.0)), step.y,
                minOf(step.z, (2.3 - position.z).coerceAtLeast(0.0)))
        }
        assertEquals(0.7, result.x, 1e-9)
        assertEquals(2.3, result.z, 1e-9)
    }
}
