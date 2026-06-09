package org.valkyrienskies.skyriders.content

import org.joml.Vector3d

data class WheeledVehiclePhysicsConfig(
    val mass: Double,
    val collisionBoxSize: Vector3d,
    val collisionBoxOffset: Vector3d,
    val axles: List<WheelAxleConfig>,
    val wheelSweepTime: Double = 0.06,
    val wheelTopSpeed: Double,
    val maxSteerLowSpeedRad: Double,
    val maxSteerHighSpeedRad: Double,
    val steeringHighSpeedStart: Double,
    val steeringFullSpeed: Double,
    val steerSmoothingTime: Double = 0.1,
    val tireFrictionCoefficient: Double = 1.25,
    val lateralSlipShape: Double = 0.35,
    val longitudinalSlipShape: Double = 0.28,
    val motorResponse: Double = 14.0,
    val brakeResponse: Double = 22.0,
    val wheelInertiaMassScale: Double = 0.018,
    val rollingResistance: Double = 24.0,
    val speedLimitSoftness: Double = 0.2,
    val uprightStrength: Double,
    val uprightDamping: Double,
    val yawAssist: Double,
    val yawAssistMinSpeed: Double,
    val yawAssistMaxSpeed: Double,
    val groundedGraceTime: Double = 0.1,
    val groundNormalSmoothingTime: Double = 0.12,
    val parkingBrakeStrength: Double = 4.5,
    val maxStepHeight: Double = 0.85,
    val stepAssistStrength: Double = 6500.0
) {
    init {
        require(axles.isNotEmpty()) { "Wheeled vehicles require at least one axle." }
    }

    companion object {
        val ATV = WheeledVehiclePhysicsConfig(
            mass = 285.0,
            collisionBoxSize = Vector3d(1.05, 0.62, 1.55),
            collisionBoxOffset = Vector3d(0.0, 0.28, -0.04),
            axles = listOf(
                WheelAxleConfig(
                    id = "front",
                    localZ = 0.68,
                    halfTrackWidth = 0.48,
                    localY = -0.16,
                    wheelRadius = 0.29,
                    wheelWidth = 0.2,
                    driven = true,
                    driveBias = 0.46,
                    steerable = true,
                    steerScale = 1.0,
                    brakeBias = 0.58,
                    suspensionRestLength = 0.25,
                    suspensionTravel = 0.34,
                    suspensionStrength = 31000.0,
                    suspensionDamping = 4300.0,
                    lateralGrip = 0.92,
                    longitudinalGrip = 1.08,
                    stepAssist = true
                ),
                WheelAxleConfig(
                    id = "rear",
                    localZ = -0.68,
                    halfTrackWidth = 0.48,
                    localY = -0.16,
                    wheelRadius = 0.29,
                    wheelWidth = 0.2,
                    driven = true,
                    driveBias = 0.54,
                    steerable = false,
                    brakeBias = 0.42,
                    suspensionRestLength = 0.25,
                    suspensionTravel = 0.34,
                    suspensionStrength = 31000.0,
                    suspensionDamping = 4300.0,
                    lateralGrip = 0.88,
                    longitudinalGrip = 1.12,
                    stepAssist = true
                )
            ),
            wheelSweepTime = 0.06,
            wheelTopSpeed = 17.0,
            maxSteerLowSpeedRad = Math.toRadians(34.0),
            maxSteerHighSpeedRad = Math.toRadians(18.0),
            steeringHighSpeedStart = 5.0,
            steeringFullSpeed = 16.0,
            steerSmoothingTime = 0.11,
            tireFrictionCoefficient = 1.28,
            lateralSlipShape = 0.32,
            longitudinalSlipShape = 0.26,
            motorResponse = 14.0,
            brakeResponse = 24.0,
            rollingResistance = 22.0,
            uprightStrength = 8600.0,
            uprightDamping = 1500.0,
            yawAssist = 2100.0,
            yawAssistMinSpeed = 0.7,
            yawAssistMaxSpeed = 15.0,
            maxStepHeight = 1.5,
            stepAssistStrength = 6400.0
        )
    }
}

data class WheelAxleConfig(
    val id: String,
    val localZ: Double,
    val halfTrackWidth: Double,
    val localY: Double,
    val wheelRadius: Double,
    val wheelWidth: Double,
    val driven: Boolean,
    val driveBias: Double,
    val steerable: Boolean,
    val steerScale: Double = 1.0,
    val brakeBias: Double,
    val suspensionRestLength: Double,
    val suspensionTravel: Double,
    val suspensionStrength: Double,
    val suspensionDamping: Double,
    val lateralGrip: Double,
    val longitudinalGrip: Double,
    val stepAssist: Boolean = false
)

data class WheeledVehicleRuntimeState(
    var engineOn: Boolean = false,
    var debugSpeed: Double = 0.0,
    var debugGroundedWheels: Int = 0,
    var debugSteerRad: Double = 0.0,
    var debugThrottle: Double = 0.0,
    var debugForwardSpeed: Double = 0.0,
    var debugLateralSlip: Double = 0.0,
    var smoothedSteerRad: Double = 0.0,
    var groundedGraceTimeRemaining: Double = 0.0,
    val smoothedGroundNormal: Vector3d = Vector3d(0.0, 1.0, 0.0),
    val wheelSpinById: MutableMap<String, Double> = HashMap(),
    val wheelAngularVelocityById: MutableMap<String, Double> = HashMap(),
    val wheelSuspensionOffsetById: MutableMap<String, Double> = HashMap(),
    var frontWheelSpin: Double = 0.0,
    var rearWheelSpin: Double = 0.0,
    var frontWheelAngularVelocity: Double = 0.0,
    var rearWheelAngularVelocity: Double = 0.0,
    var frontWheelSuspensionOffset: Double = 0.0,
    var rearWheelSuspensionOffset: Double = 0.0
)
