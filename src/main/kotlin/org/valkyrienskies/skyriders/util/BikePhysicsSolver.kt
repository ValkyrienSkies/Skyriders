package org.valkyrienskies.skyriders.util

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.BikePhysicsConfig
import org.valkyrienskies.skyriders.content.BikeRuntimeState
import org.valkyrienskies.skyriders.content.WheelContact
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.tanh

object BikePhysicsSolver {
    private val WORLD_UP = Vector3d(0.0, 1.0, 0.0)
    private val LOCAL_FORWARD = Vector3d(0.0, 0.0, 1.0)
    private val LOCAL_RIGHT = Vector3d(1.0, 0.0, 0.0)
    private val LOCAL_UP = Vector3d(0.0, 1.0, 0.0)
    private const val MIN_DIRECTION_LENGTH_SQUARED = 1.0e-9
    private const val MAX_FORCE_MAGNITUDE = 1.0e7
    private const val MAX_TORQUE_MAGNITUDE = 1.0e7
    private const val GROUNDED_GRACE_SECONDS = 0.12
    private const val FRONT_STEP_TRACTION_SCALE = 0.35

    fun updateBikePhysics(
        body: PhysVsBody,
        physLevel: PhysLevel,
        input: BikeInput,
        config: BikePhysicsConfig,
        state: BikeRuntimeState,
        dt: Double
    ) {
        val forward = transformDirection(body, LOCAL_FORWARD, LOCAL_FORWARD)
        val right = transformDirection(body, LOCAL_RIGHT, LOCAL_RIGHT)
        val up = transformDirection(body, LOCAL_UP, LOCAL_UP)
        val forwardSpeed = safeDot(body.kinematics.velocity, forward)
        val frontSteerRad = computeFrontSteerAngle(forwardSpeed, input.steer, config)

        val contactUp = safeNormalize(state.smoothedGroundNormal, WORLD_UP)
        val frontContact = sampleWheelContact(
            body,
            physLevel,
            config.frontWheelLocalPos,
            contactUp,
            frontSteerRad,
            config,
            dt
        )
        val rearContact = sampleWheelContact(body, physLevel, config.rearWheelLocalPos, contactUp, 0.0, config, dt)
        val contacts = listOf(frontContact, rearContact)
        val grounded = frontContact.grounded || rearContact.grounded
        if (grounded) {
            state.groundedGraceTimeRemaining = GROUNDED_GRACE_SECONDS
        } else {
            state.groundedGraceTimeRemaining = max(0.0, state.groundedGraceTimeRemaining - dt)
        }
        val stabilizedGrounded = grounded || state.groundedGraceTimeRemaining > 0.0

        if (grounded) {
            smoothGroundNormal(state, contacts, dt, config)
        }
        val terrainUp = safeNormalize(state.smoothedGroundNormal, WORLD_UP)

        applySuspension(body, frontContact, config)
        applySuspension(body, rearContact, config)
        applyTireForces(body, frontContact, config)
        applyTireForces(body, rearContact, config)
        applyDriveAndBrakes(body, frontContact, rearContact, input, config)

        val targetLean = updateSmoothedTargetLean(state, computeTargetLean(forwardSpeed, input.steer, config), dt)
        val chargingJump = input.jump > 0.0
        val balanceTargetLean = if (chargingJump) targetLean * 0.25 else targetLean
        val balanceStrengthScale = if (chargingJump) 1.35 else 1.0
        if (grounded) {
            applyJumpChargeAndRelease(body, contacts, forward, terrainUp, input, config, state, dt)
            applyPendingJumpRelease(body, contacts, state, dt)
            applyStepAssist(body, physLevel, frontContact, forward, terrainUp, input, config)
        } else {
            applyPendingJumpRelease(body, contacts, state, dt)
        }

        if (stabilizedGrounded) {
            applyBalanceController(body, forward, up, terrainUp, balanceTargetLean, config, forwardSpeed, balanceStrengthScale)
            applyLowSpeedAssist(body, input, forwardSpeed, terrainUp, config)
            applyAntiFlipAssist(body, forward, right, up, terrainUp, config)
        } else {
            applyAirborneControl(body, forward, right, up, input, config)
        }
        if (!grounded) {
            val jumpHeld = input.jump > 0.0
            if (!jumpHeld) {
                state.jumpCharge = 0.0
            }
            state.wasJumpHeld = jumpHeld
        }
        dampExtremeAngularVelocity(body, config)
        updateVisualState(state, targetLean, frontSteerRad, forwardSpeed, config, dt, grounded)

        state.lastGrounded = grounded
    }

