package org.valkyrienskies.skyriders.content

import org.joml.Vector3d

data class KartPhysicsConfig(
    val mass: Double,
    val collisionBoxSize: Vector3d,
    val collisionBoxOffset: Vector3d,
    val wheelLocalPositions: List<Vector3d>,
    val wheelRadius: Double,
    val suspensionRestLength: Double,
    val suspensionTravel: Double,
    val suspensionStrength: Double,
    val suspensionDamping: Double,
    val driveForce: Double,
    val brakeForce: Double,
    val lateralGrip: Double,
    val maxSteerRad: Double,
    val wheelTopSpeed: Double,
    val yawAssist: Double,
    val uprightStrength: Double,
    val uprightDamping: Double
) {
    companion object {
        val DEBUG_KART = KartPhysicsConfig(
            mass = 320.0,
            collisionBoxSize = Vector3d(1.25, 0.5, 1.55),
            collisionBoxOffset = Vector3d(0.0, 0.28, 0.0),
            wheelLocalPositions = listOf(
                Vector3d(-0.55, -0.12, 0.62),
                Vector3d(0.55, -0.12, 0.62),
                Vector3d(-0.55, -0.12, -0.62),
                Vector3d(0.55, -0.12, -0.62)
            ),
            wheelRadius = 0.28,
            suspensionRestLength = 0.28,
            suspensionTravel = 0.34,
            suspensionStrength = 36000.0,
            suspensionDamping = 4200.0,
            driveForce = 7200.0,
            brakeForce = 5200.0,
            lateralGrip = 5200.0,
            maxSteerRad = Math.toRadians(32.0),
            wheelTopSpeed = 16.0,
            yawAssist = 2600.0,
            uprightStrength = 12000.0,
            uprightDamping = 1900.0
        )
    }
}

data class KartRuntimeState(
    var engineOn: Boolean = false,
    var debugSpeed: Double = 0.0,
    var debugGroundedWheels: Int = 0,
    var debugSteerRad: Double = 0.0,
    var debugThrottle: Double = 0.0
)
