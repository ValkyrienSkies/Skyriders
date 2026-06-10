package org.valkyrienskies.skyriders.util

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.skyriders.content.VehicleInput
import org.valkyrienskies.skyriders.content.WheelAxleConfig
import org.valkyrienskies.skyriders.content.WheeledVehiclePhysicsConfig
import org.valkyrienskies.skyriders.content.WheeledVehicleRuntimeState
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

object WheeledVehiclePhysicsSolver {
    private val WORLD_UP = Vector3d(0.0, 1.0, 0.0)
    private val LOCAL_FORWARD = Vector3d(0.0, 0.0, 1.0)
    private val LOCAL_RIGHT = Vector3d(1.0, 0.0, 0.0)
    private val LOCAL_UP = Vector3d(0.0, 1.0, 0.0)
    private const val MAX_FORCE = VehiclePhysicsMath.MAX_FORCE_MAGNITUDE
    private const val WHEEL_AIR_DRAG = 0.35
    private const val WHEEL_GROUND_DRAG = 0.04
    private const val MAX_WHEEL_TOP_SPEED_MULTIPLIER = 4.0
    private const val LATERAL_SAMPLE_TIE_BREAK_BIAS = 0.02
    private const val SWEEP_SAMPLE_TIE_BREAK_BIAS = 0.005
    private const val MIN_STEP_ASSIST_RISE = 0.12

    fun updatePhysics(
        body: PhysVsBody,
        physLevel: PhysLevel,
        input: VehicleInput,
        config: WheeledVehiclePhysicsConfig,
        state: WheeledVehicleRuntimeState,
        dt: Double
    ) {
        val forward = VehiclePhysicsMath.transformDirection(body, LOCAL_FORWARD, LOCAL_FORWARD)
        val right = VehiclePhysicsMath.transformDirection(body, LOCAL_RIGHT, LOCAL_RIGHT)
        val up = VehiclePhysicsMath.transformDirection(body, LOCAL_UP, LOCAL_UP)
        val activeInput = if (input.riderPresent) input else VehicleInput.EMPTY
        val forwardSpeed = VehiclePhysicsMath.safeDot(body.kinematics.velocity, forward)
        val contactUp = VehiclePhysicsMath.safeNormalize(state.smoothedGroundNormal, WORLD_UP)
        val driftSpeed = planarSpeed(body, contactUp)
        val driftGripActive = updateDriftState(state, activeInput, driftSpeed, dt, config)
        updateDriftBoost(body, state, activeInput.riderPresent, driftGripActive, forward, config, dt)
        val driveCommand = updateTransmission(activeInput, forwardSpeed, config, state, dt)
        val steerRad = updateSteerAngle(state, activeInput.steer, forwardSpeed, config, dt)
        val wheels = expandWheels(config)
        val contacts = wheels.map { wheel ->
            val axleSteer = if (wheel.axle.steerable) steerRad * wheel.axle.steerScale else 0.0
            WheelContactState(wheel, sampleWheel(body, physLevel, wheel, contactUp, axleSteer, config, dt))
        }
        val grounded = contacts.filter { it.contact.grounded }
        if (grounded.isNotEmpty()) {
            state.groundedGraceTimeRemaining = config.groundedGraceTime
        } else {
            state.groundedGraceTimeRemaining = max(0.0, state.groundedGraceTimeRemaining - dt)
        }
        val stabilizedGrounded = grounded.isNotEmpty() || state.groundedGraceTimeRemaining > 0.0

        val loaded = contacts.map { contactState ->
            LoadedWheelContact(
                wheel = contactState.wheel,
                contact = contactState.contact,
                normalForce = applySuspension(body, contactState, config)
            )
        }
        if (grounded.isNotEmpty()) {
            smoothGroundNormal(state, loaded, forward, right, dt, config)
        }
        val terrainUp = VehiclePhysicsMath.safeNormalize(state.smoothedGroundNormal, WORLD_UP)
        state.debugDriveWork = 0.0
        state.debugStepAssistWork = 0.0

        loaded.forEach { applyLateralGrip(body, it, driftGripActive, config) }
        val parkingBrakeActive = state.parkingBrakeEngaged || activeInput.handbrake > 0.0
        if (activeInput.riderPresent) {
            updateWheelLongitudinalPhysics(body, loaded, driveCommand, driftGripActive, config, state, dt)
            if (parkingBrakeActive) {
                applyParkingBrake(body, loaded, terrainUp, config)
            }
            if (stabilizedGrounded) {
                if (state.drifting) {
                    applyDriftAssist(body, activeInput, forwardSpeed, terrainUp, state, config)
                } else {
                    applySteeringAssist(body, steerRad, forwardSpeed, terrainUp, config)
                }
                val activeStepWheels = loaded.count { it.contact.grounded && it.wheel.axle.stepAssist }.coerceAtLeast(1)
                loaded.forEach { state.debugStepAssistWork += applyStepAssist(body, physLevel, it, forward, terrainUp, driveCommand, config, activeStepWheels) }
            }
        } else {
            if (state.parkingBrakeEngaged) {
                applyParkingBrake(body, loaded, terrainUp, config)
            }
            updateWheelLongitudinalPhysics(body, loaded, driveCommand, driftGripActive, config, state, dt)
        }

        if (stabilizedGrounded) {
            applyUpright(body, up, terrainUp, driftGripActive, config)
        }

        updateDebugState(body, state, loaded, activeInput, steerRad, forwardSpeed)
        updateVisualAverages(state, wheels, config)
    }

    private fun expandWheels(config: WheeledVehiclePhysicsConfig): List<WheelInstance> {
        return config.axles.flatMapIndexed { axleIndex, axle ->
            listOf(
                WheelInstance("${axle.id}_left", axleIndex, -1.0, Vector3d(-axle.halfTrackWidth, axle.localY, axle.localZ), axle),
                WheelInstance("${axle.id}_right", axleIndex, 1.0, Vector3d(axle.halfTrackWidth, axle.localY, axle.localZ), axle)
            )
        }
    }

