package org.valkyrienskies.skyriders

import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.valkyrienskies.skyriders.util.VehicleWheelPhysics

class WheelStepProfileTest {
    @ParameterizedTest
    @CsvSource("-45", "-30", "30", "45")
    fun diagonalContactRetainsCrossSlopeAndIndependentWheelHeight(heading: Double) {
        NativeVehicleWorld().use { world ->
            world.box(Vector3d(40.0, 1.0, 80.0), Vector3d(0.0, -0.5, 0.0))
            world.box(Vector3d(40.0, 1.0, 40.0), Vector3d(0.0, 0.5, 20.0))
            val body = world.box(Vector3d(0.1), Vector3d(0.0, 0.6, -1.0), static = false)
            val forward = Quaterniond().rotateY(Math.toRadians(heading)).transform(Vector3d(0.0, 0.0, 1.0))
            body.velocity(Vector3d(forward).mul(12.0))
            val settings = VehicleWheelPhysics.StepSettings(1.15, 1.0) { true }
            fun wheel(z: Double) = VehicleWheelPhysics.sampleSphereWheel(body.api, world.level,
                Vector3d(0.0, 0.6, z), 0.82, 0.275,
                forward, step = settings)
            val front = wheel(-0.8)
            val rear = wheel(-2.2)
            assertTrue(front.stepRise > rear.stepRise + 0.1)
            assertEquals(0.0, front.contactNormalWorld.x, 1e-6)
            assertTrue(front.contactNormalWorld.z < -0.1)
            assertEquals(0.0, front.wheelForwardWorld.dot(front.contactNormalWorld), 1e-6)
            assertEquals(0.0, wheel(2.0).stepRise)
        }
    }

    @ParameterizedTest
    @CsvSource("1.5,1.0", "0.0,2.0")
    fun nearbyBlocksAndTallWallsDoNotProvideStepSupport(x: Double, height: Double) {
        NativeVehicleWorld().use { world ->
            world.box(Vector3d(20.0, 1.0, 20.0), Vector3d(0.0, -0.5, 0.0))
            world.box(Vector3d(1.0, height, 1.0), Vector3d(x, height * 0.5, 0.5))
            val body = world.box(Vector3d(0.1), Vector3d(0.0, 0.6, -0.8), static = false)
            body.velocity(Vector3d(0.0, 0.0, 12.0))
            val contact = VehicleWheelPhysics.sampleSphereWheel(body.api, world.level, body.current.position,
                0.82, 0.275, Vector3d(0.0, 0.0, 1.0),
                step = VehicleWheelPhysics.StepSettings(1.15, 1.0) { true })
            assertEquals(0.0, contact.stepRise)
        }
    }

    @Test
    fun clearanceRefusalKeepsPhysicalWheelContact() {
        NativeVehicleWorld().use { world ->
            world.box(Vector3d(20.0, 1.0, 20.0), Vector3d(0.0, -0.5, 0.0))
            world.box(Vector3d(10.0, 1.0, 10.0), Vector3d(0.0, 0.5, 5.0))
            val body = world.box(Vector3d(0.1), Vector3d(0.0, 0.6, -0.8), static = false)
            body.velocity(Vector3d(0.0, 0.0, 12.0))
            val contact = VehicleWheelPhysics.sampleSphereWheel(body.api, world.level, body.current.position,
                0.82, 0.275, Vector3d(0.0, 0.0, 1.0),
                step = VehicleWheelPhysics.StepSettings(1.15, 1.0) { false })
            assertEquals(0.0, contact.stepRise)
            assertTrue(contact.grounded)
            assertEquals(0.0, contact.contactPointWorld.y, 1e-5)
        }
    }
}