    private fun sampleWheelContact(
        body: PhysVsBody,
        physLevel: PhysLevel,
        wheelLocalPos: Vector3d,
        contactUp: Vector3d,
        steerAngleRad: Double,
        config: BikePhysicsConfig,
        dt: Double
    ): WheelContact {
        val suspensionDirWorld = safeNormalize(contactUp, WORLD_UP)
        val castDirection = safeNormalize(Vector3d(suspensionDirWorld).negate(), WORLD_UP)
        val maxLength = config.suspensionRestLength + config.suspensionTravel + config.wheelRadius
        val bodyForward = transformDirection(body, LOCAL_FORWARD, LOCAL_FORWARD)
        val forward = projectOntoPlane(bodyForward, suspensionDirWorld, bodyForward)
        val right = safeNormalize(Vector3d(suspensionDirWorld).cross(forward), transformDirection(body, LOCAL_RIGHT, LOCAL_RIGHT))
        val wheelForward = if (steerAngleRad != 0.0) {
            safeNormalize(Vector3d(forward).rotateAxis(steerAngleRad, suspensionDirWorld.x, suspensionDirWorld.y, suspensionDirWorld.z), forward)
        } else {
            Vector3d(forward)
        }
        val wheelRight = safeNormalize(Vector3d(suspensionDirWorld).cross(wheelForward), right)
        val samples = listOf(-config.wheelWidth * 0.5, 0.0, config.wheelWidth * 0.5)
        val sweepOffset = Vector3d(body.kinematics.velocity)
            .sub(Vector3d(suspensionDirWorld).mul(safeDot(body.kinematics.velocity, suspensionDirWorld)))
            .mul(dt.coerceIn(0.0, 0.1))
        val sweepSamples = if (isFinite(sweepOffset) && sweepOffset.lengthSquared() > 1.0e-6) {
            listOf(
                Vector3d(),
                Vector3d(sweepOffset).mul(-0.5),
                Vector3d(sweepOffset).mul(-1.0),
                Vector3d(sweepOffset).mul(0.35)
            )
        } else {
            listOf(Vector3d())
        }
        val bestHit = samples
            .asSequence()
            .flatMap { lateralOffset ->
                val wheelMountWorld = wheelMountWorld(body, wheelLocalPos, lateralOffset, forward, right, suspensionDirWorld)
                sweepSamples.asSequence().map { sweep -> Vector3d(wheelMountWorld).add(sweep) }
            }
            .map { sampleLocalPos ->
                WheelRayHit(
                    mountWorld = sampleLocalPos,
                    result = physLevel.rayCast(sampleLocalPos, castDirection, maxLength, body.id)
                )
            }
            .filter { hit -> hit.result != null && hit.result.hitBody.id != body.id && hit.result.distance.isFinite() }
            .minByOrNull { hit -> hit.result!!.distance }

        if (bestHit == null) {
            val wheelMountWorld = wheelMountWorld(body, wheelLocalPos, 0.0, forward, right, suspensionDirWorld)
            return WheelContact(
                grounded = false,
                hitBody = null,
                contactPointWorld = wheelMountWorld,
                contactNormalWorld = WORLD_UP,
                suspensionDirWorld = suspensionDirWorld,
                hitDistance = maxLength,
                compression = 0.0,
                normalForceEstimate = 0.0,
                wheelForwardWorld = wheelForward,
                wheelRightWorld = wheelRight,
                wheelVelocityWorld = velocityAtWorldPoint(body, wheelMountWorld)
            )
        }

        val result = bestHit.result!!
        val hitDistance = result.distance
        val contactPoint = Vector3d(bestHit.mountWorld).fma(hitDistance, castDirection)
        val springLength = hitDistance - config.wheelRadius
        val compressionMeters = config.suspensionRestLength - springLength
        val compression = (compressionMeters / config.suspensionTravel).coerceIn(0.0, 1.0)
        val normalForceEstimate = max(0.0, compressionMeters * config.suspensionStrength)

        return WheelContact(
            grounded = true,
            hitBody = result.hitBody,
            contactPointWorld = contactPoint,
            contactNormalWorld = safeNormalize(result.hitNormal, suspensionDirWorld),
            suspensionDirWorld = suspensionDirWorld,
            hitDistance = hitDistance,
            compression = compression,
            normalForceEstimate = normalForceEstimate,
            wheelForwardWorld = wheelForward,
            wheelRightWorld = wheelRight,
            wheelVelocityWorld = velocityAtWorldPoint(body, contactPoint)
        )
    }

