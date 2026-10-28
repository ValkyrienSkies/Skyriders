package org.valkyrienskies.skyriders

import net.minecraft.world.phys.AABB
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.valkyrienskies.core.impl.bodies.properties.BodyTransformImpl
import org.valkyrienskies.skyriders.content.VehicleCollisionBoxDefinition
import org.valkyrienskies.skyriders.util.VehicleCollisionSweep

class VehicleCollisionSweepTest {
    private fun pose(x: Double = 0.0, z: Double = 0.0, yaw: Double = 0.0) =
        BodyTransformImpl(Vector3d(x, 0.0, z), Quaterniond().rotateY(yaw), Vector3d(1.0), Vector3d())
    private fun target(x: Double, z: Double) = AABB(x - 0.1, -0.1, z - 0.1, x + 0.1, 0.1, z + 0.1)
    private val cube = listOf(VehicleCollisionBoxDefinition(Vector3d(1.0), Vector3d()))

    @Test
    fun `diagonal broadphase corners do not become runover hits`() {
        val from = pose()
        val to = pose(10.0, 10.0)
        assertTrue(VehicleCollisionSweep.sweptBounds(cube, from, to).intersects(target(0.0, 10.0)))
        assertNull(VehicleCollisionSweep.cast(cube, from, to, target(0.0, 10.0)))
        assertNotNull(VehicleCollisionSweep.cast(cube, from, to, target(5.0, 5.0)))
    }

    @Test
    fun `compound gaps remain empty during a sweep`() {
        val shapes = listOf(-3.0, 3.0).map { VehicleCollisionBoxDefinition(Vector3d(1.0), Vector3d(it, 0.0, 0.0)) }
        assertNull(VehicleCollisionSweep.cast(shapes, pose(), pose(z = 10.0), target(0.0, 5.0)))
        assertNotNull(VehicleCollisionSweep.cast(shapes, pose(), pose(z = 10.0), target(3.0, 5.0)))
    }

    @Test
    fun `rotation hits an intermediate contact absent from both endpoint boxes`() {
        val rod = listOf(VehicleCollisionBoxDefinition(Vector3d(8.0, 0.4, 0.4), Vector3d()))
        val from = pose()
        val to = pose(yaw = Math.PI * 0.5)
        val target = target(2.7, -2.7)
        assertNull(VehicleCollisionSweep.cast(rod, from, from, target))
        assertNull(VehicleCollisionSweep.cast(rod, to, to, target))
        val hit = VehicleCollisionSweep.cast(rod, from, to, target)!!
        assertTrue(hit.fraction in 0.35..0.6)
        assertNull(VehicleCollisionSweep.cast(rod, from, to, target(2.7, 2.7)))
    }

    @Test
    fun `moving projectile and vehicle collide between endpoints and preserve common velocity`() {
        val from = pose(-5.0, 5.0)
        val to = pose(5.0, 5.0)
        val movement = Vector3d(0.0, 0.0, 10.0)
        val hit = VehicleCollisionSweep.cast(cube, from, to, target(0.0, 0.0), movement)!!
        assertEquals(0.44, hit.fraction, 1.0e-8)
        val boost = Vector3d(20.0, 0.0, 13.0)
        val boostedTo = pose(5.0 + boost.x, 5.0 + boost.z)
        val boosted = VehicleCollisionSweep.cast(cube, from, boostedTo, target(0.0, 0.0), movement.add(boost))!!
        assertEquals(hit.fraction, boosted.fraction, 1.0e-8)
    }
}
