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
    private const val WHEEL_INERTIA_MASS_SCALE = 0.018
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
            computeTargetSteerRad(input.steer, forwardSpeed, config),
            dt,
            config
        )
        val contacts = config.wheelLocalPositions.mapIndexed { index, localPos ->
            sampleWheel(body, physLevel, localPos, index < 2, steerRad, config, dt)
        }
        val groundedContacts = contacts.filter(VehicleWheelContact::grounded)
        val grounded = groundedContacts.isNotEmpty()
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
        updateVisualWheelState(state, contacts, config, dt)
        state.debugLateralSlip = averageLateralSlip(appliedContacts)

        appliedContacts.forEach { contact ->
            applyLateralGrip(body, contact, driftGripActive, config)
        }

        applyWheelLongitudinalPhysics(body, appliedContacts, forwardSpeed, input, driftGripActive, steerRad, state, config, dt)

        if (input.riderPresent) {
            if (grounded) {
                if (state.drifting) {
                    applyDriftAssist(body, input, forwardSpeed, WORLD_UP, state, config)
                } else {
                    applySteeringAssist(body, steerRad, forwardSpeed, WORLD_UP, config)
                }
            }
        }

        if (grounded) {
            applyUpright(body, up, WORLD_UP, config)
        }
        dampAngularVelocity(body)
    }

    private fun sampleWheel(
        body: PhysVsBody,
        physLevel: PhysLevel,
        wheelLocalPos: Vector3dc,
        front: Boolean,
        steerRad: Double,
        config: KartPhysicsConfig,
        dt: Double
    ): VehicleWheelContact {
        val mountWorld = body.kinematics.transform.toWorld.transformPosition(Vector3d(wheelLocalPos))
        val castDir = Vector3d(WORLD_UP).negate()
        val maxLength = config.suspensionRestLength + config.suspensionTravel + config.wheelRadius
        val groundedMaxDistance = config.suspensionRestLength + config.wheelRadius - 1.0e-4
        val baseForward = VehiclePhysicsMath.projectOntoPlane(
            VehiclePhysicsMath.transformDirection(body, LOCAL_FORWARD, LOCAL_FORWARD),
            WORLD_UP,
            LOCAL_FORWARD
        )
        val wheelForward = if (front && steerRad != 0.0) {
            VehiclePhysicsMath.safeNormalize(Vector3d(baseForward).rotateAxis(steerRad, WORLD_UP.x, WORLD_UP.y, WORLD_UP.z), baseForward)
        } else {
            baseForward
        }
        val wheelRight = VehiclePhysicsMath.safeNormalize(
            Vector3d(WORLD_UP).cross(wheelForward),
            VehiclePhysicsMath.transformDirection(body, LOCAL_RIGHT, LOCAL_RIGHT)
        )
        val sweepOffset = Vector3d(body.kinematics.velocity)
            .sub(Vector3d(WORLD_UP).mul(VehiclePhysicsMath.safeDot(body.kinematics.velocity, WORLD_UP)))
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

        val springVelocity = -VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, WORLD_UP)
        val forceMag = compression * config.suspensionStrength + springVelocity * config.suspensionDamping
        val normalForce = forceMag.coerceAtLeast(0.0)
        VehicleWheelPhysics.applyContactForce(body, contact, Vector3d(WORLD_UP).mul(normalForce))
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

    private fun applyWheelLongitudinalPhysics(
        body: PhysVsBody,
        contacts: List<KartContact>,
        forwardSpeed: Double,
        input: VehicleInput,
        drifting: Boolean,
        steerRad: Double,
        state: KartRuntimeState,
        config: KartPhysicsConfig,
        dt: Double
    ) {
        val throttle = input.throttle.coerceIn(-1.0, 1.0)
        val usePlanarLimit = drifting || abs(input.steer) > 0.05 || abs(steerRad) > Math.toRadians(1.0)
        val driveLimitSpeed = if (usePlanarLimit) {
            val direction = forwardSpeed.signOrZero().takeIf { it != 0.0 } ?: throttle.signOrZero()
            planarSpeed(body, WORLD_UP) * direction
        } else {
            forwardSpeed
        }
        val topSpeed = if (drifting) config.wheelTopSpeed * config.driftTopSpeedMultiplier else config.wheelTopSpeed
        val speedLimitScale = computeSpeedLimitScale(driveLimitSpeed, throttle, topSpeed, config)
        val driveScale = if (drifting) config.driftDriveScale else 1.0
        val rawBrake = input.brake.coerceIn(0.0, 1.0).coerceAtLeast(input.handbrake.coerceIn(0.0, 1.0))
        val brake = if (drifting) rawBrake * config.driftBrakeScale else rawBrake

        state.frontWheelAngularVelocity = applyWheelGroupLongitudinalPhysics(
            body = body,
            contacts = contacts.filter(KartContact::front),
            angularVelocity = state.frontWheelAngularVelocity,
            throttle = 0.0,
            brakeInput = brake,
            driven = false,
            forceScale = 1.0,
            topSpeed = topSpeed,
            config = config,
            dt = dt
        )
        state.rearWheelAngularVelocity = applyWheelGroupLongitudinalPhysics(
            body = body,
            contacts = contacts.filter { !it.front },
            angularVelocity = state.rearWheelAngularVelocity,
            throttle = throttle * speedLimitScale,
            brakeInput = brake,
            driven = true,
            forceScale = driveScale,
            topSpeed = topSpeed,
            config = config,
            dt = dt
        )
    }

    private fun applyWheelGroupLongitudinalPhysics(
        body: PhysVsBody,
        contacts: List<KartContact>,
        angularVelocity: Double,
        throttle: Double,
        brakeInput: Double,
        driven: Boolean,
        forceScale: Double,
        topSpeed: Double,
        config: KartPhysicsConfig,
        dt: Double
    ): Double {
        if (contacts.isEmpty()) return angularVelocity.takeIf(Double::isFinite) ?: 0.0
        val stepDt = dt.coerceIn(0.0, 0.1)
        val radius = max(config.wheelRadius, 0.05)
        val maxOmega = max(topSpeed, 1.0) * MAX_WHEEL_TOP_SPEED_MULTIPLIER / radius
        val grounded = contacts.filter { it.contact.grounded && it.normalForce > 0.0 }
        val averageGroundSpeed = if (grounded.isNotEmpty()) {
            grounded.sumOf { kartContact ->
                VehiclePhysicsMath.safeDot(kartContact.contact.wheelVelocityWorld, kartContact.contact.wheelForwardWorld)
            } / grounded.size
        } else {
            0.0
        }
        var omega = if (angularVelocity.isFinite()) angularVelocity else averageGroundSpeed / radius

        if (driven && throttle != 0.0) {
            val motorTargetOmega = throttle.coerceIn(-1.0, 1.0) * topSpeed * 1.08 / radius
            val motorResponse = (config.driveForce / max(config.mass, 1.0) * 0.55).coerceIn(4.0, 22.0)
            val motorAlpha = 1.0 - exp(-stepDt * motorResponse * abs(throttle))
            omega = lerp(omega, motorTargetOmega, motorAlpha)
        }

        val brake = brakeInput.coerceIn(0.0, 1.0)
        if (brake > 0.0) {
            val brakeResponse = (config.brakeForce / max(config.mass, 1.0) * 1.3).coerceIn(5.0, 32.0)
            val brakeAlpha = 1.0 - exp(-stepDt * brakeResponse * brake)
            omega = lerp(omega, 0.0, brakeAlpha)
        }

        val drag = if (grounded.isNotEmpty()) WHEEL_GROUND_DRAG else WHEEL_AIR_DRAG
        omega *= exp(-drag * stepDt)

        grounded.forEach { kartContact ->
            val contact = kartContact.contact
            val groundSpeed = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.wheelForwardWorld)
            val surfaceSpeed = omega * radius
            val slipVelocity = surfaceSpeed - groundSpeed
            val slipRatio = slipVelocity / max(abs(groundSpeed), 1.5)
            val gripFactor = longitudinalGrip(slipRatio)
            val brakeForceScale = if (brake > 0.0) {
                lerp(1.0, max(config.brakeForce / max(config.driveForce, 1.0), 1.0), brake)
            } else {
                1.0
            }
            val maxLongitudinalForce = kartContact.normalForce *
                config.tireFrictionCoefficient *
                config.longitudinalGrip *
                forceScale *
                brakeForceScale
            val forceMag = (gripFactor * maxLongitudinalForce).coerceIn(-MAX_FORCE, MAX_FORCE)

            VehicleWheelPhysics.applyContactForce(body, contact, Vector3d(contact.wheelForwardWorld).mul(forceMag))

            val wheelInertia = max(0.08, config.mass * radius * radius * WHEEL_INERTIA_MASS_SCALE / contacts.size)
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

    private fun applySteeringAssist(
        body: PhysVsBody,
        steerRad: Double,
        forwardSpeed: Double,
        terrainUp: Vector3d,
        config: KartPhysicsConfig
    ) {
        val steer = (steerRad / config.maxSteerRad).coerceIn(-1.0, 1.0)
        val speed = abs(forwardSpeed)
        if (abs(steer) < 1.0e-4 || speed < config.yawAssistMinSpeed) return
        val speedT = smoothstep(config.yawAssistMinSpeed, config.yawAssistMaxSpeed, speed)
        val torque = Vector3d(terrainUp).mul(steer * forwardSpeed.signOrZero() * config.yawAssist * speedT)
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
        targetSteerRad: Double,
        dt: Double,
        config: KartPhysicsConfig
    ): Double {
        val smoothingTime = config.steerSmoothingTime.takeIf { it.isFinite() && it > 1.0e-4 } ?: 0.1
        val alpha = 1.0 - exp(-dt.coerceIn(0.0, 0.1) / smoothingTime)
        state.smoothedSteerRad = lerp(state.smoothedSteerRad, targetSteerRad, alpha)
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

    private fun longitudinalGrip(slip: Double): Double {
        val x = slip.coerceIn(-3.0, 3.0)
        return x / (abs(x) + 0.22)
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
}