    private fun wheelMountWorld(
        body: PhysVsBody,
        wheelLocalPos: Vector3d,
        lateralOffset: Double,
        forward: Vector3d,
        right: Vector3d,
        terrainUp: Vector3d
    ): Vector3d {
        return Vector3d(body.kinematics.position)
            .fma(wheelLocalPos.z, forward)
            .fma(wheelLocalPos.x + lateralOffset, right)
            .fma(wheelLocalPos.y, terrainUp)
    }

    private fun applySuspension(body: PhysVsBody, contact: WheelContact, config: BikePhysicsConfig) {
        if (!contact.grounded) return

        val compressionMeters = contact.compression * config.suspensionTravel
        if (compressionMeters <= 0.0) return

        val springForceMag = compressionMeters * config.suspensionStrength
        val suspensionVelocity = contact.wheelVelocityWorld.dot(contact.suspensionDirWorld)
        val dampingForceMag = -suspensionVelocity * config.suspensionDamping
        val totalForceMag = max(0.0, springForceMag + dampingForceMag)

        applyContactForce(
            body,
            contact,
            Vector3d(contact.suspensionDirWorld).mul(totalForceMag),
            contact.contactPointWorld
        )
    }

    private fun applyTireForces(body: PhysVsBody, contact: WheelContact, config: BikePhysicsConfig) {
        if (!contact.grounded) return

        val lateralVel = safeDot(contact.wheelVelocityWorld, contact.wheelRightWorld)
        val forwardVel = safeDot(contact.wheelVelocityWorld, contact.wheelForwardWorld)
        val safeSpeed = max(abs(forwardVel), 2.0)
        val slip = lateralVel / safeSpeed
        val gripFactor = tanh(slip * config.slipSharpness)
        val maxLateralForce = contact.normalForceEstimate * config.frictionCoefficient * config.lateralGrip
        val lateralForceMag = -gripFactor * maxLateralForce

        applyContactForce(
            body,
            contact,
            Vector3d(contact.wheelRightWorld).mul(lateralForceMag),
            contact.contactPointWorld
        )
    }

    private fun applyDriveAndBrakes(
        body: PhysVsBody,
        front: WheelContact,
        rear: WheelContact,
        input: BikeInput,
        config: BikePhysicsConfig
    ) {
        val throttle = input.throttle.coerceIn(-1.0, 1.0)
        if (rear.grounded && throttle != 0.0) {
            applyDriveForce(body, rear, throttle, config, 1.0)
        } else if (front.grounded && throttle > 0.0) {
            applyDriveForce(body, front, throttle, config, FRONT_STEP_TRACTION_SCALE)
        }

        applyBrake(body, front, input.brake * 0.65, config)
        applyBrake(body, rear, input.brake * 0.35, config)
    }

    private fun applyDriveForce(
        body: PhysVsBody,
        contact: WheelContact,
        throttle: Double,
        config: BikePhysicsConfig,
        forceScale: Double
    ) {
        if (!contact.grounded || throttle == 0.0 || forceScale <= 0.0) return

        val forwardVel = safeDot(contact.wheelVelocityWorld, contact.wheelForwardWorld)
        val speedLimitFactor = computeMotorSpeedLimitFactor(forwardVel, throttle, config)
        if (speedLimitFactor > 0.0) {
            val forceMag = throttle * speedLimitFactor * config.longitudinalGrip * contact.normalForceEstimate * forceScale
            applyContactForce(body, contact, Vector3d(contact.wheelForwardWorld).mul(forceMag), contact.contactPointWorld)
        }
    }

