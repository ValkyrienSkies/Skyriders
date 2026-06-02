package org.valkyrienskies.skyriders.util

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody

object VehiclePhysicsMath {
    const val MIN_DIRECTION_LENGTH_SQUARED = 1.0e-9
    const val MAX_FORCE_MAGNITUDE = 1.0e7
    const val MAX_TORQUE_MAGNITUDE = 1.0e7

    fun transformDirection(body: PhysVsBody, local: Vector3dc, fallback: Vector3dc): Vector3d {
        return safeNormalize(body.kinematics.transform.rotation.transform(Vector3d(local)), fallback)
    }

    fun projectOntoPlane(vector: Vector3dc, normal: Vector3dc, fallback: Vector3dc): Vector3d {
        val projected = Vector3d(vector).sub(Vector3d(normal).mul(safeDot(vector, normal)))
        return safeNormalize(projected, fallback)
    }

    fun velocityAtPoint(body: PhysVsBody, point: Vector3dc): Vector3d {
        val radius = Vector3d(point).sub(body.kinematics.position)
        val velocity = Vector3d(body.kinematics.angularVelocity).cross(radius).add(body.kinematics.velocity)
        return if (isFinite(velocity)) velocity else Vector3d()
    }

    fun safeNormalize(vector: Vector3dc, fallback: Vector3dc): Vector3d {
        if (!isFinite(vector) || vector.lengthSquared() < MIN_DIRECTION_LENGTH_SQUARED) {
            return Vector3d(fallback)
        }
        val normalized = Vector3d(vector).normalize()
        return if (isFinite(normalized)) normalized else Vector3d(fallback)
    }

    fun safeDot(a: Vector3dc, b: Vector3dc): Double {
        if (!isFinite(a) || !isFinite(b)) return 0.0
        val dot = a.dot(b)
        return if (dot.isFinite()) dot else 0.0
    }

    fun safeApplyWorldForce(
        body: PhysVsBody,
        force: Vector3dc,
        position: Vector3dc,
        maxForce: Double = MAX_FORCE_MAGNITUDE
    ) {
        if (!isFinite(force) || !isFinite(position)) return
        if (force.lengthSquared() > maxForce * maxForce) return
        body.applyWorldForce(force, position)
    }

    fun safeApplyWorldTorque(
        body: PhysVsBody,
        torque: Vector3dc,
        maxTorque: Double = MAX_TORQUE_MAGNITUDE
    ) {
        if (!isFinite(torque)) return
        if (torque.lengthSquared() > maxTorque * maxTorque) return
        body.applyWorldTorque(torque)
    }

    fun isFinite(vector: Vector3dc): Boolean {
        return vector.x().isFinite() && vector.y().isFinite() && vector.z().isFinite()
    }
}
