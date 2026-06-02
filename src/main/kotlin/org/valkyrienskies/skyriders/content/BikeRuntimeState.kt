package org.valkyrienskies.skyriders.content

import org.joml.Vector3d

data class BikeRuntimeState(
    val smoothedGroundNormal: Vector3d = Vector3d(0.0, 1.0, 0.0),
    var smoothedTargetLeanRad: Double = 0.0,
    var visualLeanRad: Double = 0.0,
    var visualSteerRad: Double = 0.0,
    var frontWheelSpin: Double = 0.0,
    var rearWheelSpin: Double = 0.0,
    var lastGrounded: Boolean = false,
    var jumpCharge: Double = 0.0,
    var wasJumpHeld: Boolean = false,
    var jumpReleaseTimeRemaining: Double = 0.0,
    val jumpReleaseForce: Vector3d = Vector3d(),
    var groundedGraceTimeRemaining: Double = 0.0,
    var wasDrifting: Boolean = false,
    var driftBoostCharge: Double = 0.0,
    var driftBoostLevel: Int = 0,
    var driftBoostTimeRemaining: Double = 0.0,
    var driftBoostForce: Double = 0.0
)