    private fun computeMotorSpeedLimitFactor(forwardVel: Double, throttle: Double, config: BikePhysicsConfig): Double {
        if (throttle == 0.0) return 0.0
        val topSpeed = config.wheelTopSpeed
        if (!topSpeed.isFinite() || topSpeed <= 0.0) return 1.0

        val throttleDirection = if (throttle > 0.0) 1.0 else -1.0
        val signedSpeed = forwardVel * throttleDirection
        if (signedSpeed <= topSpeed * 0.82) return 1.0
        if (signedSpeed >= topSpeed) return 0.0
        return 1.0 - smoothstep(topSpeed * 0.82, topSpeed, signedSpeed)
    }

    private fun applyBrake(body: PhysVsBody, contact: WheelContact, brakeInput: Double, config: BikePhysicsConfig) {
        if (!contact.grounded || brakeInput <= 0.0) return

        val forwardVel = safeDot(contact.wheelVelocityWorld, contact.wheelForwardWorld)
        val maxBrakeForce = contact.normalForceEstimate * config.frictionCoefficient * config.longitudinalGrip * config.brakeStrength
        val forceMag = (-forwardVel * brakeInput * maxBrakeForce).coerceIn(-maxBrakeForce, maxBrakeForce)
        applyContactForce(body, contact, Vector3d(contact.wheelForwardWorld).mul(forceMag), contact.contactPointWorld)
    }

    private fun computeTargetLean(forwardSpeed: Double, steerInput: Double, config: BikePhysicsConfig): Double {
        val speedLeanAmount = smoothstep(config.minLeanSpeed, config.fullLeanSpeed, abs(forwardSpeed))
        return -steerInput.coerceIn(-1.0, 1.0) * config.maxLeanAngleRad * speedLeanAmount
    }

    private fun updateSmoothedTargetLean(state: BikeRuntimeState, targetLean: Double, dt: Double): Double {
        val alpha = 1.0 - exp(-dt / 0.12)
        state.smoothedTargetLeanRad = lerp(state.smoothedTargetLeanRad, targetLean, alpha)
        return state.smoothedTargetLeanRad
    }

    private fun computeFrontSteerAngle(forwardSpeed: Double, steerInput: Double, config: BikePhysicsConfig): Double {
        val speed = abs(forwardSpeed)
        val speedT = smoothstep(config.steeringLowSpeedEnd, config.steeringHighSpeedStart, speed)
        val maxSteer = lerp(config.maxSteerLowSpeedRad, config.maxSteerHighSpeedRad, speedT)
        return steerInput.coerceIn(-1.0, 1.0) * maxSteer
    }

    private fun applyBalanceController(
        body: PhysVsBody,
        forward: Vector3d,
        currentUp: Vector3d,
        terrainUp: Vector3d,
        targetLeanRad: Double,
        config: BikePhysicsConfig,
        forwardSpeed: Double,
        strengthScale: Double
    ) {
        val leanedUp = safeNormalize(Vector3d(terrainUp).rotateAxis(targetLeanRad, forward.x, forward.y, forward.z), terrainUp)
        val uprightAssist = 1.0 - smoothstep(config.uprightAssistStartSpeed, config.uprightAssistEndSpeed, abs(forwardSpeed))
        val desiredUp = slerpDirection(leanedUp, terrainUp, uprightAssist)
        val errorAxis = Vector3d(currentUp).cross(desiredUp)
        val torque = errorAxis.mul(config.balanceStrength * strengthScale)
            .sub(Vector3d(body.kinematics.angularVelocity).mul(config.balanceDamping * strengthScale))

        safeApplyWorldTorque(body, torque)
    }