    private fun updateTransmission(
        input: VehicleInput,
        forwardSpeed: Double,
        config: WheeledVehiclePhysicsConfig,
        state: WheeledVehicleRuntimeState,
        dt: Double
    ): DriveCommand {
        val transmission = config.transmission
        val maxForwardGear = transmission.forwardGears.size
        state.transmissionShiftCooldown = max(0.0, state.transmissionShiftCooldown - dt.coerceIn(0.0, 0.1))
        state.transmissionGear = state.transmissionGear.coerceIn(-1, maxForwardGear)
        if (state.transmissionGear == 0 && transmission.automatic) {
            state.transmissionGear = 1
        }

        val rawThrottle = input.throttle.coerceIn(-1.0, 1.0)
        var brake = input.brake.coerceIn(0.0, 1.0).coerceAtLeast(input.handbrake.coerceIn(0.0, 1.0))
        if (state.parkingBrakeEngaged) {
            brake = 1.0
        }
        if (!input.riderPresent) {
            updateEngineRpm(
                input = input,
                forwardSpeed = forwardSpeed,
                driveThrottle = 0.0,
                gear = state.transmissionGear,
                gearConfig = null,
                topSpeed = config.wheelTopSpeed,
                config = config,
                state = state,
                dt = dt
            )
            state.debugTransmissionGear = state.transmissionGear
            state.debugParkingBrake = state.parkingBrakeEngaged
            return DriveCommand(
                throttle = 0.0,
                brake = brake,
                wheelTopSpeed = config.wheelTopSpeed,
                torqueMultiplier = transmission.neutralDrag,
                engineBrakeScale = 0.0
            )
        }

        var driveThrottle = 0.0
        if (transmission.automatic) {
            if (rawThrottle < -0.05) {
                if (abs(forwardSpeed) <= transmission.automaticReverseSpeedThreshold || state.transmissionGear == -1) {
                    state.transmissionGear = -1
                    driveThrottle = rawThrottle
                } else {
                    brake = brake.coerceAtLeast(-rawThrottle)
                }
            } else {
                if (state.transmissionGear < 1) {
                    state.transmissionGear = 1
                    state.transmissionShiftCooldown = transmission.shiftCooldownSeconds
                }
                if (rawThrottle > 0.05) {
                    updateAutomaticForwardGear(forwardSpeed, transmission.forwardGears, state, transmission.shiftCooldownSeconds)
                    driveThrottle = rawThrottle
                }
            }
        } else {
            if (rawThrottle < -0.05) {
                brake = brake.coerceAtLeast(-rawThrottle)
            } else if (rawThrottle > 0.05) {
                driveThrottle = when {
                    state.transmissionGear > 0 -> rawThrottle
                    state.transmissionGear < 0 -> -rawThrottle
                    else -> 0.0
                }
            }
        }

        val gear = state.transmissionGear
        val gearConfig = transmission.forwardGears.getOrNull(gear - 1)
        val topSpeed = when {
            gear > 0 && gearConfig != null -> gearConfig.maxSpeed
            gear < 0 -> transmission.reverseTopSpeed
            else -> config.wheelTopSpeed
        }
        val engineDrive = updateEngineRpm(
            input = input,
            forwardSpeed = forwardSpeed,
            driveThrottle = driveThrottle,
            gear = gear,
            gearConfig = gearConfig,
            topSpeed = topSpeed,
            config = config,
            state = state,
            dt = dt
        )
        val baseTorque = when {
            gear > 0 && gearConfig != null -> gearConfig.torqueMultiplier
            gear < 0 -> transmission.reverseTorqueMultiplier
            else -> transmission.neutralDrag
        }
        state.debugTransmissionGear = gear
        state.debugParkingBrake = state.parkingBrakeEngaged
        return DriveCommand(
            throttle = driveThrottle,
            brake = brake,
            wheelTopSpeed = topSpeed,
            torqueMultiplier = baseTorque * engineDrive.torqueScale,
            engineBrakeScale = engineDrive.engineBrakeScale
        )
    }

    private fun updateAutomaticForwardGear(
        forwardSpeed: Double,
        gears: List<org.valkyrienskies.skyriders.content.VehicleTransmissionGearConfig>,
        state: WheeledVehicleRuntimeState,
        shiftCooldownSeconds: Double
    ) {
        if (state.transmissionShiftCooldown > 0.0 || state.transmissionGear < 1) return
        val speed = abs(forwardSpeed)
        val gearIndex = (state.transmissionGear - 1).coerceIn(0, gears.lastIndex)
        val gear = gears[gearIndex]
        when {
            state.transmissionGear < gears.size && speed >= gear.upshiftSpeed -> {
                state.transmissionGear += 1
                state.transmissionShiftCooldown = shiftCooldownSeconds
            }
            state.transmissionGear > 1 && speed <= gear.downshiftSpeed -> {
                state.transmissionGear -= 1
                state.transmissionShiftCooldown = shiftCooldownSeconds
            }
        }
    }

    private fun gearLaunchTorqueFactor(
        gear: org.valkyrienskies.skyriders.content.VehicleTransmissionGearConfig,
        forwardSpeed: Double
    ): Double {
        val launchScale = gear.launchTorqueScale.coerceIn(0.0, 1.0)
        val speedT = smoothstep(0.0, max(gear.downshiftSpeed, gear.maxSpeed * 0.35), abs(forwardSpeed))
        return lerp(launchScale, 1.0, speedT).coerceIn(0.0, 1.0)
    }

    private fun updateEngineRpm(
        input: VehicleInput,
        forwardSpeed: Double,
        driveThrottle: Double,
        gear: Int,
        gearConfig: org.valkyrienskies.skyriders.content.VehicleTransmissionGearConfig?,
        topSpeed: Double,
        config: WheeledVehiclePhysicsConfig,
        state: WheeledVehicleRuntimeState,
        dt: Double
    ): EngineDriveState {
        val engine = config.engine
        val stepDt = dt.coerceIn(0.0, 0.1)
        if (!state.engineOn) {
            state.engineRpm = lerpByResponse(state.engineRpm, 0.0, engine.freeRevResponse, stepDt)
            state.clutchEngagement = 0.0
            state.debugEngineRpm = state.engineRpm
            state.debugClutchEngagement = 0.0
            state.debugEngineStalled = state.engineStalled
            return EngineDriveState(torqueScale = 0.0, engineBrakeScale = 0.0)
        }
        state.engineStalled = false

        val throttle = abs(driveThrottle).coerceIn(0.0, 1.0)
        val freeTargetRpm = if (throttle > 0.0) {
            lerp(engine.idleRpm, engine.revLimiterRpm, throttle)
        } else {
            engine.idleRpm
        }
        val coupledRpm = coupledEngineRpm(forwardSpeed, gear, gearConfig, topSpeed, config, state)
        val launchScale = when {
            gear > 0 && gearConfig != null -> gearLaunchTorqueFactor(gearConfig, forwardSpeed)
            gear < 0 -> 0.85
            else -> 0.0
        }
        val clutchAssistRpm = throttle * engine.idleRpm * (0.45 + launchScale * 0.9)
        val automaticClutchTarget = when {
            gear == 0 -> 0.0
            throttle > 0.0 -> max(
                launchScale * 0.18,
                smoothstep(engine.stallRpm, engine.idleRpm * 1.15, coupledRpm + clutchAssistRpm)
            )
            abs(forwardSpeed) > 0.5 -> smoothstep(engine.idleRpm, engine.redlineRpm * 0.25, coupledRpm) * 0.45
            else -> 0.0
        }.coerceIn(0.0, 1.0)
        val rawClutchTarget = if (config.transmission.manualClutch) {
            if (gear == 0) 0.0 else 1.0 - input.clutch.coerceIn(0.0, 1.0)
        } else {
            automaticClutchTarget
        }
        val clutchResponse = if (config.transmission.manualClutch) 4.6 else engine.coupledRevResponse
        val clutchTarget = lerpByResponse(state.clutchEngagement, rawClutchTarget, clutchResponse, stepDt).coerceIn(0.0, 1.0)

        val loadedTargetRpm = if (config.transmission.manualClutch) coupledRpm else max(coupledRpm, engine.stallRpm)
        val targetRpm = if (clutchTarget > 0.0) {
            lerp(freeTargetRpm, loadedTargetRpm, clutchTarget * 0.85)
        } else {
            freeTargetRpm
        }
        val response = lerp(engine.freeRevResponse, engine.coupledRevResponse, clutchTarget)
        state.engineRpm = lerpByResponse(
            state.engineRpm.takeIf { it.isFinite() && it >= 0.0 } ?: engine.idleRpm,
            targetRpm.coerceIn(0.0, engine.revLimiterRpm * 1.05),
            response,
            stepDt
        )
        state.clutchEngagement = clutchTarget
        state.debugEngineRpm = state.engineRpm
        state.debugClutchEngagement = clutchTarget
        state.debugEngineStalled = false

        if (shouldStallEngine(config, state.engineRpm, gear, clutchTarget)) {
            state.engineOn = false
            state.engineStalled = true
            state.engineRpm = 0.0
            state.clutchEngagement = 0.0
            state.debugEngineRpm = 0.0
            state.debugClutchEngagement = 0.0
            state.debugEngineStalled = true
            return EngineDriveState(torqueScale = 0.0, engineBrakeScale = 0.0)
        }

        val limiterScale = 1.0 - smoothstep(engine.redlineRpm, engine.revLimiterRpm, state.engineRpm)
        val stallScale = smoothstep(engine.stallRpm * 0.75, engine.idleRpm, state.engineRpm)
        val torqueScale = sampleEngineTorque(engine, state.engineRpm) * clutchTarget * limiterScale * stallScale
        val engineBrakeScale = if (gear != 0 && throttle < 0.05 && abs(forwardSpeed) > 0.35) {
            engine.engineBrakeTorqueScale * clutchTarget * smoothstep(0.35, 4.0, abs(forwardSpeed))
        } else {
            0.0
        }
        return EngineDriveState(
            torqueScale = torqueScale.coerceIn(0.0, 1.5),
            engineBrakeScale = engineBrakeScale.coerceIn(0.0, 1.0)
        )
    }

