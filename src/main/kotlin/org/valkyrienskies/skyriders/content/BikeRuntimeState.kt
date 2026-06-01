package org.valkyrienskies.skyriders.content

import org.joml.Vector3d

data class BikeRuntimeState(
    val smoothedGroundNormal: Vector3d = Vector3d(0.0, 1.0, 0.0),
    var visualLeanRad: Double = 0.0,
    var frontWheelSpin: Double = 0.0,
    var rearWheelSpin: Double = 0.0,
    var lastGrounded: Boolean = false,
    var jumpCharge: Double = 0.0,
    var wasJumpHeld: Boolean = false,
    var jumpReleaseTimeRemaining: Double = 0.0,
    val jumpReleaseForce: Vector3d = Vector3d(),
    var groundedGraceTimeRemaining: Double = 0.0
)
