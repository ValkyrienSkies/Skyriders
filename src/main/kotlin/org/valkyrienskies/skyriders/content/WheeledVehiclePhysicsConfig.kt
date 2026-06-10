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
    val suspensionDroopGroundedFraction: Double = 0.45,
    val suspensionDroopPreloadFraction: Double = 0.16,
    val parkingBrakeStrength: Double = 4.5,
    val maxStepHeight: Double = 0.85,
    val stepAssistStrength: Double = 6500.0,
    val driftEnabled: Boolean = false,
    val driftMinSpeed: Double = 5.0,
    val driftStartSteer: Double = 0.2,
    val driftSteerableGripScale: Double = 0.9,
    val driftNonSteerableGripScale: Double = 0.38,
    val driftYawAssist: Double = yawAssist * 1.35,
    val driftBrakeScale: Double = 0.0,
    val driftDriveScale: Double = 0.9,
    val driftExitSmoothingTime: Double = 0.18,
    val driftTopSpeedMultiplier: Double = 1.08,
    val driftUprightStrengthScale: Double = 1.35,
    val driftUprightDampingScale: Double = 1.25,
    val driftBoostEnabled: Boolean = false,
    val driftBoostMaxLevel: Int = 3,
    val driftBoostChargeTimes: List<Double> = listOf(0.8, 1.6, 2.6),
    val driftBoostForces: List<Double> = listOf(4200.0, 6200.0, 8400.0),
    val driftBoostDurations: List<Double> = listOf(0.3, 0.48, 0.68),
    val transmission: VehicleTransmissionConfig = VehicleTransmissionConfig.DEFAULT_AUTOMATIC,
    val engine: VehicleEngineConfig = VehicleEngineConfig.DEFAULT
) {
    init {
        require(axles.isNotEmpty()) { "Wheeled vehicles require at least one axle." }
        require(transmission.forwardGears.isNotEmpty()) { "Wheeled vehicle transmissions require at least one forward gear." }
    }

    companion object {
        val ATV = WheeledVehiclePhysicsConfig(
            mass = 285.0,
            collisionBoxSize = Vector3d(0.95, 0.70, 1.45),
            collisionBoxOffset = Vector3d(0.0, 0.25, -0.18),
            axles = listOf(
                WheelAxleConfig(
                    id = "front",
                    localZ = 0.68,
                    halfTrackWidth = 0.48,
                    localY = -0.16,
                    wheelRadius = 0.29,
                    wheelWidth = 0.24,
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
                    wheelWidth = 0.24,
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
            wheelTopSpeed = 28.0,
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
            stepAssistStrength = 8200.0,
            driftEnabled = true,
            driftMinSpeed = 4.8,
            driftStartSteer = 0.22,
            driftSteerableGripScale = 0.82,
            driftNonSteerableGripScale = 0.34,
            driftYawAssist = 3300.0,
            driftBrakeScale = 0.0,
            driftDriveScale = 0.92,
            driftExitSmoothingTime = 0.18,
            driftTopSpeedMultiplier = 1.07,
            driftUprightStrengthScale = 1.28,
            driftUprightDampingScale = 1.22,
            driftBoostEnabled = true,
            driftBoostMaxLevel = 3,
            driftBoostChargeTimes = listOf(0.78, 1.55, 2.45),
            driftBoostForces = listOf(3600.0, 5600.0, 7600.0),
            driftBoostDurations = listOf(0.28, 0.45, 0.62),
            transmission = VehicleTransmissionConfig(
                automatic = true,
                forwardGears = listOf(
                    VehicleTransmissionGearConfig(maxSpeed = 8.5, torqueMultiplier = 1.5, upshiftSpeed = 5.4),
                    VehicleTransmissionGearConfig(maxSpeed = 17.5, torqueMultiplier = 1.0, upshiftSpeed = 10.0, downshiftSpeed = 4.7),
                    VehicleTransmissionGearConfig(maxSpeed = 28.0, torqueMultiplier = 0.72, downshiftSpeed = 8.8)
                ),
                reverseTopSpeed = 7.5,
                reverseTorqueMultiplier = 0.72
            )
        )

        val CAR = WheeledVehiclePhysicsConfig(
            mass = 1180.0,
            collisionBoxSize = Vector3d(1.75, 0.72, 3.25),
            collisionBoxOffset = Vector3d(0.0, 0.34, -0.08),
            axles = listOf(
                WheelAxleConfig(
                    id = "front",
                    localZ = 1.25,
                    halfTrackWidth = 0.74,
                    localY = -0.22,
                    wheelRadius = 0.34,
                    wheelWidth = 0.24,
                    driven = false,
                    driveBias = 0.0,
                    steerable = true,
                    steerScale = 1.0,
                    brakeBias = 0.58,
                    suspensionRestLength = 0.34,
                    suspensionTravel = 0.42,
                    suspensionStrength = 62000.0,
                    suspensionDamping = 7600.0,
                    lateralGrip = 1.02,
                    longitudinalGrip = 0.95,
                    stepAssist = true
                ),
                WheelAxleConfig(
                    id = "rear",
                    localZ = -1.25,
                    halfTrackWidth = 0.74,
                    localY = -0.22,
                    wheelRadius = 0.34,
                    wheelWidth = 0.24,
                    driven = true,
                    driveBias = 1.0,
                    steerable = false,
                    brakeBias = 0.42,
                    suspensionRestLength = 0.34,
                    suspensionTravel = 0.42,
                    suspensionStrength = 65000.0,
                    suspensionDamping = 7900.0,
                    lateralGrip = 0.98,
                    longitudinalGrip = 1.08,
                    stepAssist = true
                )
            ),
            wheelSweepTime = 0.07,
            wheelTopSpeed = 24.0,
            maxSteerLowSpeedRad = Math.toRadians(31.0),
            maxSteerHighSpeedRad = Math.toRadians(12.0),
            steeringHighSpeedStart = 7.0,
            steeringFullSpeed = 24.0,
            steerSmoothingTime = 0.14,
            tireFrictionCoefficient = 1.35,
            lateralSlipShape = 0.25,
            longitudinalSlipShape = 0.22,
            motorResponse = 12.0,
            brakeResponse = 26.0,
            rollingResistance = 34.0,
            uprightStrength = 19000.0,
            uprightDamping = 3400.0,
            yawAssist = 1450.0,
            yawAssistMinSpeed = 1.0,
            yawAssistMaxSpeed = 20.0,
            groundNormalSmoothingTime = 0.16,
            parkingBrakeStrength = 5.2,
            maxStepHeight = 1.15,
            stepAssistStrength = 8200.0,
            transmission = VehicleTransmissionConfig(
                automatic = false,
                manualClutch = false,
                finalDriveRatio = 3.42,
                reverseGearRatio = 3.1,
                forwardGears = listOf(
                    VehicleTransmissionGearConfig(maxSpeed = 6.5, torqueMultiplier = 1.75, launchTorqueScale = 1.0, gearRatio = 3.25),
                    VehicleTransmissionGearConfig(maxSpeed = 12.0, torqueMultiplier = 1.18, launchTorqueScale = 0.58, gearRatio = 1.95),
                    VehicleTransmissionGearConfig(maxSpeed = 18.0, torqueMultiplier = 0.84, launchTorqueScale = 0.24, gearRatio = 1.3),
                    VehicleTransmissionGearConfig(maxSpeed = 24.0, torqueMultiplier = 0.62, launchTorqueScale = 0.08, gearRatio = 0.95)
                ),
                reverseTopSpeed = 6.0,
                reverseTorqueMultiplier = 0.72,
                shiftCooldownSeconds = 0.32
            )
        )
    }
}

data class VehicleTransmissionConfig(
    val automatic: Boolean = true,
    val manualClutch: Boolean = false,
    val forwardGears: List<VehicleTransmissionGearConfig>,
    val reverseTopSpeed: Double = 5.5,
    val reverseTorqueMultiplier: Double = 0.72,
    val neutralDrag: Double = 0.18,
    val shiftCooldownSeconds: Double = 0.28,
    val automaticReverseSpeedThreshold: Double = 0.8,
    val finalDriveRatio: Double = 1.0,
    val reverseGearRatio: Double? = null
) {
    companion object {
        val DEFAULT_AUTOMATIC = VehicleTransmissionConfig(
            automatic = true,
            forwardGears = listOf(
                VehicleTransmissionGearConfig(maxSpeed = 6.5, torqueMultiplier = 1.5, upshiftSpeed = 5.4),
                VehicleTransmissionGearConfig(maxSpeed = 11.5, torqueMultiplier = 1.0, upshiftSpeed = 10.0, downshiftSpeed = 4.7),
                VehicleTransmissionGearConfig(maxSpeed = 17.0, torqueMultiplier = 0.72, downshiftSpeed = 8.8)
            ),
            reverseTopSpeed = 5.5,
            reverseTorqueMultiplier = 0.72
        )
    }
}

data class VehicleEngineConfig(
    val idleRpm: Double = 850.0,
    val stallRpm: Double = 450.0,
    val redlineRpm: Double = 6200.0,
    val revLimiterRpm: Double = 6500.0,
    val freeRevResponse: Double = 7.5,
    val coupledRevResponse: Double = 11.0,
    val clutchStallProtection: Double = 0.42,
    val engineBrakeTorqueScale: Double = 0.26,
    val torqueCurve: List<VehicleEngineTorquePoint> = listOf(
        VehicleEngineTorquePoint(800.0, 0.55),
        VehicleEngineTorquePoint(1800.0, 0.92),
        VehicleEngineTorquePoint(3600.0, 1.0),
        VehicleEngineTorquePoint(5200.0, 0.84),
        VehicleEngineTorquePoint(6500.0, 0.35)
    )
) {
    companion object {
        val DEFAULT = VehicleEngineConfig()
    }
}

data class VehicleEngineTorquePoint(
    val rpm: Double,
    val torqueScale: Double
)

data class VehicleTransmissionGearConfig(
    val maxSpeed: Double,
    val torqueMultiplier: Double = 1.0,
    val launchTorqueScale: Double = 1.0,
    val upshiftSpeed: Double = maxSpeed * 0.82,
    val downshiftSpeed: Double = maxSpeed * 0.42,
    val gearRatio: Double? = null
)

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
    var fuelAmount: Double = Double.NaN,
    var raceParticipant: Boolean = false,
    var raceColorId: Int = -1,
    var parkingBrakeEngaged: Boolean = false,
    var transmissionGear: Int = 1,
    var transmissionShiftCooldown: Double = 0.0,
    var debugSpeed: Double = 0.0,
    var debugGroundedWheels: Int = 0,
    var debugSteerRad: Double = 0.0,
    var debugThrottle: Double = 0.0,
    var debugForwardSpeed: Double = 0.0,
    var debugTransmissionGear: Int = 1,
    var debugParkingBrake: Boolean = false,
    var debugEngineRpm: Double = 850.0,
    var debugClutchEngagement: Double = 0.0,
    var debugEngineStalled: Boolean = false,
    var debugLateralSlip: Double = 0.0,
    var debugDriveWork: Double = 0.0,
    var debugStepAssistWork: Double = 0.0,
    var drifting: Boolean = false,
    var driftDirection: Double = 0.0,
    var driftExitTimeRemaining: Double = 0.0,
    var driftBoostCharge: Double = 0.0,
    var driftBoostLevel: Int = 0,
    var driftBoostTimeRemaining: Double = 0.0,
    var driftBoostForce: Double = 0.0,
    var engineRpm: Double = 850.0,
    var clutchEngagement: Double = 0.0,
    var engineStalled: Boolean = false,
    var smoothedSteerRad: Double = 0.0,
    var groundedGraceTimeRemaining: Double = 0.0,
    val smoothedGroundNormal: Vector3d = Vector3d(0.0, 1.0, 0.0),
    val wheelSpinById: MutableMap<String, Double> = HashMap(),
    val wheelAngularVelocityById: MutableMap<String, Double> = HashMap(),
    val visualWheelAngularVelocityById: MutableMap<String, Double> = HashMap(),
    val wheelSuspensionOffsetById: MutableMap<String, Double> = HashMap(),
    val partStates: MutableMap<String, VehiclePartState> = HashMap(),
    var frontWheelSpin: Double = 0.0,
    var rearWheelSpin: Double = 0.0,
    var frontWheelAngularVelocity: Double = 0.0,
    var rearWheelAngularVelocity: Double = 0.0,
    var frontWheelSuspensionOffset: Double = 0.0,
    var rearWheelSuspensionOffset: Double = 0.0
)