    private fun coupledEngineRpm(
        forwardSpeed: Double,
        gear: Int,
        gearConfig: org.valkyrienskies.skyriders.content.VehicleTransmissionGearConfig?,
        topSpeed: Double,
        config: WheeledVehiclePhysicsConfig,
        state: WheeledVehicleRuntimeState
    ): Double {
        if (gear == 0) return 0.0
        val transmission = config.transmission
        val gearRatio = when {
            gear > 0 -> gearConfig?.gearRatio
            gear < 0 -> transmission.reverseGearRatio
            else -> null
        }
        drivenWheelCoupledRpm(config, state, gearRatio)?.let { return it }
        if (gearRatio != null && gearRatio > 0.0 && transmission.finalDriveRatio > 0.0) {
            val radius = averageDrivenWheelRadius(config).coerceAtLeast(0.05)
            val wheelRpm = abs(forwardSpeed) / radius * 60.0 / (2.0 * PI)
            return (wheelRpm * gearRatio * transmission.finalDriveRatio).coerceIn(0.0, config.engine.revLimiterRpm * 1.1)
        }
        return if (topSpeed > 1.0) {
            (abs(forwardSpeed) / topSpeed * config.engine.redlineRpm).coerceIn(0.0, config.engine.revLimiterRpm)
        } else {
            0.0
        }
    }

    private fun drivenWheelCoupledRpm(
        config: WheeledVehiclePhysicsConfig,
        state: WheeledVehicleRuntimeState,
        gearRatio: Double?
    ): Double? {
        val transmission = config.transmission
        if (gearRatio == null || gearRatio <= 0.0 || transmission.finalDriveRatio <= 0.0) return null
        val drivenAxles = config.axles.filter(WheelAxleConfig::driven)
        if (drivenAxles.isEmpty()) return null

        val wheelSpeeds = drivenAxles.flatMap { axle ->
            listOf("${axle.id}_left", "${axle.id}_right").mapNotNull { wheelId ->
                state.wheelAngularVelocityById[wheelId]?.takeIf(Double::isFinite)?.let(::abs)
            }
        }
        if (wheelSpeeds.isEmpty()) return null

        val averageWheelRpm = wheelSpeeds.average() * 60.0 / (2.0 * PI)
        return (averageWheelRpm * gearRatio * transmission.finalDriveRatio).coerceIn(0.0, config.engine.revLimiterRpm * 1.1)
    }

    private fun averageDrivenWheelRadius(config: WheeledVehiclePhysicsConfig): Double {
        val driven = config.axles.filter(WheelAxleConfig::driven)
        val axles = driven.ifEmpty { config.axles }
        return axles.sumOf(WheelAxleConfig::wheelRadius) / axles.size
    }

    private fun shouldStallEngine(
        config: WheeledVehiclePhysicsConfig,
        engineRpm: Double,
        gear: Int,
        clutchEngagement: Double
    ): Boolean {
        if (!config.transmission.manualClutch || gear == 0 || clutchEngagement < 0.68) return false
        return engineRpm < config.engine.stallRpm
    }

    private fun sampleEngineTorque(
        engine: org.valkyrienskies.skyriders.content.VehicleEngineConfig,
        rpm: Double
    ): Double {
        val curve = engine.torqueCurve.sortedBy { it.rpm }
        if (curve.isEmpty()) return 1.0
        if (rpm <= curve.first().rpm) return curve.first().torqueScale
        if (rpm >= curve.last().rpm) return curve.last().torqueScale
        val upperIndex = curve.indexOfFirst { rpm <= it.rpm }
        val lower = curve[upperIndex - 1]
        val upper = curve[upperIndex]
        val t = ((rpm - lower.rpm) / (upper.rpm - lower.rpm).coerceAtLeast(1.0e-6)).coerceIn(0.0, 1.0)
        return lerp(lower.torqueScale, upper.torqueScale, t)
    }

    private fun sampleWheel(
        body: PhysVsBody,
        physLevel: PhysLevel,
        wheel: WheelInstance,
        contactUp: Vector3d,
        steerRad: Double,
        config: WheeledVehiclePhysicsConfig,
        dt: Double
    ): VehicleWheelContact {
        val mountWorld = body.kinematics.transform.toWorld.transformPosition(Vector3d(wheel.localPos))
        val suspensionUp = VehiclePhysicsMath.safeNormalize(contactUp, WORLD_UP)
        val castDir = Vector3d(suspensionUp).negate()
        val axle = wheel.axle
        val maxLength = axle.suspensionRestLength + axle.suspensionTravel + axle.wheelRadius
        val droopGroundedRange = axle.suspensionTravel * config.suspensionDroopGroundedFraction.coerceIn(0.0, 1.0)
        val groundedMaxDistance = axle.suspensionRestLength + axle.wheelRadius + droopGroundedRange
        val bodyForward = VehiclePhysicsMath.transformDirection(body, LOCAL_FORWARD, LOCAL_FORWARD)
        val baseForward = VehiclePhysicsMath.projectOntoPlane(bodyForward, suspensionUp, bodyForward)
        val wheelForward = if (steerRad != 0.0) {
            VehiclePhysicsMath.safeNormalize(
                Vector3d(baseForward).rotateAxis(steerRad, suspensionUp.x, suspensionUp.y, suspensionUp.z),
                baseForward
            )
        } else {
            baseForward
        }
        val wheelRight = VehiclePhysicsMath.safeNormalize(
            Vector3d(suspensionUp).cross(wheelForward),
            VehiclePhysicsMath.transformDirection(body, LOCAL_RIGHT, LOCAL_RIGHT)
        )
        val sweepOffset = Vector3d(body.kinematics.velocity)
            .sub(Vector3d(suspensionUp).mul(VehiclePhysicsMath.safeDot(body.kinematics.velocity, suspensionUp)))
            .mul(dt.coerceIn(0.0, config.wheelSweepTime))
        val sweepSamples = if (VehiclePhysicsMath.isFinite(sweepOffset) && sweepOffset.lengthSquared() > 1.0e-6) {
            listOf(Vector3d(), Vector3d(sweepOffset).mul(-0.5), Vector3d(sweepOffset).mul(-1.0), Vector3d(sweepOffset).mul(0.3))
        } else {
            listOf(Vector3d())
        }
        val lateralOffsets = listOf(0.0, -axle.wheelWidth * 0.5, axle.wheelWidth * 0.5)
        val best = lateralOffsets.flatMap { lateralOffset ->
            val base = Vector3d(mountWorld).fma(lateralOffset, wheelRight)
            sweepSamples.map { sweep ->
                WheelSample(Vector3d(base).add(sweep), abs(lateralOffset), sweep.length())
            }
        }.map { sample ->
            WheelSampleHit(
                contact = VehicleWheelPhysics.sampleRaycastWheel(
                    body = body,
                    physLevel = physLevel,
                    mountWorld = sample.position,
                    suspensionDirWorld = castDir,
                    maxLength = maxLength,
                    wheelForwardWorld = wheelForward,
                    wheelRightWorld = wheelRight,
                    groundedMaxDistance = groundedMaxDistance
                ),
                lateralOffset = sample.lateralOffset,
                sweepOffset = sample.sweepOffset
            )
        }.filter { it.contact.grounded }
            .minByOrNull { it.contact.hitDistance + it.lateralOffset * LATERAL_SAMPLE_TIE_BREAK_BIAS + it.sweepOffset * SWEEP_SAMPLE_TIE_BREAK_BIAS }

        return best?.contact ?: VehicleWheelPhysics.sampleRaycastWheel(
            body = body,
            physLevel = physLevel,
            mountWorld = mountWorld,
            suspensionDirWorld = castDir,
            maxLength = maxLength,
            wheelForwardWorld = wheelForward,
            wheelRightWorld = wheelRight,
            groundedMaxDistance = groundedMaxDistance
        )
    }