    private fun applyLowSpeedAssist(
        body: PhysVsBody,
        input: BikeInput,
        speed: Double,
        terrainUp: Vector3d,
        config: BikePhysicsConfig
    ) {
        val movementAmount = smoothstep(0.4, 1.2, abs(speed))
        val lowSpeedAmount = (1.0 - smoothstep(3.0, 10.0, abs(speed))) * movementAmount
        val direction = when {
            speed < -0.5 -> -1.0
            speed > 0.5 -> 1.0
            input.throttle < -0.05 -> -1.0
            else -> 1.0
        }
        val yawTorque = input.steer.coerceIn(-1.0, 1.0) * direction * config.lowSpeedYawAssist * lowSpeedAmount
        if (yawTorque != 0.0) {
            safeApplyWorldTorque(body, Vector3d(terrainUp).mul(yawTorque))
        }
    }

    private fun applyAntiFlipAssist(
        body: PhysVsBody,
        forward: Vector3d,
        right: Vector3d,
        up: Vector3d,
        terrainUp: Vector3d,
        config: BikePhysicsConfig
    ) {
        val pitchError = safeDot(forward, terrainUp).coerceIn(-1.0, 1.0)
        if (abs(pitchError) > kotlin.math.sin(config.maxPitchAngleRad)) {
            safeApplyWorldTorque(body, Vector3d(right).mul(-pitchError * config.antiFlipStrength))
        }

        val rollError = safeDot(right, terrainUp).coerceIn(-1.0, 1.0)
        if (abs(rollError) > kotlin.math.sin(config.maxRollAngleRad)) {
            safeApplyWorldTorque(body, Vector3d(forward).mul(rollError * config.antiFlipStrength))
        }

        if (safeDot(up, terrainUp) < 0.0) {
            safeApplyWorldTorque(body, Vector3d(forward).mul(config.antiFlipStrength))
        }
    }

    private fun applyStepAssist(
        body: PhysVsBody,
        physLevel: PhysLevel,
        frontContact: WheelContact,
        forward: Vector3d,
        terrainUp: Vector3d,
        input: BikeInput,
        config: BikePhysicsConfig
    ) {
        if (!frontContact.grounded || input.throttle <= 0.0) return

        val terrainForward = Vector3d(forward).sub(Vector3d(terrainUp).mul(safeDot(forward, terrainUp)))
        if (terrainForward.lengthSquared() < 1.0e-6) return
        terrainForward.set(safeNormalize(terrainForward, forward))

        val probeLength = config.wheelRadius + 0.35
        val lowProbeStart = Vector3d(frontContact.contactPointWorld)
            .fma(config.wheelRadius * 0.55, terrainUp)
            .fma(0.08, terrainForward)
        val obstacle = physLevel.rayCast(lowProbeStart, terrainForward, probeLength, body.id) ?: return
        if (obstacle.hitBody.id == body.id) return

        val highProbeStart = Vector3d(lowProbeStart).fma(config.maxStepHeight, terrainUp)
        val blockedAbove = physLevel.rayCast(highProbeStart, terrainForward, probeLength, body.id)
        if (blockedAbove != null && blockedAbove.hitBody.id != body.id) return

        val speedIntoStep = max(0.0, safeDot(body.kinematics.velocity, terrainForward))
        val assistAmount = (1.0 - smoothstep(8.0, 18.0, speedIntoStep)).coerceIn(0.25, 1.0)
        val liftForce = Vector3d(terrainUp).mul(config.stepAssistStrength * assistAmount)
        val forwardForce = Vector3d(terrainForward).mul(config.stepAssistStrength * 0.04 * assistAmount)

        applyContactForce(body, frontContact, liftForce.add(forwardForce), frontContact.contactPointWorld)
    }

    private fun applyAirborneControl(
        body: PhysVsBody,
        forward: Vector3d,
        right: Vector3d,
        up: Vector3d,
        input: BikeInput,
        config: BikePhysicsConfig
    ) {
        val pitchTorque = Vector3d(right)
            .mul(-input.pitch.coerceIn(-1.0, 1.0) * config.airbornePitchControlStrength)
        val yawTorque = Vector3d(up)
            .mul(input.steer.coerceIn(-1.0, 1.0) * config.airborneRollControlStrength)
        val brakeDamping = Vector3d(body.kinematics.angularVelocity)
            .mul(-input.brake.coerceIn(0.0, 1.0) * config.airborneBrakeDamping)

        safeApplyWorldTorque(body, pitchTorque.add(yawTorque).add(brakeDamping))
    }

