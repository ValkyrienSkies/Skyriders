package org.valkyrienskies.skyriders.util

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.BikePhysicsConfig
import org.valkyrienskies.skyriders.content.BikeRuntimeState
import org.valkyrienskies.skyriders.content.VehicleDamage
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import org.valkyrienskies.skyriders.content.WheelContact
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
    private const val WHEEL_MOTOR_RESPONSE = 16.0
    private const val WHEEL_BRAKE_RESPONSE = 24.0
    private const val WHEEL_AIR_DRAG = 0.35
    private const val WHEEL_GROUND_DRAG = 0.04
    private const val WHEEL_INERTIA_MASS_SCALE = 0.018
    private const val MAX_WHEEL_TOP_SPEED_MULTIPLIER = 4.0
    private const val LONGITUDINAL_SLIP_REFERENCE_SPEED = 1.5
    private const val LOCKED_BRAKE_SLIP_REFERENCE_SPEED = 0.25
    private const val LOW_SPEED_BRAKE_ASSIST_SPEED = 1.25
    private const val LOW_SPEED_BRAKE_SNAP_SPEED = 0.12
    private const val LOW_SPEED_BRAKE_DEAD_SPEED = 0.015
    private const val LOW_SPEED_BRAKE_DAMPING = 10.0
    private const val MIN_STEP_ASSIST_RISE = 0.35
    private const val STEP_SIDE_PROBE_BIAS = 0.025

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
        val riderPresent = input.riderPresent
        val activeInput = if (riderPresent) input else BikeInput.EMPTY
        val drifting = updateDriftState(state, activeInput, forwardSpeed, config)
        val frontSteerRad = updateFrontSteerAngle(
            state,
            computeFrontSteerAngle(forwardSpeed, activeInput.steer, config),
            dt
        )

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
        updateDebugState(body, state, frontContact, rearContact, activeInput, frontSteerRad, drifting)
        if (grounded) {
            state.groundedGraceTimeRemaining = GROUNDED_GRACE_SECONDS
        } else {
            state.groundedGraceTimeRemaining = max(0.0, state.groundedGraceTimeRemaining - dt)
        }
        val stabilizedGrounded = grounded || state.groundedGraceTimeRemaining > 0.0

        if (grounded) {
            smoothGroundNormal(state, contacts, forward, right, drifting, dt, config)
        }
        val terrainUp = safeNormalize(state.smoothedGroundNormal, WORLD_UP)
        updateLandingDampingWindow(state, grounded, config)
        updateDriftBoost(body, state, riderPresent, drifting, forward, terrainUp, config, dt)

        applySuspension(body, frontContact, config)
        applySuspension(body, rearContact, config)
        applyLandingDamping(body, terrainUp, state, config, dt)
        val tractionScale = VehicleStatusEffects.tractionScale(physLevel.dimension, body.id) *
            VehicleDamage.tractionScale(physLevel.dimension, body.id)
        applyTireForces(body, frontContact, true, drifting, config, tractionScale)
        applyTireForces(body, rearContact, false, drifting, config, tractionScale)
        if (riderPresent) {
            applyDriveAndBrakes(body, frontContact, rearContact, activeInput, drifting, config, state, dt, tractionScale)
        } else {
            state.frontWheelAngularVelocity = applyParkingBrake(
                body,
                frontContact,
                state.frontWheelAngularVelocity,
                config,
                dt
            )
            state.rearWheelAngularVelocity = applyParkingBrake(
                body,
                rearContact,
                state.rearWheelAngularVelocity,
                config,
                dt
            )
        }

        val targetLean = updateSmoothedTargetLean(
            state,
            computeTargetLean(forwardSpeed, activeInput.steer, drifting, state, config),
            dt
        )
        val chargingJump = activeInput.jump > 0.0
        val balanceTargetLean = if (chargingJump) targetLean * 0.25 else targetLean
        val balanceStrengthScale = if (chargingJump) 1.35 else 1.0
        if (grounded && riderPresent) {
            applyJumpChargeAndRelease(body, contacts, forward, terrainUp, activeInput, config, state, dt)
            applyPendingJumpRelease(body, contacts, state, dt)
            state.debugStepAssistWork += applyStepAssist(body, physLevel, frontContact, forward, terrainUp, activeInput, config)
            state.debugStepAssistWork += applyStepAssist(body, physLevel, rearContact, forward, terrainUp, activeInput, config)
        } else {
            applyPendingJumpRelease(body, contacts, state, dt)
        }

        if (stabilizedGrounded) {
            if (riderPresent) {
                applyBalanceController(body, forward, up, terrainUp, balanceTargetLean, config, forwardSpeed, balanceStrengthScale)
                applyGroundedPitchControl(body, activeInput, forward, right, terrainUp, forwardSpeed, config)
                if (frontContact.grounded) {
                    if (drifting) {
                        applyDriftAssist(body, activeInput, forwardSpeed, terrainUp, state, config)
                    } else {
                        applySteeringAssist(body, activeInput, forwardSpeed, terrainUp, config)
                    }
                }
                applyAntiFlipAssist(body, forward, right, up, terrainUp, config)
            } else {
                applyParkingStabilization(body, forward, up, terrainUp, config)
            }
        } else {
            if (riderPresent) {
                applyAirborneControl(body, forward, right, up, activeInput, config)
            } else {
                applyAirborneParkingDamping(body, config)
            }
        }
        if (!grounded) {
            val jumpHeld = activeInput.jump > 0.0
            if (!jumpHeld) {
                state.jumpCharge = 0.0
            }
            state.wasJumpHeld = jumpHeld
        }
        dampExtremeAngularVelocity(body, config)
        updateVisualState(state, targetLean, frontSteerRad, forwardSpeed, frontContact, rearContact, config, dt, grounded)

        state.lastGrounded = grounded
    }

    private fun updateLandingDampingWindow(
        state: BikeRuntimeState,
        grounded: Boolean,
        config: BikePhysicsConfig
    ) {
        if (!config.landingDampingEnabled) {
            state.landingDampingTimeRemaining = 0.0
            return
        }

        if (grounded && !state.lastGrounded) {
            state.landingDampingTimeRemaining = config.landingDampingDuration
        }
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
                surfaceFriction = 1.0,
                wheelForwardWorld = wheelForward,
                wheelRightWorld = wheelRight,
                wheelVelocityWorld = velocityAtWorldPoint(body, wheelMountWorld),
                surfaceVelocityWorld = Vector3d(),
                relativeWheelVelocityWorld = velocityAtWorldPoint(body, wheelMountWorld)
            )
        }

        val result = bestHit.result!!
        val hitDistance = result.distance
        val contactPoint = Vector3d(bestHit.mountWorld).fma(hitDistance, castDirection)
        val wheelVelocity = velocityAtWorldPoint(body, contactPoint)
        val surfaceVelocity = VehiclePhysicsMath.surfaceVelocityAtPoint(result.hitBody, contactPoint)
        val springLength = hitDistance - config.wheelRadius
        val compressionMeters = config.suspensionRestLength - springLength
        val compression = (compressionMeters / config.suspensionTravel).coerceIn(0.0, 1.0)
        val normalForceEstimate = max(0.0, compressionMeters * config.suspensionStrength)
        val loaded = compressionMeters > 1.0e-4

        return WheelContact(
            grounded = loaded,
            hitBody = result.hitBody,
            contactPointWorld = contactPoint,
            contactNormalWorld = safeNormalize(result.hitNormal, suspensionDirWorld),
            suspensionDirWorld = suspensionDirWorld,
            hitDistance = hitDistance,
            compression = compression,
            normalForceEstimate = normalForceEstimate,
            surfaceFriction = VehicleWheelPhysics.surfaceFrictionScale(
                result.staticFrictionCoefficient,
                result.dynamicFrictionCoefficient
            ),
            wheelForwardWorld = wheelForward,
            wheelRightWorld = wheelRight,
            wheelVelocityWorld = wheelVelocity,
            surfaceVelocityWorld = surfaceVelocity,
            relativeWheelVelocityWorld = Vector3d(wheelVelocity).sub(surfaceVelocity)
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
        return body.kinematics.transform.toWorld
            .transformPosition(Vector3d(wheelLocalPos))
            .fma(lateralOffset, right)
    }

    private fun applySuspension(body: PhysVsBody, contact: WheelContact, config: BikePhysicsConfig) {
        if (!contact.grounded) return

        val compressionMeters = contact.compression * config.suspensionTravel
        if (compressionMeters <= 0.0) return

        val springForceMag = compressionMeters * config.suspensionStrength
        val suspensionVelocity = contact.relativeWheelVelocityWorld.dot(contact.suspensionDirWorld)
        val dampingForceMag = -suspensionVelocity * config.suspensionDamping
        val totalForceMag = max(0.0, springForceMag + dampingForceMag)

        applyContactForce(
            body,
            contact,
            Vector3d(contact.suspensionDirWorld).mul(totalForceMag),
            contact.contactPointWorld
        )
    }

    private fun applyLandingDamping(
        body: PhysVsBody,
        terrainUp: Vector3d,
        state: BikeRuntimeState,
        config: BikePhysicsConfig,
        dt: Double
    ) {
        if (!config.landingDampingEnabled || state.landingDampingTimeRemaining <= 0.0) return

        val verticalVelocity = safeDot(body.kinematics.velocity, terrainUp)
        if (verticalVelocity < 0.0) {
            val verticalDampingForce = Vector3d(terrainUp)
                .mul(-verticalVelocity * config.mass * config.landingVerticalDamping)
            safeApplyWorldForce(body, verticalDampingForce, body.kinematics.position)
        }

        val yawVelocity = safeDot(body.kinematics.angularVelocity, terrainUp)
        val pitchRollAngularVelocity = Vector3d(body.kinematics.angularVelocity)
            .fma(-yawVelocity, terrainUp)
        if (isFinite(pitchRollAngularVelocity) && pitchRollAngularVelocity.lengthSquared() > 1.0e-6) {
            safeApplyWorldTorque(body, pitchRollAngularVelocity.mul(-config.landingAngularDamping))
        }

        state.landingDampingTimeRemaining = max(0.0, state.landingDampingTimeRemaining - dt)
    }

    private fun applyTireForces(
        body: PhysVsBody,
        contact: WheelContact,
        isFrontWheel: Boolean,
        drifting: Boolean,
        config: BikePhysicsConfig,
        tractionScale: Double
    ) {
        if (!contact.grounded) return

        val lateralVel = safeDot(contact.relativeWheelVelocityWorld, contact.wheelRightWorld)
        val forwardVel = safeDot(contact.relativeWheelVelocityWorld, contact.wheelForwardWorld)
        val safeSpeed = max(abs(forwardVel), 2.0)
        val slip = lateralVel / safeSpeed
        val gripFactor = pacejkaLateralGrip(slip, config)
        val driftGripScale = if (!drifting) {
            1.0
        } else if (isFrontWheel) {
            config.driftFrontGripScale
        } else {
            config.driftRearGripScale
        }
        val maxLateralForce = contact.normalForceEstimate *
            config.frictionCoefficient *
            contact.surfaceFriction *
            config.lateralGrip *
            driftGripScale *
            tractionScale
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
        drifting: Boolean,
        config: BikePhysicsConfig,
        state: BikeRuntimeState,
        dt: Double,
        tractionScale: Double
    ) {
        val throttle = input.throttle.coerceIn(-1.0, 1.0)
        val driveScale = if (drifting) config.driftDriveForceScale else 1.0
        val frontBrake = if (!drifting) input.brake * 0.65 else 0.0
        val rearBrake = if (!drifting) input.brake * 0.35 else input.brake * config.driftBrakeStrengthScale
        val frontForceScale = if (frontBrake > 0.0) 1.0 else FRONT_STEP_TRACTION_SCALE * driveScale
        val rearForceScale = if (rearBrake > 0.0 && !drifting) 1.0 else driveScale

        state.frontWheelAngularVelocity = applyWheelLongitudinalPhysics(
            body = body,
            contact = front,
            angularVelocity = state.frontWheelAngularVelocity,
            throttle = if (!rear.grounded && front.grounded) throttle.coerceAtLeast(0.0) else 0.0,
            brakeInput = frontBrake,
            driven = !rear.grounded && front.grounded && throttle > 0.0,
            forceScale = frontForceScale,
            config = config,
            state = state,
            dt = dt,
            tractionScale = tractionScale,
            useLockedBrakeSlipReference = !drifting
        )
        state.rearWheelAngularVelocity = applyWheelLongitudinalPhysics(
            body = body,
            contact = rear,
            angularVelocity = state.rearWheelAngularVelocity,
            throttle = throttle,
            brakeInput = rearBrake,
            driven = true,
            forceScale = rearForceScale,
            config = config,
            state = state,
            dt = dt,
            tractionScale = tractionScale,
            useLockedBrakeSlipReference = !drifting
        )
    }

    private fun applyWheelLongitudinalPhysics(
        body: PhysVsBody,
        contact: WheelContact,
        angularVelocity: Double,
        throttle: Double,
        brakeInput: Double,
        driven: Boolean,
        forceScale: Double,
        config: BikePhysicsConfig,
        state: BikeRuntimeState,
        dt: Double,
        tractionScale: Double,
        useLockedBrakeSlipReference: Boolean
    ): Double {
        val stepDt = dt.coerceIn(0.0, 0.1)
        val radius = max(config.wheelRadius, 0.05)
        val maxOmega = max(config.wheelTopSpeed, 1.0) * MAX_WHEEL_TOP_SPEED_MULTIPLIER / radius
        val groundSpeed = if (contact.grounded) safeDot(contact.relativeWheelVelocityWorld, contact.wheelForwardWorld) else 0.0
        var omega = if (angularVelocity.isFinite()) angularVelocity else groundSpeed / radius

        if (driven && throttle != 0.0) {
            val motorTargetOmega = throttle.coerceIn(-1.0, 1.0) * config.wheelTopSpeed * 1.08 / radius
            val motorAlpha = 1.0 - exp(-stepDt * WHEEL_MOTOR_RESPONSE * abs(throttle))
            omega = lerp(omega, motorTargetOmega, motorAlpha)
        }

        val brake = brakeInput.coerceIn(0.0, 1.0)
        if (brake > 0.0) {
            val brakeAlpha = 1.0 - exp(-stepDt * WHEEL_BRAKE_RESPONSE * brake * config.brakeStrength / 3.0)
            omega = lerp(omega, 0.0, brakeAlpha)
        }

        val drag = if (contact.grounded) WHEEL_GROUND_DRAG else WHEEL_AIR_DRAG
        omega *= exp(-drag * stepDt)

        if (contact.grounded && forceScale > 0.0 && contact.normalForceEstimate > 0.0) {
            val surfaceSpeed = omega * radius
            val slipVelocity = surfaceSpeed - groundSpeed
            val slipReferenceSpeed = longitudinalSlipReferenceSpeed(
                surfaceSpeed = surfaceSpeed,
                groundSpeed = groundSpeed,
                brake = brake,
                useLockedBrakeSlipReference = useLockedBrakeSlipReference
            )
            val slipRatio = slipVelocity / slipReferenceSpeed
            val gripFactor = pacejkaLongitudinalGrip(slipRatio, config)
            val brakeForceScale = if (brake > 0.0) {
                lerp(1.0, config.brakeStrength, brake)
            } else {
                1.0
            }
            val maxLongitudinalForce = contact.normalForceEstimate *
                config.frictionCoefficient *
                contact.surfaceFriction *
                config.longitudinalGrip *
                forceScale *
                brakeForceScale *
                tractionScale
            val forceMag = gripFactor * maxLongitudinalForce
            if (driven && throttle != 0.0) {
                val drivePower = forceMag * safeDot(contact.relativeWheelVelocityWorld, contact.wheelForwardWorld)
                if (drivePower > 0.0) {
                    state.debugDriveWork += drivePower / max(config.mass, 1.0)
                }
            }

            applyContactForce(body, contact, Vector3d(contact.wheelForwardWorld).mul(forceMag), contact.contactPointWorld)
            applyLowSpeedBrakeAssist(
                body = body,
                contact = contact,
                groundSpeed = groundSpeed,
                brake = brake,
                maxLongitudinalForce = maxLongitudinalForce,
                config = config,
                dt = stepDt
            )

            val wheelInertia = max(0.08, config.mass * radius * radius * WHEEL_INERTIA_MASS_SCALE)
            val reactionDelta = (forceMag * radius / wheelInertia * stepDt)
                .coerceIn(-maxOmega * 0.35, maxOmega * 0.35)
            omega -= reactionDelta

            if (abs(slipVelocity) < 0.4) {
                val rollingOmega = groundSpeed / radius
                val rollingAlpha = 1.0 - exp(-stepDt * 12.0)
                omega = lerp(omega, rollingOmega, rollingAlpha)
            }
        }

        return omega.coerceIn(-maxOmega, maxOmega)
    }

    private fun longitudinalSlipReferenceSpeed(
        surfaceSpeed: Double,
        groundSpeed: Double,
        brake: Double,
        useLockedBrakeSlipReference: Boolean
    ): Double {
        val wheelRoadSpeed = max(abs(surfaceSpeed), abs(groundSpeed))
        val minimumReference = if (useLockedBrakeSlipReference && brake > 0.05) {
            LOCKED_BRAKE_SLIP_REFERENCE_SPEED
        } else {
            LONGITUDINAL_SLIP_REFERENCE_SPEED
        }
        return max(wheelRoadSpeed, minimumReference)
    }

    private fun applyLowSpeedBrakeAssist(
        body: PhysVsBody,
        contact: WheelContact,
        groundSpeed: Double,
        brake: Double,
        maxLongitudinalForce: Double,
        config: BikePhysicsConfig,
        dt: Double
    ) {
        val brakeInput = brake.coerceIn(0.0, 1.0)
        val speed = abs(groundSpeed)
        if (brakeInput <= 0.0 || speed <= LOW_SPEED_BRAKE_DEAD_SPEED || speed >= LOW_SPEED_BRAKE_ASSIST_SPEED) return

        val assist = 1.0 - smoothstep(LOW_SPEED_BRAKE_DEAD_SPEED, LOW_SPEED_BRAKE_ASSIST_SPEED, speed)
        val dampingForce = -groundSpeed * config.mass * LOW_SPEED_BRAKE_DAMPING * brakeInput * assist
        val snapForce = if (speed <= LOW_SPEED_BRAKE_SNAP_SPEED && dt > 1.0e-6) {
            -groundSpeed * config.mass * brakeInput / dt
        } else {
            0.0
        }
        val forceMag = (dampingForce + snapForce)
            .coerceIn(-maxLongitudinalForce, maxLongitudinalForce)
        applyContactForce(body, contact, Vector3d(contact.wheelForwardWorld).mul(forceMag), contact.contactPointWorld)
    }

    private fun applyParkingBrake(
        body: PhysVsBody,
        contact: WheelContact,
        angularVelocity: Double,
        config: BikePhysicsConfig,
        dt: Double
    ): Double {
        val stepDt = dt.coerceIn(0.0, 0.1)
        val radius = max(config.wheelRadius, 0.05)
        val maxOmega = max(config.wheelTopSpeed, 1.0) * MAX_WHEEL_TOP_SPEED_MULTIPLIER / radius
        var omega = if (angularVelocity.isFinite()) angularVelocity else 0.0

        val brakeAlpha = 1.0 - exp(-stepDt * WHEEL_BRAKE_RESPONSE * config.parkingBrakeStrength / 3.0)
        omega = lerp(omega, 0.0, brakeAlpha)

        if (!contact.grounded) {
            return (omega * exp(-WHEEL_AIR_DRAG * stepDt)).coerceIn(-maxOmega, maxOmega)
        }

        val relative = contact.relativeWheelVelocityWorld
        val forwardVel = safeDot(relative, contact.wheelForwardWorld)
        val maxBrakeForce = contact.normalForceEstimate *
            config.frictionCoefficient *
            contact.surfaceFriction *
            config.longitudinalGrip *
            config.parkingBrakeStrength
        val forwardForceMag = (-forwardVel * maxBrakeForce).coerceIn(-maxBrakeForce, maxBrakeForce)
        applyContactForce(body, contact, Vector3d(contact.wheelForwardWorld).mul(forwardForceMag), contact.contactPointWorld)

        val lateralVel = safeDot(relative, contact.wheelRightWorld)
        val lateralForceMag = (-lateralVel * maxBrakeForce).coerceIn(-maxBrakeForce, maxBrakeForce)
        applyContactForce(body, contact, Vector3d(contact.wheelRightWorld).mul(lateralForceMag), contact.contactPointWorld)

        val rollingOmega = forwardVel / radius
        if (abs(forwardVel) > 0.2) {
            omega = lerp(omega, rollingOmega, 1.0 - exp(-stepDt * 5.0))
        }
        return omega.coerceIn(-maxOmega, maxOmega)
    }

    private fun computeTargetLean(
        forwardSpeed: Double,
        steerInput: Double,
        drifting: Boolean,
        state: BikeRuntimeState,
        config: BikePhysicsConfig
    ): Double {
        val speedLeanAmount = smoothstep(config.minLeanSpeed, config.fullLeanSpeed, abs(forwardSpeed))
        if (!drifting || abs(state.driftDirection) <= 0.05) {
            return -steerInput.coerceIn(-1.0, 1.0) * config.maxLeanAngleRad * speedLeanAmount
        }

        val steerBias = steerInput.coerceIn(-1.0, 1.0) * state.driftDirection
        val leanBias = when {
            steerBias > 0.15 -> 1.0
            steerBias < -0.15 -> 0.45
            else -> 0.7
        }
        return -state.driftDirection * config.maxLeanAngleRad * speedLeanAmount * leanBias * config.driftLeanScale
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

    private fun updateFrontSteerAngle(state: BikeRuntimeState, targetSteerRad: Double, dt: Double): Double {
        val alpha = 1.0 - exp(-dt / 0.12)
        state.frontSteerRad = lerp(state.frontSteerRad, targetSteerRad, alpha)
        return state.frontSteerRad
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

    private fun applySteeringAssist(
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
        val highSpeedAmount = smoothstep(7.0, config.wheelTopSpeed, abs(speed)) * movementAmount
        val yawTorque = input.steer.coerceIn(-1.0, 1.0) * direction *
            (config.lowSpeedYawAssist * lowSpeedAmount + config.highSpeedYawAssist * highSpeedAmount)
        if (yawTorque != 0.0) {
            safeApplyWorldTorque(body, Vector3d(terrainUp).mul(yawTorque))
        }
    }

    private fun applyDriftAssist(
        body: PhysVsBody,
        input: BikeInput,
        speed: Double,
        terrainUp: Vector3d,
        state: BikeRuntimeState,
        config: BikePhysicsConfig
    ) {
        val direction = when {
            speed < -0.5 -> -1.0
            speed > 0.5 -> 1.0
            input.throttle < -0.05 -> -1.0
            else -> 1.0
        }
        val speedAmount = smoothstep(config.driftMinSpeed, config.wheelTopSpeed * 0.72, abs(speed))
        val driftDirection = state.driftDirection.takeIf { abs(it) > 0.05 } ?: input.steer.coerceIn(-1.0, 1.0)
        val bias = input.steer.coerceIn(-1.0, 1.0) * driftDirection
        val turnBias = when {
            bias > 0.15 -> 1.45
            bias < -0.15 -> 0.18
            else -> 0.78
        }
        val yawTorque = driftDirection * direction * config.driftYawAssist * speedAmount * turnBias * input.brake.coerceIn(0.0, 1.0)
        if (yawTorque != 0.0) {
            safeApplyWorldTorque(body, Vector3d(terrainUp).mul(yawTorque))
        }
    }

    private fun updateDriftState(
        state: BikeRuntimeState,
        input: BikeInput,
        forwardSpeed: Double,
        config: BikePhysicsConfig
    ): Boolean {
        if (input.brake <= 0.05) {
            state.driftDirection = 0.0
            return false
        }

        if (state.wasDrifting) return true

        val steer = input.steer.coerceIn(-1.0, 1.0)
        if (abs(steer) <= 0.15 || abs(forwardSpeed) < config.driftMinSpeed) {
            state.driftDirection = 0.0
            return false
        }

        state.driftDirection = if (steer > 0.0) 1.0 else -1.0
        return true
    }

    private fun updateDriftBoost(
        body: PhysVsBody,
        state: BikeRuntimeState,
        riderPresent: Boolean,
        drifting: Boolean,
        forward: Vector3d,
        terrainUp: Vector3d,
        config: BikePhysicsConfig,
        dt: Double
    ) {
        if (!config.driftBoostEnabled || !riderPresent) {
            state.wasDrifting = false
            state.driftDirection = 0.0
            state.driftBoostCharge = 0.0
            state.driftBoostLevel = 0
            state.driftBoostTimeRemaining = 0.0
            state.driftBoostForce = 0.0
            return
        }

        if (drifting) {
            state.driftBoostCharge += dt
            state.driftBoostLevel = computeDriftBoostLevel(state.driftBoostCharge, config)
            state.driftBoostTimeRemaining = 0.0
            state.driftBoostForce = 0.0
        } else if (state.wasDrifting) {
            val boostLevel = state.driftBoostLevel.coerceIn(0, driftBoostLevelCount(config))
            if (boostLevel > 0) {
                state.driftBoostForce = driftBoostForce(boostLevel, config)
                state.driftBoostTimeRemaining = driftBoostDuration(boostLevel, config)
            }
            state.driftBoostCharge = 0.0
            state.driftBoostLevel = 0
            state.driftDirection = 0.0
        }

        state.wasDrifting = drifting

        if (state.driftBoostTimeRemaining <= 0.0 || state.driftBoostForce <= 0.0) return

        val boostForward = projectOntoPlane(forward, terrainUp, forward)
        safeApplyWorldForce(body, boostForward.mul(state.driftBoostForce), body.kinematics.position)
        state.driftBoostTimeRemaining = max(0.0, state.driftBoostTimeRemaining - dt)
        if (state.driftBoostTimeRemaining <= 0.0) {
            state.driftBoostForce = 0.0
        }
    }

    private fun computeDriftBoostLevel(charge: Double, config: BikePhysicsConfig): Int {
        var level = 0
        val maxLevel = driftBoostLevelCount(config)
        for (index in 0 until maxLevel) {
            if (charge >= config.driftBoostChargeTimes[index]) {
                level = index + 1
            }
        }
        return level
    }

    private fun driftBoostForce(level: Int, config: BikePhysicsConfig): Double {
        val index = level - 1
        return if (index in config.driftBoostForces.indices) config.driftBoostForces[index] else 0.0
    }

    private fun driftBoostDuration(level: Int, config: BikePhysicsConfig): Double {
        val index = level - 1
        return if (index in config.driftBoostDurations.indices) config.driftBoostDurations[index] else 0.0
    }

    private fun driftBoostLevelCount(config: BikePhysicsConfig): Int {
        return min(
            config.driftBoostMaxLevel,
            min(config.driftBoostChargeTimes.size, min(config.driftBoostForces.size, config.driftBoostDurations.size))
        ).coerceAtLeast(0)
    }

    private fun applyGroundedPitchControl(
        body: PhysVsBody,
        input: BikeInput,
        forward: Vector3d,
        right: Vector3d,
        terrainUp: Vector3d,
        forwardSpeed: Double,
        config: BikePhysicsConfig
    ) {
        val currentPitch = safeDot(forward, terrainUp).coerceIn(-1.0, 1.0)
        val pitchRate = safeDot(body.kinematics.angularVelocity, right)
        val pitchInput = input.pitch.coerceIn(-1.0, 1.0)
        val speedAmount = smoothstep(0.75, 3.0, abs(forwardSpeed))

        if (pitchInput != 0.0 && speedAmount > 0.0) {
            val desiredPitch = if (pitchInput < 0.0) {
                -pitchInput * kotlin.math.sin(config.maxWheelieAngleRad)
            } else {
                -pitchInput * kotlin.math.sin(config.maxStoppieAngleRad)
            }
            val pitchError = desiredPitch - currentPitch
            val torque = Vector3d(right).mul(
                pitchError * config.groundedPitchControlStrength * speedAmount -
                    pitchRate * config.pitchLimitDamping * 0.35
            )
            safeApplyWorldTorque(body, torque)
        }

        val wheelieLimit = kotlin.math.sin(config.maxWheelieAngleRad)
        val stoppieLimit = -kotlin.math.sin(config.maxStoppieAngleRad)
        val limitError = when {
            currentPitch > wheelieLimit -> currentPitch - wheelieLimit
            currentPitch < stoppieLimit -> currentPitch - stoppieLimit
            else -> 0.0
        }
        if (limitError != 0.0) {
            val torque = Vector3d(right).mul(
                -limitError * config.pitchLimitStrength -
                    pitchRate * config.pitchLimitDamping
            )
            safeApplyWorldTorque(body, torque)
        } else if (abs(pitchRate) > 0.5) {
            val capDamping = min(1.0, abs(pitchRate) - 0.5)
            safeApplyWorldTorque(body, Vector3d(right).mul(-pitchRate * config.pitchLimitDamping * 0.25 * capDamping))
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

    private fun applyParkingStabilization(
        body: PhysVsBody,
        forward: Vector3d,
        up: Vector3d,
        terrainUp: Vector3d,
        config: BikePhysicsConfig
    ) {
        val errorAxis = Vector3d(up).cross(terrainUp)
        val angularVelocity = Vector3d(body.kinematics.angularVelocity)
        val yawVelocity = safeDot(angularVelocity, terrainUp)
        val nonYawAngularVelocity = Vector3d(angularVelocity).fma(-yawVelocity, terrainUp)
        val torque = errorAxis.mul(config.balanceStrength * config.parkingBalanceStrengthScale)
            .sub(nonYawAngularVelocity.mul(config.balanceDamping * config.parkingBalanceDampingScale))
            .fma(-yawVelocity * config.parkingYawDamping, terrainUp)

        safeApplyWorldTorque(body, torque)

        val rollError = safeDot(transformDirection(body, LOCAL_RIGHT, LOCAL_RIGHT), terrainUp).coerceIn(-1.0, 1.0)
        if (abs(rollError) > kotlin.math.sin(config.maxRollAngleRad * 0.65)) {
            safeApplyWorldTorque(body, Vector3d(forward).mul(rollError * config.antiFlipStrength))
        }
    }

    private fun applyAirborneParkingDamping(body: PhysVsBody, config: BikePhysicsConfig) {
        val torque = Vector3d(body.kinematics.angularVelocity)
            .mul(-config.balanceDamping * config.parkingBalanceDampingScale)
        safeApplyWorldTorque(body, torque)
    }

    private fun applyStepAssist(
        body: PhysVsBody,
        physLevel: PhysLevel,
        contact: WheelContact,
        forward: Vector3d,
        terrainUp: Vector3d,
        input: BikeInput,
        config: BikePhysicsConfig
    ): Double {
        if (!contact.grounded) return 0.0
        if (config.maxStepHeight <= 0.0 || config.stepAssistStrength <= 0.0) return 0.0

        val terrainForward = computeStepApproachDirection(body, forward, terrainUp, input.throttle)
        if (terrainForward.lengthSquared() < 1.0e-6) return 0.0

        val speedIntoStep = max(0.0, safeDot(body.kinematics.velocity, terrainForward))
        val projectedForward = projectOntoPlane(forward, terrainUp, forward)
        val throttleIntoStep = max(0.0, input.throttle.coerceIn(-1.0, 1.0) * safeDot(projectedForward, terrainForward))
        val crawlAmount = throttleIntoStep * (1.0 - smoothstep(0.8, 3.5, speedIntoStep))
        val effectiveStepSpeed = max(speedIntoStep, crawlAmount * 1.8)
        if (effectiveStepSpeed < 0.35) return 0.0

        val speedLookahead = smoothstep(3.0, config.wheelTopSpeed * 0.85, effectiveStepSpeed)
        val probeLength = (config.wheelRadius + 0.55 + effectiveStepSpeed * 0.11)
            .coerceIn(config.wheelRadius + 0.45, config.wheelRadius + 2.15)
        val lowProbeStart = Vector3d(contact.contactPointWorld)
            .fma(config.wheelRadius * 0.45, terrainUp)
            .fma(0.16 + speedLookahead * 0.36, terrainForward)
        val sideProbeDistance = config.wheelWidth * 0.7 + 0.08
        val obstacle = findStepObstacle(
            physLevel = physLevel,
            body = body,
            lowProbeStart = lowProbeStart,
            terrainForward = terrainForward,
            terrainUp = terrainUp,
            wheelRight = contact.wheelRightWorld,
            sideProbeDistance = sideProbeDistance,
            verticalProbeDistance = min(config.maxStepHeight * 0.45, config.wheelRadius * 1.15),
            probeLength = probeLength
        ) ?: return 0.0

        val step = findStepLandingSurface(
            physLevel = physLevel,
            body = body,
            contact = contact,
            lowProbeStart = obstacle.probeStart,
            obstacleDistance = obstacle.distance,
            terrainForward = terrainForward,
            terrainUp = terrainUp,
            wheelRight = contact.wheelRightWorld,
            sideProbeDistance = sideProbeDistance,
            config = config,
            speedLookahead = speedLookahead
        ) ?: return 0.0

        val heightAmount = smoothstep(0.12, config.maxStepHeight, step.rise)
        val approachDistance = max(0.25, step.approachDistance)
        val targetUpSpeed = (effectiveStepSpeed * step.rise / approachDistance * (0.55 + speedLookahead * 0.25))
            .coerceIn(0.0, 3.5 + speedLookahead * 4.6)
        val currentUpSpeed = safeDot(contact.relativeWheelVelocityWorld, terrainUp)
        val missingUpSpeed = max(0.0, targetUpSpeed - currentUpSpeed)
        val velocityLiftForce = config.mass * missingUpSpeed * (18.0 + speedLookahead * 22.0)
        val crawlLiftForce = config.stepAssistStrength * crawlAmount * (0.35 + heightAmount * 0.3)
        val baseLiftForce = config.stepAssistStrength * (0.65 + heightAmount * 0.45)
        val maxLiftForce = min(
            config.mass * (55.0 + speedLookahead * 80.0),
            config.stepAssistStrength * (2.0 + heightAmount * 1.2 + speedLookahead * 4.4)
        )
        val liftForce = Vector3d(terrainUp)
            .mul((baseLiftForce + velocityLiftForce + crawlLiftForce).coerceIn(0.0, maxLiftForce))

        val speedLimitScale = 1.0 - smoothstep(config.wheelTopSpeed, config.wheelTopSpeed * 1.18, speedIntoStep)
        val crawlCarryForce = config.stepAssistStrength * crawlAmount * (0.32 + heightAmount * 0.18)
        val carryForceMag = config.stepAssistStrength *
            (0.08 + speedLookahead * 0.58 + heightAmount * 0.12) *
            speedLimitScale.coerceIn(0.0, 1.0) + crawlCarryForce
        val forwardForce = Vector3d(terrainForward).mul(carryForceMag)

        val assistForce = liftForce.add(forwardForce)
        applyContactForce(body, contact, assistForce, contact.contactPointWorld)
        return assistForce.length() / max(config.mass, 1.0)
    }

    private fun findStepObstacle(
        physLevel: PhysLevel,
        body: PhysVsBody,
        lowProbeStart: Vector3d,
        terrainForward: Vector3d,
        terrainUp: Vector3d,
        wheelRight: Vector3d,
        sideProbeDistance: Double,
        verticalProbeDistance: Double,
        probeLength: Double
    ): StepObstacle? {
        return lateralStepOffsets(sideProbeDistance).asSequence()
            .flatMap { sideOffset ->
                verticalStepOffsets(verticalProbeDistance).asSequence().map { verticalOffset -> sideOffset to verticalOffset }
            }
            .mapNotNull { (sideOffset, verticalOffset) ->
                val probeStart = Vector3d(lowProbeStart)
                    .fma(sideOffset, wheelRight)
                    .fma(verticalOffset, terrainUp)
                val hit = physLevel.rayCast(probeStart, terrainForward, probeLength, body.id) ?: return@mapNotNull null
                if (hit.hitBody.id == body.id || !hit.distance.isFinite()) return@mapNotNull null
                StepObstacle(probeStart, hit.distance, abs(sideOffset))
            }
            .minByOrNull { it.distance + it.sideOffset * STEP_SIDE_PROBE_BIAS }
    }

    private fun computeStepApproachDirection(
        body: PhysVsBody,
        forward: Vector3d,
        terrainUp: Vector3d,
        throttle: Double
    ): Vector3d {
        val projectedForward = projectOntoPlane(forward, terrainUp, forward)
        val velocity = body.kinematics.velocity
        if (!isFinite(velocity)) return throttleStepDirection(projectedForward, throttle)

        val planarVelocity = Vector3d(velocity).fma(-safeDot(velocity, terrainUp), terrainUp)
        if (planarVelocity.lengthSquared() < 0.35 * 0.35) {
            return throttleStepDirection(projectedForward, throttle)
        }

        val velocityDirection = safeNormalize(planarVelocity, projectedForward)
        val alignment = safeDot(velocityDirection, projectedForward)
        if (abs(alignment) < 0.25) return Vector3d()

        val bikeAxisDirection = Vector3d(projectedForward).mul(if (alignment < 0.0) -1.0 else 1.0)
        val speed = planarVelocity.length()
        val velocityWeight = smoothstep(2.0, 12.0, speed) * 0.65
        return safeNormalize(
            Vector3d(bikeAxisDirection).mul(1.0 - velocityWeight).fma(velocityWeight, velocityDirection),
            bikeAxisDirection
        )
    }

    private fun throttleStepDirection(projectedForward: Vector3d, throttle: Double): Vector3d {
        return when {
            throttle > 0.05 -> Vector3d(projectedForward)
            throttle < -0.05 -> Vector3d(projectedForward).negate()
            else -> Vector3d()
        }
    }

    private fun findStepLandingSurface(
        physLevel: PhysLevel,
        body: PhysVsBody,
        contact: WheelContact,
        lowProbeStart: Vector3d,
        obstacleDistance: Double,
        terrainForward: Vector3d,
        terrainUp: Vector3d,
        wheelRight: Vector3d,
        sideProbeDistance: Double,
        config: BikePhysicsConfig,
        speedLookahead: Double
    ): StepOpportunity? {
        if (!obstacleDistance.isFinite()) return null

        val down = Vector3d(terrainUp).negate()
        val stepProbeHeight = config.maxStepHeight + config.wheelRadius * 1.25
        val downProbeLength = stepProbeHeight + config.wheelRadius * 1.4
        val forwardDistances = listOf(
            obstacleDistance + config.wheelRadius * 0.45,
            obstacleDistance + 0.35 + speedLookahead * 0.15,
            obstacleDistance + 0.65 + speedLookahead * 0.65
        )

        return forwardDistances
            .asSequence()
            .filter { it.isFinite() && it > 0.0 }
            .flatMap { forwardDistance ->
                lateralStepOffsets(sideProbeDistance).asSequence().map { sideOffset -> forwardDistance to sideOffset }
            }
            .mapNotNull { (forwardDistance, sideOffset) ->
                val topProbeStart = Vector3d(lowProbeStart)
                    .fma(forwardDistance, terrainForward)
                    .fma(sideOffset, wheelRight)
                    .fma(stepProbeHeight, terrainUp)
                val topHit = physLevel.rayCast(topProbeStart, down, downProbeLength, body.id) ?: return@mapNotNull null
                if (topHit.hitBody.id == body.id || !topHit.distance.isFinite()) return@mapNotNull null

                val topPoint = Vector3d(topProbeStart).fma(topHit.distance, down)
                val rise = safeDot(Vector3d(topPoint).sub(contact.contactPointWorld), terrainUp)
                val normalDot = safeDot(topHit.hitNormal, terrainUp)
                if (rise < MIN_STEP_ASSIST_RISE || rise > config.maxStepHeight + config.wheelRadius * 0.25) return@mapNotNull null
                if (normalDot < 0.48) return@mapNotNull null

                StepOpportunity(rise = rise, approachDistance = forwardDistance, sideOffset = abs(sideOffset))
            }
            .minByOrNull { it.rise + it.sideOffset * STEP_SIDE_PROBE_BIAS }
    }

    private fun lateralStepOffsets(sideProbeDistance: Double): List<Double> {
        val side = sideProbeDistance.takeIf { it.isFinite() && it > 1.0e-4 } ?: return listOf(0.0)
        return listOf(0.0, -side, side)
    }

    private fun verticalStepOffsets(verticalProbeDistance: Double): List<Double> {
        val vertical = verticalProbeDistance.takeIf { it.isFinite() && it > 1.0e-4 } ?: return listOf(0.0)
        return listOf(0.0, vertical, vertical * 0.5)
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
        frontContact: WheelContact,
        rearContact: WheelContact,
        config: BikePhysicsConfig,
        dt: Double,
        grounded: Boolean
    ) {
        val alpha = 1.0 - exp(-dt / 0.12)
        val steerAlpha = 1.0 - exp(-dt / 0.08)
        state.visualLeanRad = lerp(state.visualLeanRad, targetLean, alpha)
        state.visualSteerRad = lerp(state.visualSteerRad, targetSteer, steerAlpha)
        state.frontWheelSpin += state.frontWheelAngularVelocity * dt
        state.rearWheelSpin += state.rearWheelAngularVelocity * dt
        state.frontWheelSuspensionOffset = lerp(
            state.frontWheelSuspensionOffset,
            visualSuspensionOffset(frontContact, config.suspensionTravel),
            alpha
        )
        state.rearWheelSuspensionOffset = lerp(
            state.rearWheelSuspensionOffset,
            visualSuspensionOffset(rearContact, config.suspensionTravel),
            alpha
        )
        state.lastGrounded = grounded
    }

    private fun visualSuspensionOffset(contact: WheelContact, suspensionTravel: Double): Double {
        return if (contact.grounded) {
            contact.compression.coerceIn(0.0, 1.0) * suspensionTravel
        } else {
            0.0
        }
    }

    private fun updateDebugState(
        body: PhysVsBody,
        state: BikeRuntimeState,
        frontContact: WheelContact,
        rearContact: WheelContact,
        input: BikeInput,
        frontSteerRad: Double,
        drifting: Boolean
    ) {
        val velocity = body.kinematics.velocity
        state.debugSpeed = if (isFinite(velocity)) velocity.length() else 0.0
        state.debugFrontWheelGrounded = frontContact.grounded
        state.debugRearWheelGrounded = rearContact.grounded
        state.debugThrottle = input.throttle
        state.debugSteeringAngleRad = frontSteerRad
        state.debugDrifting = drifting
        state.debugDriveWork = 0.0
        state.debugStepAssistWork = 0.0
    }

    private fun smoothGroundNormal(
        state: BikeRuntimeState,
        contacts: List<WheelContact>,
        forward: Vector3d,
        right: Vector3d,
        drifting: Boolean,
        dt: Double,
        config: BikePhysicsConfig
    ) {
        val groundedContacts = contacts.filter(WheelContact::grounded)
        if (groundedContacts.isEmpty()) return

        val rawNormal = Vector3d()
        groundedContacts.forEach { rawNormal.add(it.contactNormalWorld) }
        rawNormal.set(safeNormalize(rawNormal, state.smoothedGroundNormal))

        val supportNormal = computeBikeSupportGroundNormal(contacts, forward, right, rawNormal)
        if (supportNormal != null) {
            val supportWeight = computeBikeSupportNormalWeight(supportNormal, drifting)
            if (supportWeight > 0.0) {
                rawNormal.set(
                    safeNormalize(
                        Vector3d(rawNormal).mul(1.0 - supportWeight).fma(supportWeight, supportNormal.normal),
                        rawNormal
                    )
                )
            }
        }

        val smoothingTime = config.groundNormalSmoothingTime * if (drifting) 1.35 else 1.0
        val alpha = 1.0 - exp(-dt / smoothingTime)
        state.smoothedGroundNormal.set(safeNormalize(Vector3d(state.smoothedGroundNormal).lerp(rawNormal, alpha), WORLD_UP))
    }

    private fun computeBikeSupportGroundNormal(
        contacts: List<WheelContact>,
        forward: Vector3d,
        right: Vector3d,
        fallbackNormal: Vector3d
    ): SupportGroundNormal? {
        val front = contacts.getOrNull(0)?.takeIf { it.hasSupportHit() } ?: return null
        val rear = contacts.getOrNull(1)?.takeIf { it.hasSupportHit() } ?: return null
        val forwardSpan = Vector3d(front.contactPointWorld).sub(rear.contactPointWorld)
        if (!isFinite(forwardSpan) || forwardSpan.lengthSquared() < 1.0e-6) return null

        val terrainForward = projectOntoPlane(forward, fallbackNormal, forward)
        val projectedSpan = Vector3d(forwardSpan).sub(Vector3d(fallbackNormal).mul(safeDot(forwardSpan, fallbackNormal)))
        if (!isFinite(projectedSpan) || projectedSpan.lengthSquared() < 1.0e-6) return null

        val spanDirection = safeNormalize(projectedSpan, terrainForward)
        val forwardAlignment = abs(safeDot(spanDirection, terrainForward))
        if (forwardAlignment < 0.55) return null

        val rise = abs(safeDot(forwardSpan, fallbackNormal))
        if (rise < 0.06) return null

        val stableRight = safeNormalize(Vector3d(fallbackNormal).cross(terrainForward), right)
        val supportNormal = Vector3d(forwardSpan).cross(stableRight)
        if (!isFinite(supportNormal) || supportNormal.lengthSquared() < 1.0e-6) return null
        if (safeDot(supportNormal, fallbackNormal) < 0.0) {
            supportNormal.negate()
        }
        val normalizedSupport = safeNormalize(supportNormal, fallbackNormal)
        if (safeDot(normalizedSupport, fallbackNormal) < 0.48) return null

        return SupportGroundNormal(
            normal = normalizedSupport,
            rise = rise,
            bothGrounded = front.grounded && rear.grounded
        )
    }

    private fun computeBikeSupportNormalWeight(supportNormal: SupportGroundNormal, drifting: Boolean): Double {
        val riseAmount = smoothstep(0.06, 0.45, supportNormal.rise)
        val loadScale = if (supportNormal.bothGrounded) 1.0 else 0.35
        val driftScale = if (drifting) 0.65 else 1.0
        return (0.34 * riseAmount * loadScale * driftScale).coerceIn(0.0, 0.34)
    }

    private fun WheelContact.hasSupportHit(): Boolean {
        return hitBody != null && isFinite(contactPointWorld) && hitDistance.isFinite()
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

    private fun pacejkaLateralGrip(slip: Double, config: BikePhysicsConfig): Double {
        val x = slip.coerceIn(-3.0, 3.0)
        val b = config.tirePacejkaB * (config.slipSharpness / 3.0)
        val bx = b * x
        val shaped = bx - config.tirePacejkaE * (bx - atan(bx))
        return sin(config.tirePacejkaC * atan(shaped)).coerceIn(-1.0, 1.0)
    }

    private fun pacejkaLongitudinalGrip(slip: Double, config: BikePhysicsConfig): Double {
        val x = slip.coerceIn(-3.0, 3.0)
        val b = config.tirePacejkaB * 0.72 * (config.slipSharpness / 3.0)
        val bx = b * x
        val shaped = bx - config.tirePacejkaE * (bx - atan(bx))
        return sin(config.tirePacejkaC * atan(shaped)).coerceIn(-1.0, 1.0)
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

    private data class SupportGroundNormal(
        val normal: Vector3d,
        val rise: Double,
        val bothGrounded: Boolean
    )

    private data class StepOpportunity(
        val rise: Double,
        val approachDistance: Double,
        val sideOffset: Double
    )

    private data class StepObstacle(
        val probeStart: Vector3d,
        val distance: Double,
        val sideOffset: Double
    )
}
