package org.valkyrienskies.skyriders

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.valkyrienskies.core.api.physics.RayCastResult
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.skyriders.util.VehicleWheelPhysics
import java.lang.reflect.Proxy

class WheelContactRegressionTest {
    @Test
    fun wheelRecoversSupportAndTireFrameAfterSidewaysGroundNormal() {
        NativeVehicleWorld().use { world ->
            world.box(Vector3d(20.0, 1.0, 20.0), Vector3d(0.0, -0.5, 0.0))
            val wheelBody = world.box(Vector3d(0.2), Vector3d(0.0, 0.6, 0.0), static = false)
            wheelBody.velocity(Vector3d(0.0, 0.0, 24.0))
            val contact = VehicleWheelPhysics.sampleSphereWheel(wheelBody.api, world.level,
                Vector3d(0.0, 0.6, 0.0), 1.2, 0.3, Vector3d(0.0, 1.0, 0.0))
            assertTrue(contact.grounded)
            assertEquals(-1.0, contact.suspensionDirWorld.y, 1e-5)
            assertEquals(0.0, contact.wheelForwardWorld.y, 1e-5)
            assertEquals(0.0, contact.wheelRightWorld.y, 1e-5)
        }
    }

    @Test
    fun overlapsAndWallsCannotBecomeSuspensionSupport() {
        NativeVehicleWorld().use { world ->
            val ground = world.box(Vector3d(10.0, 2.0, 10.0), Vector3d())
            val wheelBody = world.box(Vector3d(0.2), Vector3d(0.0, 4.0, 0.0), static = false)
            val overlap = VehicleWheelPhysics.sampleRaycastWheel(wheelBody.api, world.level,
                Vector3d(), Vector3d(0.0, -1.0, 0.0), 2.0, Vector3d(0.0, 0.0, 1.0), Vector3d(1.0, 0.0, 0.0))
            assertFalse(overlap.grounded)
            for ((distance, normal) in listOf(0.0 to Vector3d(0.0, 1.0, 0.0),
                -0.1 to Vector3d(0.0, 1.0, 0.0), 0.5 to Vector3d(),
                0.5 to Vector3d(1.0, 0.0, 0.0), 0.5 to Vector3d(Double.NaN))) {
                val hit = RayCastResult(ground.api, distance, normal, Vector3d(), Vector3d())
                assertFalse(VehicleWheelPhysics.isSupportHit(hit, wheelBody.api.id, 2.0, Vector3d(0.0, 1.0, 0.0)))
            }
        }
    }

    @Test
    fun contactUsesQueriedSurfaceVelocityEvenForStaticParent() {
        NativeVehicleWorld().use { world ->
            val parent = world.box(Vector3d(1.0), Vector3d())
            val wheelBody = world.box(Vector3d(0.2), Vector3d(0.0, 3.0, 0.0), static = false)
            wheelBody.velocity(Vector3d(0.0, 0.0, 11.0))
            val hit = RayCastResult(parent.api, 0.5, Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, 7.0), Vector3d())
            val query = Proxy.newProxyInstance(PhysLevel::class.java.classLoader, arrayOf(PhysLevel::class.java)) { _, method, _ ->
                check(method.name == "rayCast")
                hit
            } as PhysLevel
            val contact = VehicleWheelPhysics.sampleRaycastWheel(wheelBody.api, query, Vector3d(0.0, 3.0, 0.0),
                Vector3d(0.0, -1.0, 0.0), 1.0, Vector3d(0.0, 0.0, 1.0), Vector3d(1.0, 0.0, 0.0))
            assertTrue(contact.grounded)
            assertEquals(7.0, contact.surfaceVelocityWorld.z, 1e-8)
            assertEquals(4.0, contact.relativeWheelVelocityWorld.z, 1e-8)
        }
    }
}
