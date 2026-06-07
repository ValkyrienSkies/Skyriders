package org.valkyrienskies.skyriders.util

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.skyriders.content.KartPhysicsConfig
import org.valkyrienskies.skyriders.content.KartRuntimeState
import org.valkyrienskies.skyriders.content.VehicleInput
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

object KartPhysicsSolver {
    private val WORLD_UP = Vector3d(0.0, 1.0, 0.0)
    private val LOCAL_FORWARD = Vector3d(0.0, 0.0, 1.0)
    private val LOCAL_RIGHT = Vector3d(1.0, 0.0, 0.0)
    private val LOCAL_UP = Vector3d(0.0, 1.0, 0.0)
    private const val MAX_FORCE = VehiclePhysicsMath.MAX_FORCE_MAGNITUDE
    private const val WHEEL_AIR_DRAG = 0.35
    private const val WHEEL_GROUND_DRAG = 0.04
    private const val MAX_WHEEL_TOP_SPEED_MULTIPLIER = 4.0

    fun updateKartPhysics(
        body: PhysVsBody,
        physLevel: PhysLevel,
        input: VehicleInput,
        config: KartPhysicsConfig,
        state: KartRuntimeState,
        dt: Double
    ) {
        val forward = VehiclePhysicsMath.transformDirection(body, LOCAL_FORWARD, LOCAL_FORWARD)
        val right = VehiclePhysicsMath.transformDirection(body, LOCAL_RIGHT, LOCAL_RIGHT)
        val up = VehiclePhysicsMath.transformDirection(body, LOCAL_UP, LOCAL_UP)
        val forwardSpeed = VehiclePhysicsMath.safeDot(body.kinematics.velocity, forward)
        val driftGripActive = updateDriftState(state, input, forwardSpeed, dt, config)
        updateDriftBoost(body, state, input.riderPresent, state.drifting, forward, config, dt)
        val steerRad = updateSteerAngle(
            state,
            input.steer,
            forwardSpeed,
            computeTargetSteerRad(input.steer, forwardSpeed, config),
            dt,
            config
        )
        val contactUp = VehiclePhysicsMath.safeNormalize(state.smoothedGroundNormal, WORLD_UP)
        val contacts = config.wheelLocalPositions.mapIndexed { index, localPos ->
            sampleWheel(body, physLevel, localPos, index < 2, contactUp, steerRad, config, dt)
        }
        val groundedContacts = contacts.filter(VehicleWheelContact::grounded)
        val grounded = groundedContacts.isNotEmpty()
        if (grounded) {
            smoothGroundNormal(state, contacts, right, dt, config)
        }
        val terrainUp = VehiclePhysicsMath.safeNormalize(state.smoothedGroundNormal, WORLD_UP)
        val speed = body.kinematics.velocity.length()

        state.debugSpeed = if (speed.isFinite()) speed else 0.0
        state.debugForwardSpeed = if (forwardSpeed.isFinite()) forwardSpeed else 0.0
        state.debugGroundedWheels = groundedContacts.size
        state.debugSteerRad = steerRad
        state.debugThrottle = input.throttle

        val appliedContacts = contacts.mapIndexed { index, contact ->
            KartContact(
                contact = contact,
                front = index < 2,
                normalForce = applySuspension(body, contact, config)
            )
        }
        state.debugLateralSlip = averageLateralSlip(appliedContacts)

        appliedContacts.forEach { contact ->
            applyLateralGrip(body, contact, driftGripActive, config)
        }

        if (input.riderPresent) {
            applyDriveAndBrake(body, appliedContacts, forwardSpeed, terrainUp, input, driftGripActive, steerRad, config)
            if (grounded) {
                val activeStepWheels = contacts.count(VehicleWheelContact::grounded).coerceAtLeast(1)
                contacts.forEach { contact ->
                    applyStepAssist(body, physLevel, contact, forward, terrainUp, input, config, activeStepWheels)
                }
                if (state.drifting) {
                    applyDriftAssist(body, input, forwardSpeed, terrainUp, state, config)
                } else {
                    applySteeringAssist(body, steerRad, forwardSpeed, terrainUp, config)
                }
            }
        }

        if (grounded) {
            applyUpright(body, up, terrainUp, config)
        }
        dampAngularVelocity(body)
        updateWheelAngularVelocities(state, appliedContacts, input, driftGripActive, config, dt)
        updateVisualWheelState(state, contacts, config, dt)
    }

