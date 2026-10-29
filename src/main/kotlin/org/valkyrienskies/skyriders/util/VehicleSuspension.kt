package org.valkyrienskies.skyriders.util

import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody

object VehicleSuspension {
    fun assistGrade(body: PhysVsBody, ground: PhysVsBody?, point: Vector3dc, normal: Vector3dc,
                    massShare: Double, forceLimit: Double): Double {
        if (ground == null) return 0.0
        val force = Vector3d(0.0, 1.0, 0.0).fma(-normal.y(), normal)
            .mul((massShare * 9.81).coerceAtMost(forceLimit))
        VehiclePhysicsMath.safeApplyWorldForce(body, force, point)
        if (!ground.isStatic) VehiclePhysicsMath.safeApplyWorldForce(ground, Vector3d(force).negate(), point)
        return force.length()
    }

    data class Spring(
        val ground: PhysVsBody,
        val point: Vector3dc,
        val normal: Vector3dc,
        val down: Vector3dc,
        val relativeVelocity: Vector3dc,
        val compression: Double,
        val stiffness: Double,
        val damping: Double,
        val preload: Double = 0.0,
        val allowRebound: Boolean = false,
        val supported: Boolean = true
    )

    private class Response(val body: PhysVsBody) {
        val inverseMass = 1.0 / body.inertiaData.mass
        val inverseInertia = Matrix3d(body.inertiaData.inertiaTensor).invert()
        val inverseRotation = Quaterniond(body.kinematics.rotation).conjugate()

        fun moment(spring: Spring, direction: Vector3dc): Vector3d = inverseRotation.transform(
            Vector3d(spring.point).sub(body.kinematics.position).cross(direction))

        fun coupling(first: Spring, second: Spring): Double =
            first.normal.dot(second.normal) * inverseMass +
                moment(first, first.normal).dot(inverseInertia.transform(moment(second, second.normal)))
    }

    fun apply(body: PhysVsBody, springs: List<Spring?>, dt: Double): DoubleArray {
        val forces = DoubleArray(springs.size)
        if (!dt.isFinite() || dt <= 0.0 || body.isStatic) return forces
        val active = springs.indices.filter { springs[it] != null }
        if (active.isEmpty()) return forces
        val chassis = Response(body)
        val supports = active.mapNotNull { springs[it]!!.ground.takeUnless(PhysVsBody::isStatic) }
            .distinctBy(PhysVsBody::id).associate { it.id to Response(it) }
        val matrix = Array(active.size) { DoubleArray(active.size) }
        val target = DoubleArray(active.size)
        for ((row, index) in active.withIndex()) {
            val spring = springs[index]!!
            val alignment = -spring.normal.dot(spring.down).coerceAtMost(-0.28)
            val response = spring.damping + spring.stiffness * dt
            if (response <= 0.0) continue
            target[row] = (spring.stiffness * spring.compression + spring.preload) * alignment / response -
                spring.relativeVelocity.dot(spring.normal) +
                if (spring.ground.isStatic) 9.81 * spring.normal.y() * dt else 0.0
            for ((column, otherIndex) in active.withIndex()) {
                val other = springs[otherIndex]!!
                matrix[row][column] = chassis.coupling(spring, other) +
                    if (spring.ground.id == other.ground.id) supports[spring.ground.id]?.coupling(spring, other) ?: 0.0 else 0.0
            }
            matrix[row][row] += alignment * alignment / (dt * response)
        }
        val impulses = DoubleArray(active.size)
        repeat(64) {
            for (row in active.indices) {
                val diagonal = matrix[row][row]
                if (!diagonal.isFinite() || diagonal <= 0.0) continue
                var residual = target[row]
                for (column in active.indices) if (column != row) residual -= matrix[row][column] * impulses[column]
                val limit = VehiclePhysicsMath.MAX_FORCE_MAGNITUDE * dt
                val spring = springs[active[row]]!!
                impulses[row] = (residual / diagonal).coerceIn(if (spring.allowRebound) -limit else 0.0,
                    if (spring.supported) limit else 0.0)
            }
        }
        for ((row, index) in active.withIndex()) {
            val spring = springs[index]!!
            val force = Vector3d(spring.normal).mul(impulses[row] / dt)
            VehiclePhysicsMath.safeApplyWorldForce(body, force, spring.point)
            if (!spring.ground.isStatic) VehiclePhysicsMath.safeApplyWorldForce(spring.ground, Vector3d(force).negate(), spring.point)
            forces[index] = (impulses[row] / dt).coerceAtLeast(0.0)
        }
        return forces
    }
}
