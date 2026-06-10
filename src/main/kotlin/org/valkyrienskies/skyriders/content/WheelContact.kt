package org.valkyrienskies.skyriders.content

import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.PhysVsBody

data class WheelContact(
    val grounded: Boolean,
    val hitBody: PhysVsBody?,
    val contactPointWorld: Vector3d,
    val contactNormalWorld: Vector3d,
    val suspensionDirWorld: Vector3d,
    val hitDistance: Double,
    val compression: Double,
    val normalForceEstimate: Double,
    val surfaceFriction: Double,
    val wheelForwardWorld: Vector3d,
    val wheelRightWorld: Vector3d,
    val wheelVelocityWorld: Vector3d
)
