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
        val steerRad = updateSteerAngle(state, activeInput.steer, forwardSpeed, config, dt)
        val contactUp = VehiclePhysicsMath.safeNormalize(state.smoothedGroundNormal, WORLD_UP)
        val wheels = expandWheels(config)
        val contacts = wheels.map { wheel ->
            val axleSteer = if (wheel.axle.steerable) steerRad * wheel.axle.steerScale else 0.0
            WheelContactState(wheel, sampleWheel(body, physLevel, wheel, contactUp, axleSteer, config, dt))
        }
        val grounded = contacts.filter { it.contact.grounded }
        if (grounded.isNotEmpty()) {
            state.groundedGraceTimeRemaining = config.groundedGraceTime
            smoothGroundNormal(state, contacts, forward, right, dt, config)
        } else {
            state.groundedGraceTimeRemaining = max(0.0, state.groundedGraceTimeRemaining - dt)
        }
        val stabilizedGrounded = grounded.isNotEmpty() || state.groundedGraceTimeRemaining > 0.0
        val terrainUp = VehiclePhysicsMath.safeNormalize(state.smoothedGroundNormal, WORLD_UP)

        val loaded = contacts.map { contactState ->
            LoadedWheelContact(
                wheel = contactState.wheel,
                contact = contactState.contact,
                normalForce = applySuspension(body, contactState, config)
            )
        }

        loaded.forEach { applyLateralGrip(body, it, config) }
        if (activeInput.riderPresent) {
            updateWheelLongitudinalPhysics(body, loaded, activeInput, config, state, dt)
            if (stabilizedGrounded) {
                applySteeringAssist(body, steerRad, forwardSpeed, terrainUp, config)
                loaded.forEach { applyStepAssist(body, physLevel, it, forward, terrainUp, activeInput, config) }
            }
        } else {
            applyParkingBrake(body, loaded, terrainUp, config)
            updateWheelLongitudinalPhysics(body, loaded, VehicleInput(handbrake = 1.0), config, state, dt)
        }

        if (stabilizedGrounded) {
            applyUpright(body, up, terrainUp, config)
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
        val groundedMaxDistance = axle.suspensionRestLength + axle.wheelRadius + axle.suspensionTravel * 0.35
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
        val compression = (axle.suspensionRestLength - springLength).coerceIn(0.0, axle.suspensionTravel)
        if (compression <= 0.0) return 0.0
        val suspensionUp = VehiclePhysicsMath.safeNormalize(Vector3d(contact.suspensionDirWorld).negate(), WORLD_UP)
        val springVelocity = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.suspensionDirWorld)
        val forceMag = (compression * axle.suspensionStrength + springVelocity * axle.suspensionDamping).coerceAtLeast(0.0)
        val normalForce = forceMag.coerceIn(0.0, MAX_FORCE)
        VehicleWheelPhysics.applyContactForce(body, contact, Vector3d(suspensionUp).mul(normalForce))
        return normalForce
    }

    private fun applyLateralGrip(body: PhysVsBody, loaded: LoadedWheelContact, config: WheeledVehiclePhysicsConfig) {
        val contact = loaded.contact
        if (!contact.grounded || loaded.normalForce <= 0.0) return
        val lateralSpeed = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.wheelRightWorld)
        val forwardSpeed = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.wheelForwardWorld)
        val slip = lateralSpeed / max(abs(forwardSpeed), 1.5)
        val shapedSlip = slip / (abs(slip) + config.lateralSlipShape.coerceAtLeast(1.0e-4))
        val maxForce = loaded.normalForce * config.tireFrictionCoefficient * loaded.wheel.axle.lateralGrip
        VehicleWheelPhysics.applyContactForce(
            body,
            contact,
            Vector3d(contact.wheelRightWorld).mul((-shapedSlip * maxForce).coerceIn(-MAX_FORCE, MAX_FORCE))
        )
    }

    private fun updateWheelLongitudinalPhysics(
        body: PhysVsBody,
        contacts: List<LoadedWheelContact>,
        input: VehicleInput,
        config: WheeledVehiclePhysicsConfig,
        state: WheeledVehicleRuntimeState,
        dt: Double
    ) {
        val throttle = input.throttle.coerceIn(-1.0, 1.0)
        val brake = input.brake.coerceIn(0.0, 1.0).coerceAtLeast(input.handbrake.coerceIn(0.0, 1.0))
        val driveBiasTotal = config.axles.filter(WheelAxleConfig::driven).sumOf { it.driveBias.coerceAtLeast(0.0) }.coerceAtLeast(1.0e-6)
        val brakeBiasTotal = config.axles.sumOf { it.brakeBias.coerceAtLeast(0.0) }.coerceAtLeast(1.0e-6)
        val stepDt = dt.coerceIn(0.0, 0.1)

        contacts.forEach { loaded ->
            val axle = loaded.wheel.axle
            val radius = max(axle.wheelRadius, 0.05)
            val maxOmega = max(config.wheelTopSpeed, 1.0) * MAX_WHEEL_TOP_SPEED_MULTIPLIER / radius
            val groundSpeed = if (loaded.contact.grounded) {
                VehiclePhysicsMath.safeDot(loaded.contact.wheelVelocityWorld, loaded.contact.wheelForwardWorld)
            } else {
                0.0
            }
            var omega = state.wheelAngularVelocityById[loaded.wheel.id]?.takeIf(Double::isFinite) ?: groundSpeed / radius
            val driveShare = if (axle.driven) axle.driveBias.coerceAtLeast(0.0) / driveBiasTotal / 2.0 else 0.0
            val brakeShare = axle.brakeBias.coerceAtLeast(0.0) / brakeBiasTotal / 2.0

            if (axle.driven && throttle != 0.0) {
                val motorTarget = throttle * config.wheelTopSpeed * 1.08 / radius
                val motorAlpha = 1.0 - exp(-stepDt * config.motorResponse * abs(throttle) * (0.5 + driveShare * 2.0))
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
                val forceScale = if (axle.driven || brake > 0.0) 1.0 else 0.35
                val maxForce = loaded.normalForce * config.tireFrictionCoefficient * axle.longitudinalGrip * forceScale
                val rollingForce = (-groundSpeed * config.rollingResistance / contacts.size).coerceIn(-MAX_FORCE, MAX_FORCE)
                val forceMag = (shapedSlip * maxForce + rollingForce).coerceIn(-MAX_FORCE, MAX_FORCE)
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
            state.wheelSpinById[loaded.wheel.id] = (state.wheelSpinById[loaded.wheel.id] ?: 0.0) + omega * stepDt
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
        val maxBrakeForce = grounded.sumOf(LoadedWheelContact::normalForce) * config.tireFrictionCoefficient * config.parkingBrakeStrength
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

    private fun applyUpright(body: PhysVsBody, up: Vector3d, terrainUp: Vector3d, config: WheeledVehiclePhysicsConfig) {
        val axis = Vector3d(up).cross(terrainUp)
        if (!VehiclePhysicsMath.isFinite(axis) || axis.lengthSquared() < VehiclePhysicsMath.MIN_DIRECTION_LENGTH_SQUARED) return
        val correction = axis.mul(config.uprightStrength)
        val damping = Vector3d(body.kinematics.angularVelocity).mul(-config.uprightDamping)
        VehiclePhysicsMath.safeApplyWorldTorque(body, correction.add(damping))
    }

    private fun applyStepAssist(
        body: PhysVsBody,
        physLevel: PhysLevel,
        loaded: LoadedWheelContact,
        forward: Vector3d,
        terrainUp: Vector3d,
        input: VehicleInput,
        config: WheeledVehiclePhysicsConfig
    ) {
        val contact = loaded.contact
        val axle = loaded.wheel.axle
        if (!axle.stepAssist || !contact.grounded || config.maxStepHeight <= 0.0 || config.stepAssistStrength <= 0.0) return
        val terrainForward = computeStepApproachDirection(body, forward, terrainUp, input.throttle)
        if (terrainForward.lengthSquared() < 1.0e-6) return
        val speedIntoStep = max(0.0, VehiclePhysicsMath.safeDot(body.kinematics.velocity, terrainForward))
        val projectedForward = VehiclePhysicsMath.projectOntoPlane(forward, terrainUp, forward)
        val throttleIntoStep = max(0.0, input.throttle.coerceIn(-1.0, 1.0) * VehiclePhysicsMath.safeDot(projectedForward, terrainForward))
        val crawlAmount = throttleIntoStep * (1.0 - smoothstep(0.8, 3.5, speedIntoStep))
        val effectiveSpeed = max(speedIntoStep, crawlAmount * 1.8)
        if (effectiveSpeed < 0.35) return

        val speedLookahead = smoothstep(3.0, config.wheelTopSpeed * 0.85, effectiveSpeed)
        val probeLength = (axle.wheelRadius + 0.42 + effectiveSpeed * 0.08).coerceIn(axle.wheelRadius + 0.3, axle.wheelRadius + 1.45)
        val lowProbeStart = Vector3d(contact.contactPointWorld)
            .fma(axle.wheelRadius * 0.45, terrainUp)
            .fma(0.05 + speedLookahead * 0.2, terrainForward)
        val obstacle = physLevel.rayCast(lowProbeStart, terrainForward, probeLength, body.id) ?: return
        if (obstacle.hitBody.id == body.id) return
        val step = findStepLandingSurface(physLevel, body, contact, lowProbeStart, obstacle.distance, terrainForward, terrainUp, axle, config, speedLookahead) ?: return

        val heightAmount = smoothstep(0.12, config.maxStepHeight, step.rise)
        val targetUpSpeed = (effectiveSpeed * step.rise / max(0.25, step.approachDistance) * (0.5 + speedLookahead * 0.2))
            .coerceIn(0.0, 2.2 + speedLookahead * 2.4)
        val missingUpSpeed = max(0.0, targetUpSpeed - VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, terrainUp))
        val lift = Vector3d(terrainUp).mul(
            (config.mass * missingUpSpeed * (10.0 + speedLookahead * 10.0) + config.stepAssistStrength * (0.18 + heightAmount * 0.2) * crawlAmount)
                .coerceIn(0.0, config.stepAssistStrength * (0.75 + heightAmount * 0.45 + speedLookahead * 1.1))
        )
        val carry = Vector3d(terrainForward).mul(config.stepAssistStrength * (0.02 + speedLookahead * 0.12 + heightAmount * 0.04))
        VehicleWheelPhysics.applyContactForce(body, contact, lift.add(carry))
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
            obstacleDistance + 0.6 + speedLookahead * 0.55
        )
        return forwardDistances.asSequence()
            .filter { it.isFinite() && it > 0.0 }
            .mapNotNull { distance ->
                val topProbeStart = Vector3d(lowProbeStart).fma(distance, terrainForward).fma(stepProbeHeight, terrainUp)
                val topHit = physLevel.rayCast(topProbeStart, down, downProbeLength, body.id) ?: return@mapNotNull null
                if (topHit.hitBody.id == body.id || !topHit.distance.isFinite()) return@mapNotNull null
                val topPoint = Vector3d(topProbeStart).fma(topHit.distance, down)
                val rise = VehiclePhysicsMath.safeDot(Vector3d(topPoint).sub(contact.contactPointWorld), terrainUp)
                if (rise < 0.05 || rise > config.maxStepHeight + axle.wheelRadius * 0.25) return@mapNotNull null
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
        contacts: List<WheelContactState>,
        bodyForward: Vector3d,
        bodyRight: Vector3d,
        dt: Double,
        config: WheeledVehiclePhysicsConfig
    ) {
        val grounded = contacts.filter { it.contact.grounded }
        if (grounded.isEmpty()) return
        val normalAverage = Vector3d()
        grounded.forEach { normalAverage.add(it.contact.contactNormalWorld) }
        var rawNormal = VehiclePhysicsMath.safeNormalize(normalAverage, state.smoothedGroundNormal)
        computeSupportGroundNormal(contacts, bodyForward, bodyRight, rawNormal)?.let { supportNormal ->
            rawNormal = VehiclePhysicsMath.safeNormalize(Vector3d(rawNormal).mul(0.7).fma(0.3, supportNormal), rawNormal)
        }
        val smoothingTime = config.groundNormalSmoothingTime.takeIf { it.isFinite() && it > 1.0e-4 } ?: 0.12
        val alpha = 1.0 - exp(-dt.coerceIn(0.0, 0.1) / smoothingTime)
        state.smoothedGroundNormal.set(VehiclePhysicsMath.safeNormalize(Vector3d(state.smoothedGroundNormal).lerp(rawNormal, alpha), WORLD_UP))
    }

    private fun computeSupportGroundNormal(
        contacts: List<WheelContactState>,
        bodyForward: Vector3d,
        bodyRight: Vector3d,
        fallbackNormal: Vector3d
    ): Vector3d? {
        val hitContacts = contacts.filter { it.contact.hitBody != null && VehiclePhysicsMath.isFinite(it.contact.contactPointWorld) }
        if (hitContacts.size < 3) return null
        val terrainForward = VehiclePhysicsMath.projectOntoPlane(bodyForward, fallbackNormal, bodyForward)
        val stableRight = VehiclePhysicsMath.safeNormalize(Vector3d(fallbackNormal).cross(terrainForward), bodyRight)
        val front = hitContacts.maxByOrNull { it.wheel.axle.localZ } ?: return null
        val rear = hitContacts.minByOrNull { it.wheel.axle.localZ } ?: return null
        val left = hitContacts.minByOrNull { it.wheel.side } ?: return null
        val right = hitContacts.maxByOrNull { it.wheel.side } ?: return null
        val forwardSpan = Vector3d(front.contact.contactPointWorld).sub(rear.contact.contactPointWorld)
        val rightSpan = Vector3d(right.contact.contactPointWorld).sub(left.contact.contactPointWorld)
        if (forwardSpan.lengthSquared() < 1.0e-6) return null
        if (rightSpan.lengthSquared() < 1.0e-6) rightSpan.set(stableRight)
        val support = Vector3d(forwardSpan).cross(rightSpan)
        if (!VehiclePhysicsMath.isFinite(support) || support.lengthSquared() < 1.0e-6) return null
        if (VehiclePhysicsMath.safeDot(support, fallbackNormal) < 0.0) support.negate()
        val normal = VehiclePhysicsMath.safeNormalize(support, fallbackNormal)
        return normal.takeIf { VehiclePhysicsMath.safeDot(it, fallbackNormal) > 0.48 }
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
        state.frontWheelAngularVelocity = averageByWheel(frontWheels, state.wheelAngularVelocityById)
        state.rearWheelAngularVelocity = averageByWheel(rearWheels, state.wheelAngularVelocityById)
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

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        if (edge0 == edge1) return if (value < edge0) 0.0 else 1.0
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun lerp(from: Double, to: Double, alpha: Double): Double {
        return from + (to - from) * alpha.coerceIn(0.0, 1.0)
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
