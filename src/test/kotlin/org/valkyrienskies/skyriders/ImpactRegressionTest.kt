package org.valkyrienskies.skyriders

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.valkyrienskies.skyriders.util.VehicleImpactMath

class ImpactRegressionTest {
    @Test
    fun tangentialMotionAndSharedCruisingSpeedDoNotCountAsImpact() {
        val normal = Vector3d(1.0, 0.0, 0.0)
        assertEquals(0.0, VehicleImpactMath.normalSpeed(Vector3d(0.0, 0.0, 30.0), normal))
        val vehicle = Vector3d(2.0, 0.0, 25.0)
        val platform = Vector3d(0.0, 0.0, 25.0)
        val relative = Vector3d(vehicle).sub(platform)
        assertEquals(2.0, VehicleImpactMath.normalSpeed(relative, normal))
        val boost = Vector3d(12.0, -7.0, 20.0)
        assertEquals(2.0, VehicleImpactMath.normalSpeed(vehicle.add(boost).sub(platform.add(boost)), normal))
        assertEquals(12.0, VehicleImpactMath.normalSpeed(Vector3d(-12.0, 0.0, 0.0), normal))
    }

    @Test
    fun invalidNormalsAndVelocitiesDoNotProduceDamage() {
        assertEquals(0.0, VehicleImpactMath.normalSpeed(Vector3d(10.0, 0.0, 0.0), Vector3d()))
        assertEquals(0.0, VehicleImpactMath.normalSpeed(Vector3d(Double.NaN), Vector3d(1.0, 0.0, 0.0)))
    }
}