    private fun applySuspension(
        body: PhysVsBody,
        contactState: WheelContactState,
        config: WheeledVehiclePhysicsConfig
    ): Double {
        val contact = contactState.contact
        val axle = contactState.wheel.axle
        if (!contact.grounded) return 0.0
        val springLength = contact.hitDistance - axle.wheelRadius
        val compression = axle.suspensionRestLength - springLength
        val compressed = compression.coerceIn(0.0, axle.suspensionTravel)
        val droop = (springLength - axle.suspensionRestLength).coerceAtLeast(0.0)
        val droopRange = axle.suspensionTravel * config.suspensionDroopGroundedFraction.coerceIn(0.0, 1.0)
        val droopLoad = if (compressed <= 0.0 && droopRange > 1.0e-4) {
            val droopT = (droop / droopRange).coerceIn(0.0, 1.0)
            val preload = config.mass * 9.81 * 0.25 * config.suspensionDroopPreloadFraction.coerceIn(0.0, 1.0)
            preload * (1.0 - droopT)
        } else {
            0.0
        }
        if (compressed <= 0.0 && droopLoad <= 0.0) return 0.0
        val suspensionUp = VehiclePhysicsMath.safeNormalize(Vector3d(contact.suspensionDirWorld).negate(), WORLD_UP)
        val springVelocity = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.suspensionDirWorld)
        val dampingScale = if (compressed > 0.0) 1.0 else 0.35
        val forceMag = (compressed * axle.suspensionStrength + droopLoad + springVelocity * axle.suspensionDamping * dampingScale).coerceAtLeast(0.0)
        val normalForce = forceMag.coerceIn(0.0, MAX_FORCE)
        VehicleWheelPhysics.applyContactForce(body, contact, Vector3d(suspensionUp).mul(normalForce))
        return normalForce
    }

    private fun applyLateralGrip(
        body: PhysVsBody,
        loaded: LoadedWheelContact,
        drifting: Boolean,
        config: WheeledVehiclePhysicsConfig
    ) {
        val contact = loaded.contact
        if (!contact.grounded || loaded.normalForce <= 0.0) return
        val lateralSpeed = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.wheelRightWorld)
        val forwardSpeed = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.wheelForwardWorld)
        val slip = lateralSpeed / max(abs(forwardSpeed), 1.5)
        val shapedSlip = slip / (abs(slip) + config.lateralSlipShape.coerceAtLeast(1.0e-4))
        val driftGripScale = if (!drifting) {
            1.0
        } else if (loaded.wheel.axle.steerable) {
            config.driftSteerableGripScale
        } else {
            config.driftNonSteerableGripScale
        }
        val maxForce = loaded.normalForce *
            config.tireFrictionCoefficient *
            contact.surfaceFriction *
            loaded.wheel.axle.lateralGrip *
            driftGripScale.coerceAtLeast(0.0)
        VehicleWheelPhysics.applyContactForce(
            body,
            contact,
            Vector3d(contact.wheelRightWorld).mul((-shapedSlip * maxForce).coerceIn(-MAX_FORCE, MAX_FORCE))
        )
    }

    private fun updateWheelLongitudinalPhysics(
        body: PhysVsBody,
        contacts: List<LoadedWheelContact>,
        driveCommand: DriveCommand,
        drifting: Boolean,
        config: WheeledVehiclePhysicsConfig,
        state: WheeledVehicleRuntimeState,
        dt: Double
    ) {
        val throttle = driveCommand.throttle.coerceIn(-1.0, 1.0)
        val brake = if (drifting) {
            driveCommand.brake.coerceIn(0.0, 1.0) * config.driftBrakeScale.coerceIn(0.0, 1.0)
        } else {
            driveCommand.brake.coerceIn(0.0, 1.0)
        }
        val driveBiasTotal = config.axles.filter(WheelAxleConfig::driven).sumOf { it.driveBias.coerceAtLeast(0.0) }.coerceAtLeast(1.0e-6)
        val brakeBiasTotal = config.axles.sumOf { it.brakeBias.coerceAtLeast(0.0) }.coerceAtLeast(1.0e-6)
        val stepDt = dt.coerceIn(0.0, 0.1)

        contacts.forEach { loaded ->
            val axle = loaded.wheel.axle
            val radius = max(axle.wheelRadius, 0.05)
            val topSpeed = max(
                driveCommand.wheelTopSpeed * if (drifting) config.driftTopSpeedMultiplier.coerceAtLeast(0.1) else 1.0,
                1.0
            )
            val maxOmega = topSpeed * MAX_WHEEL_TOP_SPEED_MULTIPLIER / radius
            val groundSpeed = if (loaded.contact.grounded) {
                VehiclePhysicsMath.safeDot(loaded.contact.wheelVelocityWorld, loaded.contact.wheelForwardWorld)
            } else {
                0.0
            }
            var omega = state.wheelAngularVelocityById[loaded.wheel.id]?.takeIf(Double::isFinite) ?: groundSpeed / radius
            val driveShare = if (axle.driven) axle.driveBias.coerceAtLeast(0.0) / driveBiasTotal / 2.0 else 0.0
            val brakeShare = axle.brakeBias.coerceAtLeast(0.0) / brakeBiasTotal / 2.0

            if (axle.driven && throttle != 0.0) {
                val motorTarget = throttle * topSpeed * 1.08 / radius
                val motorAlpha = 1.0 - exp(-stepDt * config.motorResponse * driveCommand.torqueMultiplier * abs(throttle) * (0.5 + driveShare * 2.0))
                omega = lerp(omega, motorTarget, motorAlpha)
            }
            if (brake > 0.0) {
                val brakeAlpha = 1.0 - exp(-stepDt * config.brakeResponse * brake * (0.5 + brakeShare * 2.0))
                omega = lerp(omega, 0.0, brakeAlpha)
            }

            val drag = if (loaded.contact.grounded) WHEEL_GROUND_DRAG else WHEEL_AIR_DRAG
            omega *= exp(-drag * stepDt)

            if (loaded.contact.grounded && loaded.normalForce > 0.0) {
                val surfaceSpeed = omega * radius
                val slipVelocity = surfaceSpeed - groundSpeed
                val slip = slipVelocity / max(abs(groundSpeed), 1.5)
                val shapedSlip = slip / (abs(slip) + config.longitudinalSlipShape.coerceAtLeast(1.0e-4))
                val driveForceScale = if (axle.driven && abs(throttle) > 0.0) {
                    driveCommand.torqueMultiplier.coerceIn(0.0, 1.75) *
                        if (drifting) config.driftDriveScale.coerceAtLeast(0.0) else 1.0
                } else {
                    0.35
                }
                val forceScale = if (brake > 0.0) 1.0 else driveForceScale
                val gripLimit = loaded.normalForce * config.tireFrictionCoefficient * loaded.contact.surfaceFriction * axle.longitudinalGrip
                val maxForce = gripLimit * forceScale
                val engineBrakeForce = if (axle.driven && brake <= 0.0 && abs(throttle) < 0.05 && driveCommand.engineBrakeScale > 0.0 && abs(groundSpeed) > 0.1) {
                    -groundSpeed.signOrZero() * gripLimit * driveCommand.engineBrakeScale
                } else {
                    0.0
                }
                val rollingForce = (-groundSpeed * config.rollingResistance / contacts.size).coerceIn(-MAX_FORCE, MAX_FORCE)
                val driveForceMag = shapedSlip * maxForce
                if (axle.driven && throttle != 0.0) {
                    val drivePower = driveForceMag * VehiclePhysicsMath.safeDot(loaded.contact.wheelVelocityWorld, loaded.contact.wheelForwardWorld)
                    if (drivePower > 0.0) {
                        state.debugDriveWork += drivePower / max(config.mass, 1.0)
                    }
                }
                val forceMag = (driveForceMag + engineBrakeForce + rollingForce).coerceIn(-MAX_FORCE, MAX_FORCE)
                VehicleWheelPhysics.applyContactForce(body, loaded.contact, Vector3d(loaded.contact.wheelForwardWorld).mul(forceMag))

                val wheelInertia = max(0.08, config.mass * radius * radius * config.wheelInertiaMassScale)
                val reactionDelta = (forceMag * radius / wheelInertia * stepDt).coerceIn(-maxOmega * 0.35, maxOmega * 0.35)
                omega -= reactionDelta

                if (abs(slipVelocity) < 0.4) {
                    omega = lerp(omega, groundSpeed / radius, 1.0 - exp(-stepDt * 12.0))
                }
            }

            omega = omega.coerceIn(-maxOmega, maxOmega)
            state.wheelAngularVelocityById[loaded.wheel.id] = omega
            val visualOmega = if (loaded.contact.grounded) {
                (groundSpeed / radius).coerceIn(-maxOmega, maxOmega)
            } else {
                omega
            }
            state.visualWheelAngularVelocityById[loaded.wheel.id] = visualOmega
            state.wheelSpinById[loaded.wheel.id] = (state.wheelSpinById[loaded.wheel.id] ?: 0.0) + visualOmega * stepDt
            state.wheelSuspensionOffsetById[loaded.wheel.id] = visualSuspensionOffset(loaded)
        }
    }

    private fun applyParkingBrake(
        body: PhysVsBody,
        contacts: List<LoadedWheelContact>,
        terrainUp: Vector3d,
        config: WheeledVehiclePhysicsConfig
    ) {
        val grounded = contacts.filter { it.contact.grounded && it.normalForce > 0.0 }
        if (grounded.isEmpty()) return
        val velocity = body.kinematics.velocity
        val planar = Vector3d(velocity).fma(-VehiclePhysicsMath.safeDot(velocity, terrainUp), terrainUp)
        if (!VehiclePhysicsMath.isFinite(planar) || planar.lengthSquared() < 0.025 * 0.025) return
        val maxBrakeForce = grounded.sumOf { it.normalForce * it.contact.surfaceFriction } *
            config.tireFrictionCoefficient *
            config.parkingBrakeStrength
        val brakeForce = VehiclePhysicsMath.safeNormalize(planar, Vector3d())
            .mul((-planar.length() * config.mass * config.parkingBrakeStrength).coerceIn(-maxBrakeForce, 0.0))
        VehiclePhysicsMath.safeApplyWorldForce(body, brakeForce, body.kinematics.position)
    }

    private fun applySteeringAssist(
        body: PhysVsBody,
        steerRad: Double,
        forwardSpeed: Double,
        terrainUp: Vector3d,
        config: WheeledVehiclePhysicsConfig
    ) {
        val steer = (steerRad / config.maxSteerLowSpeedRad).coerceIn(-1.0, 1.0)
        val speed = abs(forwardSpeed)
        if (abs(steer) < 1.0e-4 || speed < 0.05) return
        val speedT = smoothstep(config.yawAssistMinSpeed, config.yawAssistMaxSpeed, speed)
        val torque = Vector3d(terrainUp).mul(steer * forwardSpeed.signOrZero() * config.yawAssist * speedT)
        VehiclePhysicsMath.safeApplyWorldTorque(body, torque)
    }

    private fun applyDriftAssist(
        body: PhysVsBody,
        input: VehicleInput,
        forwardSpeed: Double,
        terrainUp: Vector3d,
        state: WheeledVehicleRuntimeState,
        config: WheeledVehiclePhysicsConfig
    ) {
        val speed = abs(forwardSpeed)
        if (speed < config.yawAssistMinSpeed || abs(state.driftDirection) < 0.05) return

        val steerBias = input.steer.coerceIn(-1.0, 1.0) * state.driftDirection
        val biasScale = when {
            steerBias > 0.15 -> 1.05
            steerBias < -0.15 -> 0.28
            else -> 0.58
        }
        val speedT = smoothstep(config.driftMinSpeed, config.yawAssistMaxSpeed, speed)
        val highSpeedSoftening = lerp(1.0, 0.68, smoothstep(config.yawAssistMaxSpeed * 0.7, config.yawAssistMaxSpeed * 1.35, speed))
        val torque = Vector3d(terrainUp).mul(
            state.driftDirection * forwardSpeed.signOrZero() * config.driftYawAssist * speedT * biasScale * highSpeedSoftening
        )
        VehiclePhysicsMath.safeApplyWorldTorque(body, torque)
    }

    private fun applyUpright(
        body: PhysVsBody,
        up: Vector3d,
        terrainUp: Vector3d,
        drifting: Boolean,
        config: WheeledVehiclePhysicsConfig
    ) {
        val axis = Vector3d(up).cross(terrainUp)
        if (!VehiclePhysicsMath.isFinite(axis) || axis.lengthSquared() < VehiclePhysicsMath.MIN_DIRECTION_LENGTH_SQUARED) return
        val strengthScale = if (drifting) config.driftUprightStrengthScale else 1.0
        val dampingScale = if (drifting) config.driftUprightDampingScale else 1.0
        val correction = axis.mul(config.uprightStrength * strengthScale)
        val damping = Vector3d(body.kinematics.angularVelocity).mul(-config.uprightDamping * dampingScale)
        VehiclePhysicsMath.safeApplyWorldTorque(body, correction.add(damping))
    }

    private fun applyStepAssist(
        body: PhysVsBody,
        physLevel: PhysLevel,
        loaded: LoadedWheelContact,
        forward: Vector3d,
        terrainUp: Vector3d,
        driveCommand: DriveCommand,
        config: WheeledVehiclePhysicsConfig,
        activeStepWheels: Int
    ): Double {
        val contact = loaded.contact
        val axle = loaded.wheel.axle
        if (!axle.stepAssist || !contact.grounded || config.maxStepHeight <= 0.0 || config.stepAssistStrength <= 0.0) return 0.0
        val driveDirection = driveCommand.throttle.coerceIn(-1.0, 1.0)
        val terrainForward = computeStepApproachDirection(body, forward, terrainUp, driveDirection)
        if (terrainForward.lengthSquared() < 1.0e-6) return 0.0
        val speedIntoStep = max(0.0, VehiclePhysicsMath.safeDot(body.kinematics.velocity, terrainForward))
        val projectedForward = VehiclePhysicsMath.projectOntoPlane(forward, terrainUp, forward)
        val throttleIntoStep = max(0.0, driveDirection * VehiclePhysicsMath.safeDot(projectedForward, terrainForward))
        val crawlAmount = throttleIntoStep * (1.0 - smoothstep(0.8, 3.5, speedIntoStep))
        val effectiveSpeed = max(speedIntoStep, crawlAmount * 1.8)
        if (effectiveSpeed < 0.35) return 0.0

        val speedLookahead = smoothstep(3.0, config.wheelTopSpeed * 0.85, effectiveSpeed)
        val probeLength = (axle.wheelRadius + 0.45 + effectiveSpeed * 0.09)
            .coerceIn(axle.wheelRadius + 0.35, axle.wheelRadius + 1.75)
        val lowProbeStart = Vector3d(contact.contactPointWorld)
            .fma(axle.wheelRadius * 0.45, terrainUp)
            .fma(0.06 + speedLookahead * 0.24, terrainForward)
        val obstacle = physLevel.rayCast(lowProbeStart, terrainForward, probeLength, body.id) ?: return 0.0
        if (obstacle.hitBody.id == body.id) return 0.0
        val step = findStepLandingSurface(physLevel, body, contact, lowProbeStart, obstacle.distance, terrainForward, terrainUp, axle, config, speedLookahead) ?: return 0.0

        val heightAmount = smoothstep(0.12, config.maxStepHeight, step.rise)
        val approachDistance = max(0.25, step.approachDistance)
        val targetUpSpeed = (effectiveSpeed * step.rise / approachDistance * (0.55 + speedLookahead * 0.25))
            .coerceIn(0.0, 2.4 + speedLookahead * 2.8)
        val currentUpSpeed = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, terrainUp)
        val missingUpSpeed = max(0.0, targetUpSpeed - currentUpSpeed)
        val liftDemand = if (targetUpSpeed > 1.0e-4) {
            (missingUpSpeed / targetUpSpeed).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val wheelShare = (2.0 / activeStepWheels.toDouble()).coerceIn(0.35, 1.0)
        val velocityLiftForce = config.mass * missingUpSpeed * (12.0 + speedLookahead * 12.0)
        val crawlLiftForce = config.stepAssistStrength * crawlAmount * (0.22 + heightAmount * 0.16)
        val baseLiftForce = config.stepAssistStrength * (0.22 + heightAmount * 0.2) * liftDemand
        val maxLiftForce = min(
            config.mass * (28.0 + speedLookahead * 40.0),
            config.stepAssistStrength * (0.85 + heightAmount * 0.55 + speedLookahead * 1.65)
        )
        val lift = Vector3d(terrainUp)
            .mul((baseLiftForce + velocityLiftForce + crawlLiftForce).coerceIn(0.0, maxLiftForce) * wheelShare)

        val speedLimitScale = 1.0 - smoothstep(config.wheelTopSpeed, config.wheelTopSpeed * 1.18, speedIntoStep)
        val obstacleProximityScale = 1.0 - smoothstep(probeLength * 0.45, probeLength, obstacle.distance)
        val crawlCarryForce = config.stepAssistStrength * crawlAmount * (0.18 + heightAmount * 0.08)
        val rawCarryForce = config.stepAssistStrength *
            (0.025 + speedLookahead * 0.18 + heightAmount * 0.05) *
            speedLimitScale.coerceIn(0.0, 1.0) * obstacleProximityScale.coerceIn(0.0, 1.0) + crawlCarryForce
        val maxCarryForce = config.mass * (1.8 + speedLookahead * 3.8 + heightAmount * 1.2)
        val carry = Vector3d(terrainForward).mul(rawCarryForce.coerceIn(0.0, maxCarryForce) * wheelShare)

        val assistForce = lift.add(carry)
        VehicleWheelPhysics.applyContactForce(body, contact, assistForce)
        return assistForce.length() / max(config.mass, 1.0)
    }

    private fun findStepLandingSurface(
        physLevel: PhysLevel,
        body: PhysVsBody,
        contact: VehicleWheelContact,
        lowProbeStart: Vector3d,
        obstacleDistance: Double,
        terrainForward: Vector3d,
        terrainUp: Vector3d,
        axle: WheelAxleConfig,
        config: WheeledVehiclePhysicsConfig,
        speedLookahead: Double
    ): StepOpportunity? {
        if (!obstacleDistance.isFinite()) return null
        val down = Vector3d(terrainUp).negate()
        val stepProbeHeight = config.maxStepHeight + axle.wheelRadius * 1.2
        val downProbeLength = stepProbeHeight + axle.wheelRadius * 1.35
        val forwardDistances = listOf(
            obstacleDistance + axle.wheelRadius * 0.45,
            obstacleDistance + 0.35 + speedLookahead * 0.15,
            obstacleDistance + 0.65 + speedLookahead * 0.65
        )
        return forwardDistances.asSequence()
            .filter { it.isFinite() && it > 0.0 }
            .mapNotNull { distance ->
                val topProbeStart = Vector3d(lowProbeStart).fma(distance, terrainForward).fma(stepProbeHeight, terrainUp)
                val topHit = physLevel.rayCast(topProbeStart, down, downProbeLength, body.id) ?: return@mapNotNull null
                if (topHit.hitBody.id == body.id || !topHit.distance.isFinite()) return@mapNotNull null
                val topPoint = Vector3d(topProbeStart).fma(topHit.distance, down)
                val rise = VehiclePhysicsMath.safeDot(Vector3d(topPoint).sub(contact.contactPointWorld), terrainUp)
                if (rise < MIN_STEP_ASSIST_RISE || rise > config.maxStepHeight + axle.wheelRadius * 0.25) return@mapNotNull null
                if (VehiclePhysicsMath.safeDot(topHit.hitNormal, terrainUp) < 0.48) return@mapNotNull null
                StepOpportunity(rise, distance)
            }
            .minByOrNull(StepOpportunity::rise)
    }

    private fun computeStepApproachDirection(body: PhysVsBody, forward: Vector3d, terrainUp: Vector3d, throttle: Double): Vector3d {
        val projectedForward = VehiclePhysicsMath.projectOntoPlane(forward, terrainUp, forward)
        val velocity = body.kinematics.velocity
        if (!VehiclePhysicsMath.isFinite(velocity)) return throttleStepDirection(projectedForward, throttle)
        val planarVelocity = Vector3d(velocity).fma(-VehiclePhysicsMath.safeDot(velocity, terrainUp), terrainUp)
        if (planarVelocity.lengthSquared() < 0.35 * 0.35) return throttleStepDirection(projectedForward, throttle)
        val velocityDirection = VehiclePhysicsMath.safeNormalize(planarVelocity, projectedForward)
        val alignment = VehiclePhysicsMath.safeDot(velocityDirection, projectedForward)
        if (abs(alignment) < 0.25) return Vector3d()
        val axisDirection = Vector3d(projectedForward).mul(if (alignment < 0.0) -1.0 else 1.0)
        val velocityWeight = smoothstep(2.0, 12.0, planarVelocity.length()) * 0.65
        return VehiclePhysicsMath.safeNormalize(Vector3d(axisDirection).mul(1.0 - velocityWeight).fma(velocityWeight, velocityDirection), axisDirection)
    }

    private fun throttleStepDirection(projectedForward: Vector3d, throttle: Double): Vector3d {
        return when {
            throttle > 0.05 -> Vector3d(projectedForward)
            throttle < -0.05 -> Vector3d(projectedForward).negate()
            else -> Vector3d()
        }
    }

    private fun smoothGroundNormal(
        state: WheeledVehicleRuntimeState,
        contacts: List<LoadedWheelContact>,
        bodyForward: Vector3d,
        bodyRight: Vector3d,
        dt: Double,
        config: WheeledVehiclePhysicsConfig
    ) {
        val grounded = contacts.filter { it.contact.grounded }
        if (grounded.isEmpty()) return
        val normalAverage = Vector3d()
        val loadBearing = grounded.filter { it.normalForce > 1.0e-4 }
        val normalContacts = loadBearing.ifEmpty { grounded }
        normalContacts.forEach { contact ->
            normalAverage.fma(contact.supportWeight(), contact.contact.contactNormalWorld)
        }
        var rawNormal = VehiclePhysicsMath.safeNormalize(normalAverage, state.smoothedGroundNormal)
        computeSupportGroundNormal(contacts, bodyForward, bodyRight, rawNormal)?.let { supportNormal ->
            rawNormal = VehiclePhysicsMath.safeNormalize(Vector3d(rawNormal).mul(0.7).fma(0.3, supportNormal), rawNormal)
        }
        val smoothingTime = config.groundNormalSmoothingTime.takeIf { it.isFinite() && it > 1.0e-4 } ?: 0.12
        val alpha = 1.0 - exp(-dt.coerceIn(0.0, 0.1) / smoothingTime)
        state.smoothedGroundNormal.set(VehiclePhysicsMath.safeNormalize(Vector3d(state.smoothedGroundNormal).lerp(rawNormal, alpha), WORLD_UP))
    }

    private fun computeSupportGroundNormal(
        contacts: List<LoadedWheelContact>,
        bodyForward: Vector3d,
        bodyRight: Vector3d,
        fallbackNormal: Vector3d
    ): Vector3d? {
        val hitContacts = contacts
            .filter { it.normalForce > 1.0e-4 }
            .filter { it.contact.hitBody != null && VehiclePhysicsMath.isFinite(it.contact.contactPointWorld) }
        if (hitContacts.size < 3) return null
        val terrainForward = VehiclePhysicsMath.projectOntoPlane(bodyForward, fallbackNormal, bodyForward)
        val stableRight = VehiclePhysicsMath.safeNormalize(Vector3d(fallbackNormal).cross(terrainForward), bodyRight)
        val frontZ = hitContacts.maxOf { it.wheel.axle.localZ }
        val rearZ = hitContacts.minOf { it.wheel.axle.localZ }
        val front = weightedAveragePoint(hitContacts.filter { it.wheel.axle.localZ == frontZ }) ?: return null
        val rear = weightedAveragePoint(hitContacts.filter { it.wheel.axle.localZ == rearZ }) ?: return null
        val left = weightedAveragePoint(hitContacts.filter { it.wheel.side < 0.0 })
        val right = weightedAveragePoint(hitContacts.filter { it.wheel.side > 0.0 })
        val forwardSpan = Vector3d(front).sub(rear)
        val rightSpan = if (left != null && right != null) {
            Vector3d(right).sub(left)
        } else {
            Vector3d(stableRight)
        }
        if (forwardSpan.lengthSquared() < 1.0e-6) return null
        if (rightSpan.lengthSquared() < 1.0e-6) rightSpan.set(stableRight)
        val support = Vector3d(forwardSpan).cross(rightSpan)
        if (!VehiclePhysicsMath.isFinite(support) || support.lengthSquared() < 1.0e-6) return null
        if (VehiclePhysicsMath.safeDot(support, fallbackNormal) < 0.0) support.negate()
        val normal = VehiclePhysicsMath.safeNormalize(support, fallbackNormal)
        return normal.takeIf { VehiclePhysicsMath.safeDot(it, fallbackNormal) > 0.48 }
    }

    private fun weightedAveragePoint(contacts: List<LoadedWheelContact>): Vector3d? {
        if (contacts.isEmpty()) return null
        val totalWeight = contacts.sumOf { it.supportWeight() }
        if (!totalWeight.isFinite() || totalWeight <= 1.0e-6) return null
        val point = Vector3d()
        contacts.forEach { contact ->
            point.fma(contact.supportWeight(), contact.contact.contactPointWorld)
        }
        return point.div(totalWeight)
    }

    private fun LoadedWheelContact.supportWeight(): Double {
        return normalForce.takeIf { it.isFinite() && it > 1.0e-4 } ?: 1.0
    }

    private fun updateSteerAngle(
        state: WheeledVehicleRuntimeState,
        steerInput: Double,
        forwardSpeed: Double,
        config: WheeledVehiclePhysicsConfig,
        dt: Double
    ): Double {
        val speedT = smoothstep(config.steeringHighSpeedStart, config.steeringFullSpeed, abs(forwardSpeed))
        val maxSteer = lerp(config.maxSteerLowSpeedRad, config.maxSteerHighSpeedRad, speedT)
        val target = steerInput.coerceIn(-1.0, 1.0) * maxSteer
        val smoothing = config.steerSmoothingTime.takeIf { it.isFinite() && it > 1.0e-4 } ?: 0.1
        val alpha = 1.0 - exp(-dt.coerceIn(0.0, 0.1) / smoothing)
        state.smoothedSteerRad = lerp(state.smoothedSteerRad, target, alpha)
        if (target == 0.0 && abs(state.smoothedSteerRad) < 0.002) state.smoothedSteerRad = 0.0
        return state.smoothedSteerRad
    }

    private fun updateDebugState(
        body: PhysVsBody,
        state: WheeledVehicleRuntimeState,
        contacts: List<LoadedWheelContact>,
        input: VehicleInput,
        steerRad: Double,
        forwardSpeed: Double
    ) {
        val velocity = body.kinematics.velocity
        state.debugSpeed = if (VehiclePhysicsMath.isFinite(velocity)) velocity.length() else 0.0
        state.debugForwardSpeed = if (forwardSpeed.isFinite()) forwardSpeed else 0.0
        state.debugGroundedWheels = contacts.count { it.contact.grounded }
        state.debugSteerRad = steerRad
        state.debugThrottle = input.throttle
        val grounded = contacts.filter { it.contact.grounded }
        state.debugLateralSlip = if (grounded.isEmpty()) {
            0.0
        } else {
            grounded.sumOf {
                val lateral = VehiclePhysicsMath.safeDot(it.contact.wheelVelocityWorld, it.contact.wheelRightWorld)
                val forward = VehiclePhysicsMath.safeDot(it.contact.wheelVelocityWorld, it.contact.wheelForwardWorld)
                lateral / max(abs(forward), 1.5)
            } / grounded.size
        }
    }

    private fun updateVisualAverages(
        state: WheeledVehicleRuntimeState,
        wheels: List<WheelInstance>,
        config: WheeledVehiclePhysicsConfig
    ) {
        val frontZ = config.axles.maxOf(WheelAxleConfig::localZ)
        val rearZ = config.axles.minOf(WheelAxleConfig::localZ)
        val frontWheels = wheels.filter { it.axle.localZ == frontZ }
        val rearWheels = wheels.filter { it.axle.localZ == rearZ }
        state.frontWheelSpin = averageByWheel(frontWheels, state.wheelSpinById)
        state.rearWheelSpin = averageByWheel(rearWheels, state.wheelSpinById)
        state.frontWheelAngularVelocity = averageByWheel(frontWheels, state.visualWheelAngularVelocityById)
        state.rearWheelAngularVelocity = averageByWheel(rearWheels, state.visualWheelAngularVelocityById)
        state.frontWheelSuspensionOffset = averageByWheel(frontWheels, state.wheelSuspensionOffsetById)
        state.rearWheelSuspensionOffset = averageByWheel(rearWheels, state.wheelSuspensionOffsetById)
    }

    private fun averageByWheel(wheels: List<WheelInstance>, values: Map<String, Double>): Double {
        if (wheels.isEmpty()) return 0.0
        return wheels.sumOf { values[it.id]?.takeIf(Double::isFinite) ?: 0.0 } / wheels.size
    }

    private fun visualSuspensionOffset(loaded: LoadedWheelContact): Double {
        if (!loaded.contact.grounded) return 0.0
        val axle = loaded.wheel.axle
        val springLength = loaded.contact.hitDistance - axle.wheelRadius
        return (axle.suspensionRestLength - springLength).coerceIn(0.0, axle.suspensionTravel)
    }

    private fun updateDriftState(
        state: WheeledVehicleRuntimeState,
        input: VehicleInput,
        driftSpeed: Double,
        dt: Double,
        config: WheeledVehiclePhysicsConfig
    ): Boolean {
        if (!config.driftEnabled) {
            clearDriftState(state)
            return false
        }

        val brake = input.brake.coerceIn(0.0, 1.0).coerceAtLeast(input.handbrake.coerceIn(0.0, 1.0))
        val steer = input.steer.coerceIn(-1.0, 1.0)
        val speed = abs(driftSpeed)

        if (brake <= 0.05 || speed < config.driftMinSpeed) {
            if (state.drifting) {
                state.driftExitTimeRemaining = config.driftExitSmoothingTime
            }
            state.drifting = false
            state.driftDirection = 0.0
            state.driftExitTimeRemaining = max(0.0, state.driftExitTimeRemaining - dt)
            return state.driftExitTimeRemaining > 0.0
        }

        if (!state.drifting) {
            if (abs(steer) < config.driftStartSteer) return false
            state.driftDirection = steer.signOrZero()
        }

        state.drifting = true
        state.driftExitTimeRemaining = 0.0
        return true
    }

    private fun updateDriftBoost(
        body: PhysVsBody,
        state: WheeledVehicleRuntimeState,
        riderPresent: Boolean,
        drifting: Boolean,
        forward: Vector3d,
        config: WheeledVehiclePhysicsConfig,
        dt: Double
    ) {
        if (!config.driftEnabled || !config.driftBoostEnabled || !riderPresent) {
            state.driftBoostCharge = 0.0
            state.driftBoostLevel = 0
            state.driftBoostTimeRemaining = 0.0
            state.driftBoostForce = 0.0
            return
        }

        if (drifting && state.drifting) {
            state.driftBoostCharge += dt
            state.driftBoostLevel = computeDriftBoostLevel(state.driftBoostCharge, config)
            state.driftBoostTimeRemaining = 0.0
            state.driftBoostForce = 0.0
        } else if (!drifting && state.driftBoostCharge > 0.0) {
            val boostLevel = state.driftBoostLevel.coerceIn(0, driftBoostLevelCount(config))
            if (boostLevel > 0) {
                state.driftBoostForce = driftBoostForce(boostLevel, config)
                state.driftBoostTimeRemaining = driftBoostDuration(boostLevel, config)
            }
            state.driftBoostCharge = 0.0
            state.driftBoostLevel = 0
        }

        if (state.driftBoostTimeRemaining <= 0.0 || state.driftBoostForce <= 0.0) return

        VehiclePhysicsMath.safeApplyWorldForce(
            body,
            Vector3d(forward).mul(state.driftBoostForce),
            body.kinematics.position
        )
        state.driftBoostTimeRemaining = max(0.0, state.driftBoostTimeRemaining - dt)
        if (state.driftBoostTimeRemaining <= 0.0) {
            state.driftBoostForce = 0.0
        }
    }

    private fun clearDriftState(state: WheeledVehicleRuntimeState) {
        state.drifting = false
        state.driftDirection = 0.0
        state.driftExitTimeRemaining = 0.0
        state.driftBoostCharge = 0.0
        state.driftBoostLevel = 0
        state.driftBoostTimeRemaining = 0.0
        state.driftBoostForce = 0.0
    }

    private fun computeDriftBoostLevel(charge: Double, config: WheeledVehiclePhysicsConfig): Int {
        var level = 0
        val maxLevel = driftBoostLevelCount(config)
        for (index in 0 until maxLevel) {
            if (charge >= config.driftBoostChargeTimes[index]) {
                level = index + 1
            }
        }
        return level
    }

    private fun driftBoostForce(level: Int, config: WheeledVehiclePhysicsConfig): Double {
        val index = level - 1
        return if (index in config.driftBoostForces.indices) config.driftBoostForces[index] else 0.0
    }

    private fun driftBoostDuration(level: Int, config: WheeledVehiclePhysicsConfig): Double {
        val index = level - 1
        return if (index in config.driftBoostDurations.indices) config.driftBoostDurations[index] else 0.0
    }

    private fun driftBoostLevelCount(config: WheeledVehiclePhysicsConfig): Int {
        return min(
            config.driftBoostMaxLevel,
            min(config.driftBoostChargeTimes.size, min(config.driftBoostForces.size, config.driftBoostDurations.size))
        ).coerceAtLeast(0)
    }

    private fun planarSpeed(body: PhysVsBody, terrainUp: Vector3d): Double {
        val velocity = body.kinematics.velocity
        if (!VehiclePhysicsMath.isFinite(velocity)) return 0.0
        val vertical = VehiclePhysicsMath.safeDot(velocity, terrainUp)
        val planar = Vector3d(velocity).fma(-vertical, terrainUp)
        return if (VehiclePhysicsMath.isFinite(planar)) planar.length() else 0.0
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        if (edge0 == edge1) return if (value < edge0) 0.0 else 1.0
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun lerp(from: Double, to: Double, alpha: Double): Double {
        return from + (to - from) * alpha.coerceIn(0.0, 1.0)
    }

    private fun lerpByResponse(from: Double, to: Double, response: Double, dt: Double): Double {
        val alpha = 1.0 - exp(-dt.coerceIn(0.0, 0.1) * response.coerceAtLeast(0.0))
        return lerp(from, to, alpha)
    }

    private fun Double.signOrZero(): Double {
        return when {
            this > 0.0 -> 1.0
            this < 0.0 -> -1.0
            else -> 0.0
        }
    }

    private data class WheelInstance(
        val id: String,
        val axleIndex: Int,
        val side: Double,
        val localPos: Vector3d,
        val axle: WheelAxleConfig
    )

    private data class WheelContactState(
        val wheel: WheelInstance,
        val contact: VehicleWheelContact
    )

    private data class LoadedWheelContact(
        val wheel: WheelInstance,
        val contact: VehicleWheelContact,
        val normalForce: Double
    )

    private data class DriveCommand(
        val throttle: Double,
        val brake: Double,
        val wheelTopSpeed: Double,
        val torqueMultiplier: Double,
        val engineBrakeScale: Double
    )

    private data class EngineDriveState(
        val torqueScale: Double,
        val engineBrakeScale: Double
    )

    private data class WheelSample(
        val position: Vector3d,
        val lateralOffset: Double,
        val sweepOffset: Double
    )

    private data class WheelSampleHit(
        val contact: VehicleWheelContact,
        val lateralOffset: Double,
        val sweepOffset: Double
    )

    private data class StepOpportunity(
        val rise: Double,
        val approachDistance: Double
    )
}
