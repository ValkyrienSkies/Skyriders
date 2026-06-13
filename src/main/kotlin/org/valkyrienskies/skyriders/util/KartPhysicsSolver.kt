package org.valkyrienskies.skyriders.util

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.skyriders.content.KartPhysicsConfig
import org.valkyrienskies.skyriders.content.KartRuntimeState
import org.valkyrienskies.skyriders.content.VehicleDamage
import org.valkyrienskies.skyriders.content.VehicleInput
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import org.valkyrienskies.skyriders.content.VehicleTransmissionConfig
import org.valkyrienskies.skyriders.content.VehicleTransmissionGearConfig
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
    private const val PARKING_BRAKE_DEAD_SPEED = 0.025
    private const val LATERAL_SAMPLE_TIE_BREAK_BIAS = 0.02
    private const val SWEEP_SAMPLE_TIE_BREAK_BIAS = 0.005
    private const val MIN_STEP_ASSIST_RISE = 0.35
    private const val STEP_SIDE_PROBE_BIAS = 0.025

    fun updateKartPhysics(
        body: PhysVsBody,
        physLevel: PhysLevel,
        input: VehicleInput,
        config: KartPhysicsConfig,
        state: KartRuntimeState,
        dt: Double
    ) {
        state.transmissionShiftCooldown = max(0.0, state.transmissionShiftCooldown - dt.coerceIn(0.0, 0.1))
        val forward = VehiclePhysicsMath.transformDirection(body, LOCAL_FORWARD, LOCAL_FORWARD)
        val right = VehiclePhysicsMath.transformDirection(body, LOCAL_RIGHT, LOCAL_RIGHT)
        val up = VehiclePhysicsMath.transformDirection(body, LOCAL_UP, LOCAL_UP)
        val activeInput = if (input.riderPresent) input else VehicleInput.EMPTY
        val tractionScale = VehicleStatusEffects.tractionScale(physLevel.dimension, body.id) *
            VehicleDamage.tractionScale(physLevel.dimension, body.id)
        val topSpeedMultiplier = VehicleStatusEffects.topSpeedMultiplier(physLevel.dimension, body.id)
        val forwardSpeed = VehiclePhysicsMath.safeDot(body.kinematics.velocity, forward)
        val contactUp = VehiclePhysicsMath.safeNormalize(state.smoothedGroundNormal, WORLD_UP)
        val driftSpeed = planarSpeed(body, contactUp)
        val driftGripActive = updateDriftState(state, activeInput, driftSpeed, dt, config)
        updateDriftBoost(body, state, activeInput.riderPresent, state.drifting, forward, config, dt)
        val steerRad = updateSteerAngle(
            state,
            activeInput.steer,
            forwardSpeed,
            computeTargetSteerRad(activeInput.steer, forwardSpeed, config),
            dt,
            config
        )
        val contacts = config.wheelLocalPositions.mapIndexed { index, localPos ->
            val contact = sampleWheel(body, physLevel, localPos, index < 2, contactUp, steerRad, config, dt)
            if (VehicleDamage.isWheelDestroyed(physLevel.dimension, body.id, kartWheelPartId(index))) {
                contact.withoutWheelPhysics()
            } else {
                contact
            }
        }
        val groundedContacts = contacts.filter(VehicleWheelContact::grounded)
        val grounded = groundedContacts.isNotEmpty()
        if (grounded) {
            state.groundedGraceTimeRemaining = config.groundedGraceTime
        } else {
            state.groundedGraceTimeRemaining = max(0.0, state.groundedGraceTimeRemaining - dt)
        }
        val stabilizedGrounded = grounded || state.groundedGraceTimeRemaining > 0.0
        if (grounded) {
            smoothGroundNormal(state, contacts, forward, right, state.drifting, dt, config)
        }
        val terrainUp = VehiclePhysicsMath.safeNormalize(state.smoothedGroundNormal, WORLD_UP)
        val speed = body.kinematics.velocity.length()

        state.debugSpeed = if (speed.isFinite()) speed else 0.0
        state.debugForwardSpeed = if (forwardSpeed.isFinite()) forwardSpeed else 0.0
        state.debugGroundedWheels = groundedContacts.size
        state.debugSteerRad = steerRad
        state.debugThrottle = activeInput.throttle
        state.debugDriveWork = 0.0
        state.debugStepAssistWork = 0.0

        val appliedContacts = contacts.mapIndexed { index, contact ->
            KartContact(
                contact = contact,
                front = index < 2,
                normalForce = applySuspension(body, contact, config)
            )
        }
        state.debugLateralSlip = averageLateralSlip(appliedContacts)

        appliedContacts.forEach { contact ->
            applyLateralGrip(body, contact, driftGripActive, config, tractionScale)
        }

        if (activeInput.riderPresent) {
            applyDriveAndBrake(
                body,
                appliedContacts,
                forwardSpeed,
                terrainUp,
                activeInput,
                driftGripActive,
                steerRad,
                config,
                state,
                tractionScale,
                topSpeedMultiplier
            )
            if (stabilizedGrounded) {
                val activeStepWheels = contacts.count(VehicleWheelContact::grounded).coerceAtLeast(1)
                contacts.forEach { contact ->
                    state.debugStepAssistWork += applyStepAssist(body, physLevel, contact, forward, terrainUp, activeInput, config, activeStepWheels)
                }
                if (state.drifting) {
                    applyDriftAssist(body, activeInput, forwardSpeed, terrainUp, state, config)
                } else {
                    applySteeringAssist(body, steerRad, forwardSpeed, terrainUp, config)
                }
            }
        } else {
            applyParkingBrake(body, appliedContacts, terrainUp, config)
        }

        if (stabilizedGrounded) {
            applyUpright(body, up, terrainUp, state.drifting, config)
        }
        dampAngularVelocity(body)
        val wheelInput = if (activeInput.riderPresent) activeInput else VehicleInput(handbrake = 1.0)
        updateWheelAngularVelocities(state, appliedContacts, wheelInput, driftGripActive && activeInput.riderPresent, config, dt, topSpeedMultiplier)
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
        val droopGroundedRange = config.suspensionTravel * config.suspensionDroopGroundedFraction.coerceIn(0.0, 1.0)
        val groundedMaxDistance = config.suspensionRestLength + config.wheelRadius + droopGroundedRange
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
        val lateralOffsets = listOf(0.0, -config.wheelSampleWidth * 0.5, config.wheelSampleWidth * 0.5)
        val samples = lateralOffsets.flatMap { lateralOffset ->
            val base = Vector3d(mountWorld).fma(lateralOffset, wheelRight)
            sweepSamples.map { sweep ->
                WheelSample(
                    position = Vector3d(base).add(sweep),
                    lateralOffset = abs(lateralOffset),
                    sweepOffset = sweep.length()
                )
            }
        }
        return samples
            .map { sample ->
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
            }
            .filter { it.contact.grounded }
            .minByOrNull { sample ->
                sample.contact.hitDistance +
                    sample.lateralOffset * LATERAL_SAMPLE_TIE_BREAK_BIAS +
                    sample.sweepOffset * SWEEP_SAMPLE_TIE_BREAK_BIAS
            }
            ?.contact
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

    private fun kartWheelPartId(index: Int): String = when (index) {
        0 -> "front_left_wheel"
        1 -> "front_right_wheel"
        2 -> "rear_left_wheel"
        3 -> "rear_right_wheel"
        else -> ""
    }

    private fun VehicleWheelContact.withoutWheelPhysics(): VehicleWheelContact {
        return copy(
            grounded = false,
            hitBody = null,
            compressionDistance = 0.0,
            surfaceVelocityWorld = Vector3d(),
            relativeWheelVelocityWorld = Vector3d(wheelVelocityWorld)
        )
    }

    private fun applySuspension(body: PhysVsBody, contact: VehicleWheelContact, config: KartPhysicsConfig): Double {
        if (!contact.grounded) return 0.0
        val springLength = contact.hitDistance - config.wheelRadius
        val compression = config.suspensionRestLength - springLength
        val compressed = compression.coerceIn(0.0, config.suspensionTravel)
        val droop = (springLength - config.suspensionRestLength).coerceAtLeast(0.0)
        val droopRange = config.suspensionTravel * config.suspensionDroopGroundedFraction.coerceIn(0.0, 1.0)
        val droopLoad = if (compressed <= 0.0 && droopRange > 1.0e-4) {
            val droopT = (droop / droopRange).coerceIn(0.0, 1.0)
            val preload = config.mass * 9.81 * 0.25 * config.suspensionDroopPreloadFraction.coerceIn(0.0, 1.0)
            preload * (1.0 - droopT)
        } else {
            0.0
        }
        if (compressed <= 0.0 && droopLoad <= 0.0) return 0.0

        val suspensionUp = VehiclePhysicsMath.safeNormalize(Vector3d(contact.suspensionDirWorld).negate(), WORLD_UP)
        val springVelocity = VehiclePhysicsMath.safeDot(contact.relativeWheelVelocityWorld, contact.suspensionDirWorld)
        val dampingScale = if (compressed > 0.0) 1.0 else 0.35
        val forceMag = compressed * config.suspensionStrength + droopLoad + springVelocity * config.suspensionDamping * dampingScale
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

    private fun applyLateralGrip(
        body: PhysVsBody,
        kartContact: KartContact,
        drifting: Boolean,
        config: KartPhysicsConfig,
        tractionScale: Double
    ) {
        val contact = kartContact.contact
        if (!contact.grounded || kartContact.normalForce <= 0.0) return

        val lateralSpeed = VehiclePhysicsMath.safeDot(contact.relativeWheelVelocityWorld, contact.wheelRightWorld)
        val forwardSpeed = VehiclePhysicsMath.safeDot(contact.relativeWheelVelocityWorld, contact.wheelForwardWorld)
        val baseGrip = if (kartContact.front) config.frontLateralGrip else config.rearLateralGrip
        val grip = if (!drifting) {
            baseGrip
        } else if (kartContact.front) {
            baseGrip * config.driftFrontGripScale
        } else {
            baseGrip * config.driftRearGripScale
        }
        val maxLateralForce = (kartContact.normalForce * config.tireFrictionCoefficient * contact.surfaceFriction * grip * tractionScale)
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
        config: KartPhysicsConfig,
        state: KartRuntimeState,
        tractionScale: Double,
        topSpeedMultiplier: Double
    ) {
        val driveCommand = updateKartDriveCommand(input, forwardSpeed, config, state, topSpeedMultiplier)
        val rearContacts = contacts.filter { !it.front && it.contact.grounded && it.normalForce > 0.0 }
        if (rearContacts.isEmpty()) return

        val throttle = driveCommand.throttle
        val usePlanarLimit = drifting || abs(input.steer) > 0.05 || abs(steerRad) > Math.toRadians(1.0)
        val driveLimitSpeed = if (usePlanarLimit) {
            val direction = forwardSpeed.signOrZero().takeIf { it != 0.0 } ?: throttle.signOrZero()
            planarSpeed(body, terrainUp) * direction
        } else {
            forwardSpeed
        }
        val topSpeed = if (drifting) driveCommand.topSpeed * config.driftTopSpeedMultiplier else driveCommand.topSpeed
        val speedLimitScale = computeSpeedLimitScale(driveLimitSpeed, throttle, topSpeed, config)
        val driveScale = if (drifting) config.driftDriveScale else 1.0
        val driveForce = throttle * config.driveForce * driveCommand.torqueMultiplier * speedLimitScale * driveScale / rearContacts.size
        rearContacts.forEach { kartContact ->
            if (driveForce != 0.0) {
                val contact = kartContact.contact
                val maxDriveForce = kartContact.normalForce *
                    config.tireFrictionCoefficient *
                    contact.surfaceFriction *
                    config.longitudinalGrip *
                    tractionScale
                val limitedDriveForce = driveForce.coerceIn(-maxDriveForce, maxDriveForce)
                val drivePower = limitedDriveForce * VehiclePhysicsMath.safeDot(contact.relativeWheelVelocityWorld, contact.wheelForwardWorld)
                if (drivePower > 0.0) {
                    state.debugDriveWork += drivePower / max(config.mass, 1.0)
                }
                VehicleWheelPhysics.applyContactForce(
                    body,
                    contact,
                    Vector3d(contact.wheelForwardWorld).mul(limitedDriveForce)
                )
            }
        }

        val rawBrake = driveCommand.brake
        val brake = if (drifting) rawBrake * config.driftBrakeScale else rawBrake
        contacts.filter { it.contact.grounded && it.normalForce > 0.0 }.forEach { kartContact ->
            val contact = kartContact.contact
            val wheelForwardSpeed = VehiclePhysicsMath.safeDot(contact.relativeWheelVelocityWorld, contact.wheelForwardWorld)
            val rollingScale = if (drifting) 0.35 else 1.0
            val rollingForce = (-wheelForwardSpeed * config.rollingResistance * rollingScale * tractionScale / contacts.size).coerceIn(-MAX_FORCE, MAX_FORCE)
            if (rollingForce != 0.0) {
                VehicleWheelPhysics.applyContactForce(body, contact, Vector3d(contact.wheelForwardWorld).mul(rollingForce))
            }

            if (brake > 0.0) {
                val maxBrakeForce = kartContact.normalForce *
                    config.tireFrictionCoefficient *
                    contact.surfaceFriction *
                    config.longitudinalGrip *
                    tractionScale
                val brakeForce = (-wheelForwardSpeed * config.brakeForce * brake).coerceIn(-maxBrakeForce, maxBrakeForce)
                VehicleWheelPhysics.applyContactForce(body, contact, Vector3d(contact.wheelForwardWorld).mul(brakeForce))
            }
        }
    }

    private fun applyParkingBrake(
        body: PhysVsBody,
        contacts: List<KartContact>,
        terrainUp: Vector3d,
        config: KartPhysicsConfig
    ) {
        val groundedContacts = contacts.filter { it.contact.grounded && it.normalForce > 0.0 }
        if (groundedContacts.isEmpty()) return

        val damping = (config.brakeForce / max(config.mass, 1.0) * config.parkingBrakeStrength * 0.18)
            .coerceIn(3.0, 18.0)
        val forceShareMass = config.mass / groundedContacts.size
        groundedContacts.forEach { kartContact ->
            val contact = kartContact.contact
            val relative = contact.relativeWheelVelocityWorld
            val planarVelocity = Vector3d(relative).fma(-VehiclePhysicsMath.safeDot(relative, terrainUp), terrainUp)
            if (!VehiclePhysicsMath.isFinite(planarVelocity)) return@forEach

            val planarSpeed = planarVelocity.length()
            if (!planarSpeed.isFinite() || planarSpeed <= PARKING_BRAKE_DEAD_SPEED) return@forEach

            val maxBrakeForce = kartContact.normalForce *
                contact.surfaceFriction *
                config.tireFrictionCoefficient *
                config.longitudinalGrip *
                config.parkingBrakeStrength
            val brakeForce = VehiclePhysicsMath.safeNormalize(planarVelocity, Vector3d())
                .mul((-planarSpeed * forceShareMass * damping).coerceIn(-maxBrakeForce, 0.0))

            VehicleWheelPhysics.applyContactForce(body, contact, brakeForce)
        }
    }

    private fun applyGroundReactionForSharedForce(
        body: PhysVsBody,
        contacts: List<KartContact>,
        force: Vector3dc
    ) {
        val dynamicContacts = contacts.filter { kartContact ->
            val hitBody = kartContact.contact.hitBody
            hitBody != null && hitBody.id != body.id && !hitBody.isStatic
        }
        if (dynamicContacts.isEmpty()) return

        val sharedReaction = Vector3d(force).negate().div(dynamicContacts.size.toDouble())
        dynamicContacts.forEach { kartContact ->
            VehiclePhysicsMath.safeApplyWorldForce(
                kartContact.contact.hitBody!!,
                sharedReaction,
                kartContact.contact.contactPointWorld
            )
        }
    }

    private fun updateWheelAngularVelocities(
        state: KartRuntimeState,
        contacts: List<KartContact>,
        input: VehicleInput,
        drifting: Boolean,
        config: KartPhysicsConfig,
        dt: Double,
        topSpeedMultiplier: Double
    ) {
        val wheelTopSpeed = config.wheelTopSpeed * topSpeedMultiplier.coerceAtLeast(1.0)
        val topSpeed = if (drifting) wheelTopSpeed * config.driftTopSpeedMultiplier else wheelTopSpeed
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

    private fun applyUpright(body: PhysVsBody, up: Vector3d, terrainUp: Vector3d, drifting: Boolean, config: KartPhysicsConfig) {
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
        contact: VehicleWheelContact,
        forward: Vector3d,
        terrainUp: Vector3d,
        input: VehicleInput,
        config: KartPhysicsConfig,
        activeStepWheels: Int
    ): Double {
        if (!contact.grounded) return 0.0
        if (config.maxStepHeight <= 0.0 || config.stepAssistStrength <= 0.0) return 0.0

        val terrainForward = computeStepApproachDirection(body, forward, terrainUp, input.throttle)
        if (terrainForward.lengthSquared() < 1.0e-6) return 0.0

        val speedIntoStep = max(0.0, VehiclePhysicsMath.safeDot(body.kinematics.velocity, terrainForward))
        val projectedForward = VehiclePhysicsMath.projectOntoPlane(forward, terrainUp, forward)
        val throttleIntoStep = max(0.0, input.throttle.coerceIn(-1.0, 1.0) * VehiclePhysicsMath.safeDot(projectedForward, terrainForward))
        val crawlAmount = throttleIntoStep * (1.0 - smoothstep(0.8, 3.5, speedIntoStep))
        val effectiveStepSpeed = max(speedIntoStep, crawlAmount * 1.8)
        if (effectiveStepSpeed < 0.35) return 0.0

        val speedLookahead = smoothstep(3.0, config.wheelTopSpeed * 0.85, effectiveStepSpeed)
        val probeLength = (config.wheelRadius + 0.55 + effectiveStepSpeed * 0.11)
            .coerceIn(config.wheelRadius + 0.45, config.wheelRadius + 2.15)
        val lowProbeStart = Vector3d(contact.contactPointWorld)
            .fma(config.wheelRadius * 0.45, terrainUp)
            .fma(0.16 + speedLookahead * 0.36, terrainForward)
        val sideProbeDistance = config.wheelSampleWidth * 0.7 + 0.08
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
            .coerceIn(0.0, 2.4 + speedLookahead * 2.8)
        val currentUpSpeed = VehiclePhysicsMath.safeDot(contact.relativeWheelVelocityWorld, terrainUp)
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

        val assistForce = liftForce.add(forwardForce)
        VehicleWheelPhysics.applyContactForce(body, contact, assistForce)
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
        wheelRight: Vector3d,
        sideProbeDistance: Double,
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
                val rise = VehiclePhysicsMath.safeDot(Vector3d(topPoint).sub(contact.contactPointWorld), terrainUp)
                val normalDot = VehiclePhysicsMath.safeDot(topHit.hitNormal, terrainUp)
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

    private fun smoothGroundNormal(
        state: KartRuntimeState,
        contacts: List<VehicleWheelContact>,
        bodyForward: Vector3d,
        bodyRight: Vector3d,
        drifting: Boolean,
        dt: Double,
        config: KartPhysicsConfig
    ) {
        val groundedContacts = contacts.filter(VehicleWheelContact::grounded)
        if (groundedContacts.isEmpty()) return

        val normalAverage = Vector3d()
        groundedContacts.forEach { normalAverage.add(it.contactNormalWorld) }
        var rawNormal = VehiclePhysicsMath.safeNormalize(normalAverage, state.smoothedGroundNormal)
        val supportNormal = computeSupportGroundNormal(contacts, bodyForward, bodyRight, rawNormal)
        if (supportNormal != null) {
            val supportWeight = computeSupportNormalWeight(supportNormal, drifting)
            if (supportWeight > 0.0) {
                rawNormal = VehiclePhysicsMath.safeNormalize(
                    Vector3d(rawNormal).mul(1.0 - supportWeight).fma(supportWeight, supportNormal.normal),
                    rawNormal
                )
            }
        }

        val baseSmoothingTime = config.groundNormalSmoothingTime.takeIf { it.isFinite() && it > 1.0e-4 } ?: 0.12
        val smoothingTime = baseSmoothingTime * if (drifting) 1.25 else 1.0
        val alpha = 1.0 - exp(dt.coerceIn(0.0, 0.1) / -smoothingTime)
        state.smoothedGroundNormal.set(
            VehiclePhysicsMath.safeNormalize(Vector3d(state.smoothedGroundNormal).lerp(rawNormal, alpha), WORLD_UP)
        )
    }

    private fun computeSupportGroundNormal(
        contacts: List<VehicleWheelContact>,
        bodyForward: Vector3d,
        bodyRight: Vector3d,
        fallbackNormal: Vector3d
    ): SupportGroundNormal? {
        if (contacts.size < 4) return null

        val frontContacts = contacts.take(2).filter { it.hasSupportHit() }
        val rearContacts = contacts.drop(2).filter { it.hasSupportHit() }
        val frontPoints = frontContacts.map(VehicleWheelContact::contactPointWorld)
        val rearPoints = rearContacts.map(VehicleWheelContact::contactPointWorld)
        if (frontPoints.isEmpty() || rearPoints.isEmpty()) return null

        val terrainForward = VehiclePhysicsMath.projectOntoPlane(bodyForward, fallbackNormal, bodyForward)
        val stableRight = VehiclePhysicsMath.safeNormalize(Vector3d(fallbackNormal).cross(terrainForward), bodyRight)

        val frontCenter = averagePoint(frontPoints)
        val rearCenter = averagePoint(rearPoints)
        val forwardSpan = Vector3d(frontCenter).sub(rearCenter)
        if (forwardSpan.lengthSquared() < 1.0e-6) return null
        val projectedForwardSpan = Vector3d(forwardSpan)
            .sub(Vector3d(fallbackNormal).mul(VehiclePhysicsMath.safeDot(forwardSpan, fallbackNormal)))
        if (
            VehiclePhysicsMath.isFinite(projectedForwardSpan) &&
            projectedForwardSpan.lengthSquared() > 1.0e-6 &&
            abs(VehiclePhysicsMath.safeDot(VehiclePhysicsMath.safeNormalize(projectedForwardSpan, terrainForward), terrainForward)) < 0.55
        ) {
            return null
        }

        val leftContacts = listOfNotNull(
            contacts.getOrNull(0)?.takeIf { it.hasSupportHit() },
            contacts.getOrNull(2)?.takeIf { it.hasSupportHit() }
        )
        val rightContacts = listOfNotNull(
            contacts.getOrNull(1)?.takeIf { it.hasSupportHit() },
            contacts.getOrNull(3)?.takeIf { it.hasSupportHit() }
        )
        val leftPoints = leftContacts.map(VehicleWheelContact::contactPointWorld)
        val rightPoints = rightContacts.map(VehicleWheelContact::contactPointWorld)
        val rightSpan = if (leftPoints.isNotEmpty() && rightPoints.isNotEmpty()) {
            Vector3d(averagePoint(rightPoints)).sub(averagePoint(leftPoints))
        } else {
            Vector3d(stableRight)
        }
        if (rightSpan.lengthSquared() < 1.0e-6) return null
        val projectedRightSpan = Vector3d(rightSpan)
            .sub(Vector3d(fallbackNormal).mul(VehiclePhysicsMath.safeDot(rightSpan, fallbackNormal)))
        if (
            VehiclePhysicsMath.isFinite(projectedRightSpan) &&
            projectedRightSpan.lengthSquared() > 1.0e-6 &&
            abs(VehiclePhysicsMath.safeDot(VehiclePhysicsMath.safeNormalize(projectedRightSpan, stableRight), stableRight)) < 0.45
        ) {
            return null
        }

        val forwardRise = abs(VehiclePhysicsMath.safeDot(forwardSpan, fallbackNormal))
        val rightRise = abs(VehiclePhysicsMath.safeDot(rightSpan, fallbackNormal))
        val supportRise = max(forwardRise, rightRise)
        if (supportRise < 0.06) return null

        val supportNormal = Vector3d(forwardSpan).cross(rightSpan)
        if (!VehiclePhysicsMath.isFinite(supportNormal) || supportNormal.lengthSquared() < 1.0e-6) return null
        if (VehiclePhysicsMath.safeDot(supportNormal, fallbackNormal) < 0.0) {
            supportNormal.negate()
        }
        val normalizedSupport = VehiclePhysicsMath.safeNormalize(supportNormal, fallbackNormal)
        if (VehiclePhysicsMath.safeDot(normalizedSupport, fallbackNormal) < 0.48) return null

        return SupportGroundNormal(
            normal = normalizedSupport,
            rise = supportRise,
            allGrounded = contacts.all(VehicleWheelContact::grounded)
        )
    }

    private fun computeSupportNormalWeight(supportNormal: SupportGroundNormal, drifting: Boolean): Double {
        val riseAmount = smoothstep(0.06, 0.5, supportNormal.rise)
        val loadScale = if (supportNormal.allGrounded) 1.0 else 0.45
        val driftScale = if (drifting) 0.75 else 1.0
        return (0.34 * riseAmount * loadScale * driftScale).coerceIn(0.0, 0.34)
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

    private fun updateKartDriveCommand(
        input: VehicleInput,
        forwardSpeed: Double,
        config: KartPhysicsConfig,
        state: KartRuntimeState,
        topSpeedMultiplier: Double
    ): KartDriveCommand {
        val speedMultiplier = topSpeedMultiplier.coerceAtLeast(1.0)
        val transmission = config.transmission
        if (transmission == null) {
            state.debugTransmissionGear = 0
            state.debugEngineRpm = 0.0
            return KartDriveCommand(
                throttle = input.throttle.coerceIn(-1.0, 1.0),
                brake = input.brake.coerceIn(0.0, 1.0).coerceAtLeast(input.handbrake.coerceIn(0.0, 1.0)),
                topSpeed = config.wheelTopSpeed * speedMultiplier,
                torqueMultiplier = 1.0
            )
        }

        val maxForwardGear = transmission.forwardGears.size
        state.transmissionGear = state.transmissionGear.coerceIn(-1, maxForwardGear)
        if (state.transmissionGear == 0 && transmission.automatic) {
            state.transmissionGear = 1
        }

        val rawThrottle = input.throttle.coerceIn(-1.0, 1.0)
        var brake = input.brake.coerceIn(0.0, 1.0).coerceAtLeast(input.handbrake.coerceIn(0.0, 1.0))
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
                    updateAutomaticKartGear(forwardSpeed, transmission, state)
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
        } * speedMultiplier
        val baseTorque = when {
            gear > 0 && gearConfig != null -> gearConfig.torqueMultiplier * kartGearLaunchTorqueFactor(gearConfig, forwardSpeed)
            gear < 0 -> transmission.reverseTorqueMultiplier
            else -> transmission.neutralDrag
        }

        state.debugTransmissionGear = gear
        state.debugEngineRpm = estimateKartEngineRpm(
            forwardSpeed = forwardSpeed,
            throttle = driveThrottle,
            topSpeed = topSpeed,
            gear = gear
        )
        return KartDriveCommand(
            throttle = driveThrottle,
            brake = brake,
            topSpeed = topSpeed,
            torqueMultiplier = baseTorque.coerceIn(0.0, 1.75)
        )
    }

    private fun updateAutomaticKartGear(
        forwardSpeed: Double,
        transmission: VehicleTransmissionConfig,
        state: KartRuntimeState
    ) {
        if (state.transmissionShiftCooldown > 0.0 || state.transmissionGear < 1) return
        val gears = transmission.forwardGears
        val speed = abs(forwardSpeed)
        val gearIndex = (state.transmissionGear - 1).coerceIn(0, gears.lastIndex)
        val gear = gears[gearIndex]
        when {
            state.transmissionGear < gears.size && speed >= gear.upshiftSpeed -> {
                state.transmissionGear += 1
                state.transmissionShiftCooldown = transmission.shiftCooldownSeconds
            }
            state.transmissionGear > 1 && speed <= gear.downshiftSpeed -> {
                state.transmissionGear -= 1
                state.transmissionShiftCooldown = transmission.shiftCooldownSeconds
            }
        }
    }

    private fun kartGearLaunchTorqueFactor(gear: VehicleTransmissionGearConfig, forwardSpeed: Double): Double {
        val launchScale = gear.launchTorqueScale.coerceIn(0.0, 1.0)
        val speedT = smoothstep(0.0, max(gear.downshiftSpeed, gear.maxSpeed * 0.35), abs(forwardSpeed))
        return lerp(launchScale * launchScale, 1.0, speedT).coerceIn(0.0, 1.0)
    }

    private fun estimateKartEngineRpm(forwardSpeed: Double, throttle: Double, topSpeed: Double, gear: Int): Double {
        if (gear == 0) return if (throttle > 0.05) 2600.0 + throttle * 2600.0 else 950.0
        val speedT = if (topSpeed > 1.0) (abs(forwardSpeed) / topSpeed).coerceIn(0.0, 1.15) else 0.0
        val throttleLift = abs(throttle).coerceIn(0.0, 1.0) * 1450.0
        return (950.0 + speedT * 5000.0 + throttleLift).coerceIn(700.0, 6800.0)
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
                VehiclePhysicsMath.safeDot(kartContact.contact.relativeWheelVelocityWorld, kartContact.contact.wheelForwardWorld) / radius
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
            val lateralSpeed = VehiclePhysicsMath.safeDot(kartContact.contact.relativeWheelVelocityWorld, kartContact.contact.wheelRightWorld)
            val forwardSpeed = VehiclePhysicsMath.safeDot(kartContact.contact.relativeWheelVelocityWorld, kartContact.contact.wheelForwardWorld)
            lateralSpeed / max(abs(forwardSpeed), 1.5)
        }
        return slipTotal / grounded.size
    }

    private fun updateDriftState(
        state: KartRuntimeState,
        input: VehicleInput,
        driftSpeed: Double,
        dt: Double,
        config: KartPhysicsConfig
    ): Boolean {
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

    private data class SupportGroundNormal(
        val normal: Vector3d,
        val rise: Double,
        val allGrounded: Boolean
    )

    private data class KartDriveCommand(
        val throttle: Double,
        val brake: Double,
        val topSpeed: Double,
        val torqueMultiplier: Double
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
}