    private fun applyJumpChargeAndRelease(
        body: PhysVsBody,
        contacts: List<WheelContact>,
        forward: Vector3d,
        terrainUp: Vector3d,
        input: BikeInput,
        config: BikePhysicsConfig,
        state: BikeRuntimeState,
        dt: Double
    ) {
        val jumpInput = input.jump.coerceIn(0.0, 1.0)
        val jumpHeld = jumpInput > 0.0

        if (jumpHeld) {
            state.jumpCharge = (state.jumpCharge + jumpInput * config.jumpChargeRate * dt).coerceIn(0.0, 1.0)
            val compressionForce = Vector3d(terrainUp)
                .mul(-config.jumpCompressionForce * state.jumpCharge)
            safeApplyWorldForce(body, compressionForce, body.kinematics.position)
        } else if (state.wasJumpHeld && state.jumpCharge > 0.05) {
            val terrainForward = Vector3d(forward).sub(Vector3d(terrainUp).mul(safeDot(forward, terrainUp)))
            if (terrainForward.lengthSquared() > 1.0e-6) {
                terrainForward.set(safeNormalize(terrainForward, forward))
            } else {
                terrainForward.set(forward)
            }

            val charge = state.jumpCharge
            val launchForce = Vector3d(terrainUp).mul(config.jumpReleaseForce * charge)
                .fma(config.jumpForwardBoostForce * charge, terrainForward)
            state.jumpReleaseForce.set(launchForce)
            state.jumpReleaseTimeRemaining = config.jumpReleaseDuration
            state.jumpCharge = 0.0
        }

        state.wasJumpHeld = jumpHeld
    }

    private fun applyPendingJumpRelease(
        body: PhysVsBody,
        contacts: List<WheelContact>,
        state: BikeRuntimeState,
        dt: Double
    ) {
        if (state.jumpReleaseTimeRemaining <= 0.0) return

        safeApplyWorldForce(body, state.jumpReleaseForce, body.kinematics.position)
        applyGroundReactionForSharedForce(body, contacts, state.jumpReleaseForce)
        state.jumpReleaseTimeRemaining = max(0.0, state.jumpReleaseTimeRemaining - dt)
        if (state.jumpReleaseTimeRemaining <= 0.0) {
            state.jumpReleaseForce.zero()
        }
    }

    private fun dampExtremeAngularVelocity(body: PhysVsBody, config: BikePhysicsConfig) {
        val angularVelocity = Vector3d(body.kinematics.angularVelocity)
        val overspeed = if (isFinite(angularVelocity)) angularVelocity.length() - config.maxAngularVelocity else 0.0
        if (overspeed > 0.0) {
            safeApplyWorldTorque(body, safeNormalize(angularVelocity, WORLD_UP).mul(-overspeed * config.balanceDamping))
        }
    }

    private fun updateVisualState(
        state: BikeRuntimeState,
        targetLean: Double,
        targetSteer: Double,
        forwardSpeed: Double,
        config: BikePhysicsConfig,
        dt: Double,
        grounded: Boolean
    ) {
        val alpha = 1.0 - exp(-dt / 0.12)
        val steerAlpha = 1.0 - exp(-dt / 0.08)
        state.visualLeanRad = lerp(state.visualLeanRad, targetLean, alpha)
        state.visualSteerRad = lerp(state.visualSteerRad, targetSteer, steerAlpha)
        state.frontWheelSpin += forwardSpeed / config.wheelRadius * dt
        state.rearWheelSpin += forwardSpeed / config.wheelRadius * dt
        state.lastGrounded = grounded
    }

    private fun smoothGroundNormal(
        state: BikeRuntimeState,
        contacts: List<WheelContact>,
        dt: Double,
        config: BikePhysicsConfig
    ) {
        val groundedContacts = contacts.filter(WheelContact::grounded)
        if (groundedContacts.isEmpty()) return

        val rawNormal = Vector3d()
        groundedContacts.forEach { rawNormal.add(it.contactNormalWorld) }
        rawNormal.set(safeNormalize(rawNormal, state.smoothedGroundNormal))

        val alpha = 1.0 - exp(-dt / config.groundNormalSmoothingTime)
        state.smoothedGroundNormal.set(safeNormalize(Vector3d(state.smoothedGroundNormal).lerp(rawNormal, alpha), WORLD_UP))
    }

