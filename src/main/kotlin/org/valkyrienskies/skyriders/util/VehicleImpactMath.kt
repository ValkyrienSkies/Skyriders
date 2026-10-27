package org.valkyrienskies.skyriders.util

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.properties.BodyKinematics
import kotlin.math.abs
import kotlin.math.sqrt

internal object VehicleImpactMath {
    fun normalSpeed(relativeVelocity: Vector3dc, normal: Vector3dc): Double {
        val lengthSquared = normal.lengthSquared()
        if (!lengthSquared.isFinite() || lengthSquared < 1.0e-12) return 0.0
        val speed = abs(relativeVelocity.dot(normal)) / sqrt(lengthSquared)
        return if (speed.isFinite()) speed else 0.0
    }

    fun pointVelocity(kinematics: BodyKinematics?, point: Vector3dc): Vector3d {
        if (kinematics == null) return Vector3d()
        return Vector3d(kinematics.angularVelocity)
            .cross(Vector3d(point).sub(kinematics.position))
            .add(kinematics.velocity)
    }
}
