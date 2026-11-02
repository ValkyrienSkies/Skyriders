package org.valkyrienskies.skyriders

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.valkyrienskies.core.api.bodies.shape.BoxBodyShapeData
import org.valkyrienskies.core.api.bodies.shape.CompoundBodyShapeData
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.BikePhysicsConfig
import org.valkyrienskies.skyriders.content.BikeRuntimeState
import org.valkyrienskies.skyriders.util.BikePhysicsSolver

class DivotRegressionTest {
    @ParameterizedTest
    @CsvSource("12,1", "24,1", "12,4", "24,4", "12,100", "24,100")
    fun bikeDoesNotPullItselfDownIntoDivots(speed: Double, width: Double) {
        NativeVehicleWorld().use { world ->
            val config = BikePhysicsConfig.DIRT_BIKE
            world.box(Vector3d(40.0, 1.0, 200.0), Vector3d(0.0, -1.5, 50.0))
            world.box(Vector3d(40.0, 1.0, 40.0), Vector3d(0.0, -0.5, -20.0))
            world.box(Vector3d(40.0, 1.0, 100.0), Vector3d(0.0, -0.5, width + 50.0))
            val bike = world.compound(listOf(CompoundBodyShapeData.Child(BoxBodyShapeData(config.collisionBoxSize),
                config.collisionBoxOffset, segmentId = 0)), Vector3d(0.0, 1.5, -6.0), config.mass, false,
                pivot = Vector3d(0.0, -0.2, 0.0))
            val state = BikeRuntimeState(engineOn = true)
            val dt = 1.0 / 60.0
            repeat(180) {
                BikePhysicsSolver.updateBikePhysics(bike.api, world.level, BikeInput.EMPTY, config, state, dt)
                world.step(dt)
            }
            bike.velocity(Vector3d(0.0, 0.0, speed))
            state.frontWheelAngularVelocity = speed / config.wheelRadius
            state.rearWheelAngularVelocity = speed / config.wheelRadius
            var lowestAcceleration = 0.0
            var lowestPitch = 0.0
            var samples = 0
            repeat(120) {
                val before = bike.current.velocity.y()
                BikePhysicsSolver.updateBikePhysics(bike.api, world.level,
                    BikeInput(throttle = 1.0, riderPresent = true), config, state, dt)
                world.step(dt)
                if (bike.current.position.z() in -1.0..minOf(width, 3.0)) {
                    samples++
                    lowestAcceleration = minOf(lowestAcceleration,
                        (minOf(0.0, bike.current.velocity.y()) - minOf(0.0, before)) / dt)
                    lowestPitch = minOf(lowestPitch, bike.current.rotation.transform(Vector3d(0.0, 0.0, 1.0)).y)
                }
            }
            println("DIVOT speed=$speed width=$width minAcceleration=$lowestAcceleration pitch=$lowestPitch position=${bike.current.position}")
            assertTrue(samples > 0, "Bike never reached divot")
            assertTrue(lowestAcceleration >= -12.0, "Bike was pulled downward beyond gravity: acceleration=$lowestAcceleration speed=$speed width=$width")
        }
    }
}