    private fun velocityAtWorldPoint(body: PhysVsBody, pointWorld: Vector3d): Vector3d {
        val radius = Vector3d(pointWorld).sub(body.kinematics.position)
        val velocity = Vector3d(body.kinematics.angularVelocity).cross(radius).add(body.kinematics.velocity)
        return if (isFinite(velocity)) velocity else Vector3d()
    }

    private fun transformDirection(body: PhysVsBody, direction: Vector3d, fallback: Vector3d): Vector3d {
        return safeNormalize(body.kinematics.rotation.transform(Vector3d(direction)), fallback)
    }

    private fun projectOntoPlane(vector: Vector3dc, normal: Vector3dc, fallback: Vector3dc): Vector3d {
        val projected = Vector3d(vector).sub(Vector3d(normal).mul(safeDot(vector, normal)))
        return safeNormalize(projected, fallback)
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        if (edge0 == edge1) return if (value < edge0) 0.0 else 1.0
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun lerp(from: Double, to: Double, alpha: Double): Double {
        return from + (to - from) * alpha.coerceIn(0.0, 1.0)
    }

    private fun slerpDirection(from: Vector3d, to: Vector3d, alpha: Double): Vector3d {
        return safeNormalize(Vector3d(from).lerp(to, alpha.coerceIn(0.0, 1.0)), to)
    }

    private fun applyContactForce(body: PhysVsBody, contact: WheelContact, force: Vector3dc, pos: Vector3dc) {
        safeApplyWorldForce(body, force, pos)

        val hitBody = contact.hitBody ?: return
        if (hitBody.id == body.id || hitBody.isStatic) return

        safeApplyWorldForce(hitBody, Vector3d(force).negate(), pos)
    }

    private fun applyGroundReactionForSharedForce(
        body: PhysVsBody,
        contacts: List<WheelContact>,
        force: Vector3dc
    ) {
        val dynamicContacts = contacts
            .filter { contact -> contact.grounded }
            .filter { contact ->
                val hitBody = contact.hitBody
                hitBody != null && hitBody.id != body.id && !hitBody.isStatic
            }
        if (dynamicContacts.isEmpty()) return

        val sharedReaction = Vector3d(force).negate().div(dynamicContacts.size.toDouble())
        dynamicContacts.forEach { contact ->
            safeApplyWorldForce(contact.hitBody!!, sharedReaction, contact.contactPointWorld)
        }
    }

    private fun safeApplyWorldForce(body: PhysVsBody, force: Vector3dc, pos: Vector3dc) {
        if (!isFinite(force) || !isFinite(pos)) return
        if (force.lengthSquared() > MAX_FORCE_MAGNITUDE * MAX_FORCE_MAGNITUDE) return
        body.applyWorldForce(force, pos)
    }

    private fun safeApplyWorldTorque(body: PhysVsBody, torque: Vector3dc) {
        if (!isFinite(torque)) return
        if (torque.lengthSquared() > MAX_TORQUE_MAGNITUDE * MAX_TORQUE_MAGNITUDE) return
        body.applyWorldTorque(torque)
    }

    private fun safeNormalize(vector: Vector3dc, fallback: Vector3dc): Vector3d {
        if (!isFinite(vector) || vector.lengthSquared() < MIN_DIRECTION_LENGTH_SQUARED) {
            return Vector3d(fallback)
        }
        val normalized = Vector3d(vector).normalize()
        return if (isFinite(normalized)) normalized else Vector3d(fallback)
    }

    private fun safeDot(a: Vector3dc, b: Vector3dc): Double {
        if (!isFinite(a) || !isFinite(b)) return 0.0
        val dot = a.dot(b)
        return if (dot.isFinite()) dot else 0.0
    }

    private fun isFinite(vector: Vector3dc): Boolean {
        return vector.x().isFinite() && vector.y().isFinite() && vector.z().isFinite()
    }

    private data class WheelRayHit(
        val mountWorld: Vector3d,
        val result: org.valkyrienskies.core.api.physics.RayCastResult?
    )
}