    private fun sampleWheel(
        body: PhysVsBody,
        physLevel: PhysLevel,
        wheelLocalPos: Vector3dc,
        front: Boolean,
        contactUp: Vector3d,
        steerRad: Double,
        config: KartPhysicsConfig,
        dt: Double
    ): VehicleWheelContact {
        val mountWorld = body.kinematics.transform.toWorld.transformPosition(Vector3d(wheelLocalPos))
        val suspensionUp = VehiclePhysicsMath.safeNormalize(contactUp, WORLD_UP)
        val castDir = Vector3d(suspensionUp).negate()
        val maxLength = config.suspensionRestLength + config.suspensionTravel + config.wheelRadius
        val groundedMaxDistance = config.suspensionRestLength + config.wheelRadius - 1.0e-4
        val bodyForward = VehiclePhysicsMath.transformDirection(body, LOCAL_FORWARD, LOCAL_FORWARD)
        val baseForward = VehiclePhysicsMath.projectOntoPlane(
            bodyForward,
            suspensionUp,
            bodyForward
        )
        val wheelForward = if (front && steerRad != 0.0) {
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
            listOf(
                Vector3d(),
                Vector3d(sweepOffset).mul(-0.5),
                Vector3d(sweepOffset).mul(-1.0),
                Vector3d(sweepOffset).mul(0.3)
            )
        } else {
            listOf(Vector3d())
        }
        val lateralOffsets = listOf(-config.wheelSampleWidth * 0.5, 0.0, config.wheelSampleWidth * 0.5)
        val samples = lateralOffsets.flatMap { lateralOffset ->
            val base = Vector3d(mountWorld).fma(lateralOffset, wheelRight)
            sweepSamples.map { sweep -> Vector3d(base).add(sweep) }
        }
        return samples
            .map { samplePos ->
                VehicleWheelPhysics.sampleRaycastWheel(
                    body = body,
                    physLevel = physLevel,
                    mountWorld = samplePos,
                    suspensionDirWorld = castDir,
                    maxLength = maxLength,
                    wheelForwardWorld = wheelForward,
                    wheelRightWorld = wheelRight,
                    groundedMaxDistance = groundedMaxDistance
                )
            }
            .filter(VehicleWheelContact::grounded)
            .minByOrNull(VehicleWheelContact::hitDistance)
            ?: VehicleWheelPhysics.sampleRaycastWheel(
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

    private fun applySuspension(body: PhysVsBody, contact: VehicleWheelContact, config: KartPhysicsConfig): Double {
        if (!contact.grounded) return 0.0
        val springLength = contact.hitDistance - config.wheelRadius
        val compression = (config.suspensionRestLength - springLength).coerceIn(0.0, config.suspensionTravel)
        if (compression <= 0.0) return 0.0

        val suspensionUp = VehiclePhysicsMath.safeNormalize(Vector3d(contact.suspensionDirWorld).negate(), WORLD_UP)
        val springVelocity = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.suspensionDirWorld)
        val forceMag = compression * config.suspensionStrength + springVelocity * config.suspensionDamping
        val normalForce = forceMag.coerceAtLeast(0.0)
        VehicleWheelPhysics.applyContactForce(body, contact, Vector3d(suspensionUp).mul(normalForce))
        return normalForce
    }

    private fun updateVisualWheelState(
        state: KartRuntimeState,
        contacts: List<VehicleWheelContact>,
        config: KartPhysicsConfig,
        dt: Double
    ) {
        val alpha = 1.0 - exp(-dt.coerceIn(0.0, 0.1) / 0.08)
        state.frontWheelSpin += state.frontWheelAngularVelocity * dt
        state.rearWheelSpin += state.rearWheelAngularVelocity * dt
        state.frontWheelSuspensionOffset = lerp(
            state.frontWheelSuspensionOffset,
            averageVisualSuspensionOffset(contacts.take(2), config),
            alpha
        )
        state.rearWheelSuspensionOffset = lerp(
            state.rearWheelSuspensionOffset,
            averageVisualSuspensionOffset(contacts.drop(2), config),
            alpha
        )
    }

    private fun averageVisualSuspensionOffset(contacts: List<VehicleWheelContact>, config: KartPhysicsConfig): Double {
        if (contacts.isEmpty()) return 0.0
        return contacts.sumOf { contact -> visualSuspensionOffset(contact, config) } / contacts.size
    }

    private fun visualSuspensionOffset(contact: VehicleWheelContact, config: KartPhysicsConfig): Double {
        if (!contact.grounded) return 0.0
        val springLength = contact.hitDistance - config.wheelRadius
        val compression = (config.suspensionRestLength - springLength).coerceIn(0.0, config.suspensionTravel)
        return compression
    }

    private fun applyLateralGrip(body: PhysVsBody, kartContact: KartContact, drifting: Boolean, config: KartPhysicsConfig) {
        val contact = kartContact.contact
        if (!contact.grounded || kartContact.normalForce <= 0.0) return

        val lateralSpeed = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.wheelRightWorld)
        val forwardSpeed = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.wheelForwardWorld)
        val baseGrip = if (kartContact.front) config.frontLateralGrip else config.rearLateralGrip
        val grip = if (!drifting) {
            baseGrip
        } else if (kartContact.front) {
            baseGrip * config.driftFrontGripScale
        } else {
            baseGrip * config.driftRearGripScale
        }
        val maxLateralForce = (kartContact.normalForce * config.tireFrictionCoefficient * grip)
            .coerceIn(0.0, MAX_FORCE)
        val slip = lateralSpeed / max(abs(forwardSpeed), 1.5)
        val shape = config.lateralSlipShape.takeIf { it.isFinite() && it > 1.0e-4 } ?: 0.5
        val shapedSlip = slip / (abs(slip) + shape)
        val force = Vector3d(contact.wheelRightWorld).mul((-shapedSlip * maxLateralForce).coerceIn(-MAX_FORCE, MAX_FORCE))
        VehicleWheelPhysics.applyContactForce(body, contact, force)
    }

    private fun applyDriveAndBrake(
        body: PhysVsBody,
        contacts: List<KartContact>,
        forwardSpeed: Double,
        terrainUp: Vector3d,
        input: VehicleInput,
        drifting: Boolean,
        steerRad: Double,
        config: KartPhysicsConfig
    ) {
        val rearContacts = contacts.filter { !it.front && it.contact.grounded && it.normalForce > 0.0 }
        if (rearContacts.isEmpty()) return

        val throttle = input.throttle.coerceIn(-1.0, 1.0)
        val usePlanarLimit = drifting || abs(input.steer) > 0.05 || abs(steerRad) > Math.toRadians(1.0)
        val driveLimitSpeed = if (usePlanarLimit) {
            val direction = forwardSpeed.signOrZero().takeIf { it != 0.0 } ?: throttle.signOrZero()
            planarSpeed(body, terrainUp) * direction
        } else {
            forwardSpeed
        }
        val topSpeed = if (drifting) config.wheelTopSpeed * config.driftTopSpeedMultiplier else config.wheelTopSpeed
        val speedLimitScale = computeSpeedLimitScale(driveLimitSpeed, throttle, topSpeed, config)
        val driveScale = if (drifting) config.driftDriveScale else 1.0
        val driveForce = throttle * config.driveForce * speedLimitScale * driveScale / rearContacts.size
        val driveNormalForce = rearContacts.sumOf(KartContact::normalForce) / rearContacts.size
        val maxDriveForce = driveNormalForce * config.tireFrictionCoefficient * config.longitudinalGrip
        rearContacts.forEach { kartContact ->
            if (driveForce != 0.0) {
                val contact = kartContact.contact
                val limitedDriveForce = driveForce.coerceIn(-maxDriveForce, maxDriveForce)
                VehicleWheelPhysics.applyContactForce(body, contact, Vector3d(contact.wheelForwardWorld).mul(limitedDriveForce))
            }
        }

        val rawBrake = input.brake.coerceIn(0.0, 1.0).coerceAtLeast(input.handbrake.coerceIn(0.0, 1.0))
        val brake = if (drifting) rawBrake * config.driftBrakeScale else rawBrake
        contacts.filter { it.contact.grounded && it.normalForce > 0.0 }.forEach { kartContact ->
            val contact = kartContact.contact
            val wheelForwardSpeed = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.wheelForwardWorld)
            val rollingScale = if (drifting) 0.35 else 1.0
            val rollingForce = (-wheelForwardSpeed * config.rollingResistance * rollingScale / contacts.size).coerceIn(-MAX_FORCE, MAX_FORCE)
            if (rollingForce != 0.0) {
                VehicleWheelPhysics.applyContactForce(body, contact, Vector3d(contact.wheelForwardWorld).mul(rollingForce))
            }

            if (brake > 0.0) {
                val maxBrakeForce = kartContact.normalForce * config.tireFrictionCoefficient * config.longitudinalGrip
                val brakeForce = (-wheelForwardSpeed * config.brakeForce * brake).coerceIn(-maxBrakeForce, maxBrakeForce)
                VehicleWheelPhysics.applyContactForce(body, contact, Vector3d(contact.wheelForwardWorld).mul(brakeForce))
            }
        }
    }

    private fun updateWheelAngularVelocities(
        state: KartRuntimeState,
        contacts: List<KartContact>,
        input: VehicleInput,
        drifting: Boolean,
        config: KartPhysicsConfig,
        dt: Double
    ) {
        val topSpeed = if (drifting) config.wheelTopSpeed * config.driftTopSpeedMultiplier else config.wheelTopSpeed
        val brake = input.brake.coerceIn(0.0, 1.0).coerceAtLeast(input.handbrake.coerceIn(0.0, 1.0))
        state.frontWheelAngularVelocity = updateWheelGroupAngularVelocity(
            contacts = contacts.filter(KartContact::front),
            angularVelocity = state.frontWheelAngularVelocity,
            throttle = 0.0,
            brake = brake,
            driven = false,
            topSpeed = topSpeed,
            config = config,
            dt = dt
        )
        state.rearWheelAngularVelocity = updateWheelGroupAngularVelocity(
            contacts = contacts.filter { !it.front },
            angularVelocity = state.rearWheelAngularVelocity,
            throttle = input.throttle.coerceIn(-1.0, 1.0),
            brake = brake,
            driven = true,
            topSpeed = topSpeed,
            config = config,
            dt = dt
        )
    }

    private fun applySteeringAssist(
        body: PhysVsBody,
        steerRad: Double,
        forwardSpeed: Double,
        terrainUp: Vector3d,
        config: KartPhysicsConfig
    ) {
        val steer = (steerRad / config.maxSteerRad).coerceIn(-1.0, 1.0)
        val speed = abs(forwardSpeed)
        if (abs(steer) < 1.0e-4 || speed < 0.05) return
        val speedT = smoothstep(config.yawAssistMinSpeed, config.yawAssistMaxSpeed, speed)
        val lowSpeedT = smoothstep(0.05, config.yawAssistMinSpeed.coerceAtLeast(0.06), speed)
        val assist = max(speedT, lowSpeedT * 0.22)
        val torque = Vector3d(terrainUp).mul(steer * forwardSpeed.signOrZero() * config.yawAssist * assist)
        VehiclePhysicsMath.safeApplyWorldTorque(body, torque)
    }

    private fun applyDriftAssist(
        body: PhysVsBody,
        input: VehicleInput,
        forwardSpeed: Double,
        terrainUp: Vector3d,
        state: KartRuntimeState,
        config: KartPhysicsConfig
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

    private fun applyUpright(body: PhysVsBody, up: Vector3d, terrainUp: Vector3d, config: KartPhysicsConfig) {
        val axis = Vector3d(up).cross(terrainUp)
        if (!VehiclePhysicsMath.isFinite(axis) || axis.lengthSquared() < VehiclePhysicsMath.MIN_DIRECTION_LENGTH_SQUARED) return
        val correction = axis.mul(config.uprightStrength)
        val damping = Vector3d(body.kinematics.angularVelocity).mul(-config.uprightDamping)
        VehiclePhysicsMath.safeApplyWorldTorque(body, correction.add(damping))
    }

    private fun applyStepAssist(
        body: PhysVsBody,
        physLevel: PhysLevel,
        contact: VehicleWheelContact,
        forward: Vector3d,
        terrainUp: Vector3d,
        input: VehicleInput,
        config: KartPhysicsConfig,
        activeStepWheels: Int
    ) {
        if (!contact.grounded) return
        if (config.maxStepHeight <= 0.0 || config.stepAssistStrength <= 0.0) return

        val terrainForward = computeStepApproachDirection(body, forward, terrainUp, input.throttle)
        if (terrainForward.lengthSquared() < 1.0e-6) return

        val speedIntoStep = max(0.0, VehiclePhysicsMath.safeDot(body.kinematics.velocity, terrainForward))
        val projectedForward = VehiclePhysicsMath.projectOntoPlane(forward, terrainUp, forward)
        val throttleIntoStep = max(0.0, input.throttle.coerceIn(-1.0, 1.0) * VehiclePhysicsMath.safeDot(projectedForward, terrainForward))
        val crawlAmount = throttleIntoStep * (1.0 - smoothstep(0.8, 3.5, speedIntoStep))
        val effectiveStepSpeed = max(speedIntoStep, crawlAmount * 1.8)
        if (effectiveStepSpeed < 0.35) return

        val speedLookahead = smoothstep(3.0, config.wheelTopSpeed * 0.85, effectiveStepSpeed)
        val probeLength = (config.wheelRadius + 0.45 + effectiveStepSpeed * 0.09)
            .coerceIn(config.wheelRadius + 0.35, config.wheelRadius + 1.75)
        val lowProbeStart = Vector3d(contact.contactPointWorld)
            .fma(config.wheelRadius * 0.45, terrainUp)
            .fma(0.06 + speedLookahead * 0.24, terrainForward)
        val obstacle = physLevel.rayCast(lowProbeStart, terrainForward, probeLength, body.id) ?: return
        if (obstacle.hitBody.id == body.id) return

        val step = findStepLandingSurface(
            physLevel = physLevel,
            body = body,
            contact = contact,
            lowProbeStart = lowProbeStart,
            obstacleDistance = obstacle.distance,
            terrainForward = terrainForward,
            terrainUp = terrainUp,
            config = config,
            speedLookahead = speedLookahead
        ) ?: return

        val heightAmount = smoothstep(0.12, config.maxStepHeight, step.rise)
        val approachDistance = max(0.25, step.approachDistance)
        val targetUpSpeed = (effectiveStepSpeed * step.rise / approachDistance * (0.55 + speedLookahead * 0.25))
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
        val liftForce = Vector3d(terrainUp)
            .mul((baseLiftForce + velocityLiftForce + crawlLiftForce).coerceIn(0.0, maxLiftForce) * wheelShare)

        val speedLimitScale = 1.0 - smoothstep(config.wheelTopSpeed, config.wheelTopSpeed * 1.18, speedIntoStep)
        val obstacleProximityScale = 1.0 - smoothstep(probeLength * 0.45, probeLength, obstacle.distance)
        val crawlCarryForce = config.stepAssistStrength * crawlAmount * (0.18 + heightAmount * 0.08)
        val rawCarryForce = config.stepAssistStrength *
            (0.025 + speedLookahead * 0.18 + heightAmount * 0.05) *
            speedLimitScale.coerceIn(0.0, 1.0) * obstacleProximityScale.coerceIn(0.0, 1.0) + crawlCarryForce
        val maxCarryForce = config.mass * (1.8 + speedLookahead * 3.8 + heightAmount * 1.2)
        val carryForceMag = rawCarryForce.coerceIn(0.0, maxCarryForce) * wheelShare
        val forwardForce = Vector3d(terrainForward).mul(carryForceMag)

        VehicleWheelPhysics.applyContactForce(body, contact, liftForce.add(forwardForce))
    }

    private fun computeStepApproachDirection(
        body: PhysVsBody,
        forward: Vector3d,
        terrainUp: Vector3d,
        throttle: Double
    ): Vector3d {
        val projectedForward = VehiclePhysicsMath.projectOntoPlane(forward, terrainUp, forward)
        val velocity = body.kinematics.velocity
        if (!VehiclePhysicsMath.isFinite(velocity)) return throttleStepDirection(projectedForward, throttle)

        val planarVelocity = Vector3d(velocity).fma(-VehiclePhysicsMath.safeDot(velocity, terrainUp), terrainUp)
        if (planarVelocity.lengthSquared() < 0.35 * 0.35) {
            return throttleStepDirection(projectedForward, throttle)
        }

        val velocityDirection = VehiclePhysicsMath.safeNormalize(planarVelocity, projectedForward)
        val alignment = VehiclePhysicsMath.safeDot(velocityDirection, projectedForward)
        if (abs(alignment) < 0.25) return Vector3d()

        val vehicleAxisDirection = Vector3d(projectedForward).mul(if (alignment < 0.0) -1.0 else 1.0)
        val speed = planarVelocity.length()
        val velocityWeight = smoothstep(2.0, 12.0, speed) * 0.65
        return VehiclePhysicsMath.safeNormalize(
            Vector3d(vehicleAxisDirection).mul(1.0 - velocityWeight).fma(velocityWeight, velocityDirection),
            vehicleAxisDirection
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
        contact: VehicleWheelContact,
        lowProbeStart: Vector3d,
        obstacleDistance: Double,
        terrainForward: Vector3d,
        terrainUp: Vector3d,
        config: KartPhysicsConfig,
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
            .mapNotNull { forwardDistance ->
                val topProbeStart = Vector3d(lowProbeStart)
                    .fma(forwardDistance, terrainForward)
                    .fma(stepProbeHeight, terrainUp)
                val topHit = physLevel.rayCast(topProbeStart, down, downProbeLength, body.id) ?: return@mapNotNull null
                if (topHit.hitBody.id == body.id || !topHit.distance.isFinite()) return@mapNotNull null

                val topPoint = Vector3d(topProbeStart).fma(topHit.distance, down)
                val rise = VehiclePhysicsMath.safeDot(Vector3d(topPoint).sub(contact.contactPointWorld), terrainUp)
                val normalDot = VehiclePhysicsMath.safeDot(topHit.hitNormal, terrainUp)
                if (rise < 0.05 || rise > config.maxStepHeight + config.wheelRadius * 0.25) return@mapNotNull null
                if (normalDot < 0.48) return@mapNotNull null

                StepOpportunity(rise = rise, approachDistance = forwardDistance)
            }
            .minByOrNull { it.rise }
    }

    private fun smoothGroundNormal(
        state: KartRuntimeState,
        contacts: List<VehicleWheelContact>,
        bodyRight: Vector3d,
        dt: Double,
        config: KartPhysicsConfig
    ) {
        val groundedContacts = contacts.filter(VehicleWheelContact::grounded)
        if (groundedContacts.isEmpty()) return

        val normalAverage = Vector3d()
        groundedContacts.forEach { normalAverage.add(it.contactNormalWorld) }
        var rawNormal = VehiclePhysicsMath.safeNormalize(normalAverage, state.smoothedGroundNormal)
        val supportNormal = computeSupportGroundNormal(contacts, bodyRight, rawNormal)
        if (supportNormal != null) {
            rawNormal = VehiclePhysicsMath.safeNormalize(Vector3d(rawNormal).mul(0.35).fma(0.65, supportNormal), rawNormal)
        }

        val smoothingTime = config.groundNormalSmoothingTime.takeIf { it.isFinite() && it > 1.0e-4 } ?: 0.12
        val alpha = 1.0 - exp(dt.coerceIn(0.0, 0.1) / -smoothingTime)
        state.smoothedGroundNormal.set(
            VehiclePhysicsMath.safeNormalize(Vector3d(state.smoothedGroundNormal).lerp(rawNormal, alpha), WORLD_UP)
        )
    }

    private fun computeSupportGroundNormal(
        contacts: List<VehicleWheelContact>,
        bodyRight: Vector3d,
        fallbackNormal: Vector3d
    ): Vector3d? {
        if (contacts.size < 4) return null

        val frontPoints = contacts.take(2).filter { it.hasSupportHit() }.map(VehicleWheelContact::contactPointWorld)
        val rearPoints = contacts.drop(2).filter { it.hasSupportHit() }.map(VehicleWheelContact::contactPointWorld)
        if (frontPoints.isEmpty() || rearPoints.isEmpty()) return null

        val frontCenter = averagePoint(frontPoints)
        val rearCenter = averagePoint(rearPoints)
        val forwardSpan = Vector3d(frontCenter).sub(rearCenter)
        if (forwardSpan.lengthSquared() < 1.0e-6) return null

        val leftPoints = listOfNotNull(
            contacts.getOrNull(0)?.takeIf { it.hasSupportHit() }?.contactPointWorld,
            contacts.getOrNull(2)?.takeIf { it.hasSupportHit() }?.contactPointWorld
        )
        val rightPoints = listOfNotNull(
            contacts.getOrNull(1)?.takeIf { it.hasSupportHit() }?.contactPointWorld,
            contacts.getOrNull(3)?.takeIf { it.hasSupportHit() }?.contactPointWorld
        )
        val rightSpan = if (leftPoints.isNotEmpty() && rightPoints.isNotEmpty()) {
            Vector3d(averagePoint(rightPoints)).sub(averagePoint(leftPoints))
        } else {
            Vector3d(bodyRight)
        }
        if (rightSpan.lengthSquared() < 1.0e-6) return null

        val supportNormal = Vector3d(forwardSpan).cross(rightSpan)
        if (!VehiclePhysicsMath.isFinite(supportNormal) || supportNormal.lengthSquared() < 1.0e-6) return null
        if (VehiclePhysicsMath.safeDot(supportNormal, fallbackNormal) < 0.0) {
            supportNormal.negate()
        }
        return VehiclePhysicsMath.safeNormalize(supportNormal, fallbackNormal)
    }

    private fun averagePoint(points: List<Vector3d>): Vector3d {
        val average = Vector3d()
        points.forEach(average::add)
        return average.div(points.size.toDouble())
    }

    private fun VehicleWheelContact.hasSupportHit(): Boolean {
        return hitBody != null && VehiclePhysicsMath.isFinite(contactPointWorld) && hitDistance.isFinite()
    }

    private fun dampAngularVelocity(body: PhysVsBody) {
        val angularVelocity = body.kinematics.angularVelocity
        if (!VehiclePhysicsMath.isFinite(angularVelocity) || angularVelocity.lengthSquared() < 64.0) return
        VehiclePhysicsMath.safeApplyWorldTorque(body, Vector3d(angularVelocity).mul(-450.0))
    }

    private fun Double.signOrZero(): Double {
        return when {
            this > 0.0 -> 1.0
            this < 0.0 -> -1.0
            else -> 0.0
        }
    }

    private fun computeTargetSteerRad(steerInput: Double, forwardSpeed: Double, config: KartPhysicsConfig): Double {
        val speedT = smoothstep(config.steeringHighSpeedStart, config.steeringFullSpeed, abs(forwardSpeed))
        val maxSteer = lerp(config.maxSteerRad, config.maxSteerHighSpeedRad, speedT)
        return steerInput.coerceIn(-1.0, 1.0) * maxSteer
    }

    private fun updateSteerAngle(
        state: KartRuntimeState,
        steerInput: Double,
        forwardSpeed: Double,
        targetSteerRad: Double,
        dt: Double,
        config: KartPhysicsConfig
    ): Double {
        if (abs(steerInput) < 0.03 && abs(forwardSpeed) < config.yawAssistMinSpeed) {
            state.smoothedSteerRad = 0.0
            return 0.0
        }

        val smoothingTime = config.steerSmoothingTime.takeIf { it.isFinite() && it > 1.0e-4 } ?: 0.1
        val alpha = 1.0 - exp(-dt.coerceIn(0.0, 0.1) / smoothingTime)
        state.smoothedSteerRad = lerp(state.smoothedSteerRad, targetSteerRad, alpha)
        if (targetSteerRad == 0.0 && abs(state.smoothedSteerRad) < 0.002) {
            state.smoothedSteerRad = 0.0
        }
        return state.smoothedSteerRad
    }

    private fun computeSpeedLimitScale(speed: Double, throttle: Double, topSpeed: Double, config: KartPhysicsConfig): Double {
        if (throttle == 0.0) return 0.0
        if (!topSpeed.isFinite() || topSpeed <= 0.0) return 1.0

        val signedSpeed = speed * throttle.signOrZero()
        if (signedSpeed <= topSpeed * (1.0 - config.speedLimitSoftness.coerceIn(0.02, 0.8))) return 1.0
        if (signedSpeed >= topSpeed) return 0.0
        return 1.0 - smoothstep(topSpeed * (1.0 - config.speedLimitSoftness.coerceIn(0.02, 0.8)), topSpeed, signedSpeed)
    }

    private fun updateWheelGroupAngularVelocity(
        contacts: List<KartContact>,
        angularVelocity: Double,
        throttle: Double,
        brake: Double,
        driven: Boolean,
        topSpeed: Double,
        config: KartPhysicsConfig,
        dt: Double
    ): Double {
        val stepDt = dt.coerceIn(0.0, 0.1)
        val radius = max(config.wheelRadius, 0.05)
        val maxOmega = max(topSpeed, 1.0) * MAX_WHEEL_TOP_SPEED_MULTIPLIER / radius
        val grounded = contacts.filter { it.contact.grounded }
        var omega = angularVelocity.takeIf(Double::isFinite) ?: 0.0

        if (grounded.isNotEmpty()) {
            val rollingOmega = grounded.sumOf { kartContact ->
                VehiclePhysicsMath.safeDot(kartContact.contact.wheelVelocityWorld, kartContact.contact.wheelForwardWorld) / radius
            } / grounded.size
            omega = lerp(omega, rollingOmega, 1.0 - exp(-stepDt * 14.0))
        } else if (driven && throttle != 0.0) {
            val targetOmega = throttle.coerceIn(-1.0, 1.0) * topSpeed * 1.08 / radius
            omega = lerp(omega, targetOmega, 1.0 - exp(-stepDt * 10.0 * abs(throttle)))
        }

        val brakeInput = brake.coerceIn(0.0, 1.0)
        if (brakeInput > 0.0) {
            val brakeResponse = (config.brakeForce / max(config.mass, 1.0) * 1.3).coerceIn(5.0, 32.0)
            omega = lerp(omega, 0.0, 1.0 - exp(-stepDt * brakeResponse * brakeInput))
        }

        val drag = if (grounded.isNotEmpty()) WHEEL_GROUND_DRAG else WHEEL_AIR_DRAG
        omega *= exp(-drag * stepDt)
        return omega.coerceIn(-maxOmega, maxOmega)
    }

    private fun planarSpeed(body: PhysVsBody, terrainUp: Vector3d): Double {
        val velocity = body.kinematics.velocity
        if (!VehiclePhysicsMath.isFinite(velocity)) return 0.0
        val vertical = VehiclePhysicsMath.safeDot(velocity, terrainUp)
        val planar = Vector3d(velocity).fma(-vertical, terrainUp)
        return if (VehiclePhysicsMath.isFinite(planar)) planar.length() else 0.0
    }

    private fun averageLateralSlip(contacts: List<KartContact>): Double {
        val grounded = contacts.filter { it.contact.grounded }
        if (grounded.isEmpty()) return 0.0
        val slipTotal = grounded.sumOf { kartContact ->
            val lateralSpeed = VehiclePhysicsMath.safeDot(kartContact.contact.wheelVelocityWorld, kartContact.contact.wheelRightWorld)
            val forwardSpeed = VehiclePhysicsMath.safeDot(kartContact.contact.wheelVelocityWorld, kartContact.contact.wheelForwardWorld)
            lateralSpeed / max(abs(forwardSpeed), 1.5)
        }
        return slipTotal / grounded.size
    }

    private fun updateDriftState(
        state: KartRuntimeState,
        input: VehicleInput,
        forwardSpeed: Double,
        dt: Double,
        config: KartPhysicsConfig
    ): Boolean {
        val brake = input.brake.coerceIn(0.0, 1.0).coerceAtLeast(input.handbrake.coerceIn(0.0, 1.0))
        val steer = input.steer.coerceIn(-1.0, 1.0)
        val speed = abs(forwardSpeed)

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
        state: KartRuntimeState,
        riderPresent: Boolean,
        drifting: Boolean,
        forward: Vector3d,
        config: KartPhysicsConfig,
        dt: Double
    ) {
        if (!config.driftBoostEnabled || !riderPresent) {
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

    private fun computeDriftBoostLevel(charge: Double, config: KartPhysicsConfig): Int {
        var level = 0
        val maxLevel = driftBoostLevelCount(config)
        for (index in 0 until maxLevel) {
            if (charge >= config.driftBoostChargeTimes[index]) {
                level = index + 1
            }
        }
        return level
    }

    private fun driftBoostForce(level: Int, config: KartPhysicsConfig): Double {
        val index = level - 1
        return if (index in config.driftBoostForces.indices) config.driftBoostForces[index] else 0.0
    }

    private fun driftBoostDuration(level: Int, config: KartPhysicsConfig): Double {
        val index = level - 1
        return if (index in config.driftBoostDurations.indices) config.driftBoostDurations[index] else 0.0
    }

    private fun driftBoostLevelCount(config: KartPhysicsConfig): Int {
        return min(
            config.driftBoostMaxLevel,
            min(config.driftBoostChargeTimes.size, min(config.driftBoostForces.size, config.driftBoostDurations.size))
        ).coerceAtLeast(0)
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        if (edge0 == edge1) return if (value < edge0) 0.0 else 1.0
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun lerp(from: Double, to: Double, alpha: Double): Double {
        return from + (to - from) * alpha.coerceIn(0.0, 1.0)
    }

    private data class KartContact(
        val contact: VehicleWheelContact,
        val front: Boolean,
        val normalForce: Double
    )

    private data class StepOpportunity(
        val rise: Double,
        val approachDistance: Double
    )
}
