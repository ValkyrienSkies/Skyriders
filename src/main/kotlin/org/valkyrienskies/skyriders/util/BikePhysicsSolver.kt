package org.valkyrienskies.skyriders.util

import org.joml.Vector3d
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

    fun updateBikePhysics(
        body: PhysVsBody,
        physLevel: PhysLevel,
        input: BikeInput,
        config: BikePhysicsConfig,
        state: BikeRuntimeState,
        dt: Double
    ) {
        val transform = body.kinematics.transform
        val forward = transformDirection(body, LOCAL_FORWARD)
        val right = transformDirection(body, LOCAL_RIGHT)
        val up = transformDirection(body, LOCAL_UP)
        val forwardSpeed = body.kinematics.velocity.dot(forward)

        val frontContact = sampleWheelContact(body, physLevel, config.frontWheelLocalPos, config, false)
        val rearContact = sampleWheelContact(body, physLevel, config.rearWheelLocalPos, config, false)
        val grounded = frontContact.grounded || rearContact.grounded

        smoothGroundNormal(state, listOf(frontContact, rearContact), dt, config)

        applySuspension(body, frontContact, config)
        applySuspension(body, rearContact, config)
        applyTireForces(body, frontContact, config)
        applyTireForces(body, rearContact, config)
        applyDriveAndBrakes(body, frontContact, rearContact, input, config)

        val targetLean = computeTargetLean(forwardSpeed, input.steer, config)
        applyBalanceController(body, forward, up, targetLean, config, forwardSpeed)
        applyLowSpeedAssist(body, input, forwardSpeed, config)
        applyAntiFlipAssist(body, forward, right, up, config)
        dampExtremeAngularVelocity(body, config)
        updateVisualState(state, targetLean, forwardSpeed, config, dt, grounded)

        state.lastGrounded = grounded
    }

    private fun sampleWheelContact(
        body: PhysVsBody,
        physLevel: PhysLevel,
        wheelLocalPos: Vector3d,
        config: BikePhysicsConfig,
        steerable: Boolean
    ): WheelContact {
        val transform = body.kinematics.transform
        val wheelMountWorld = transform.toWorld.transformPosition(Vector3d(wheelLocalPos))
        val suspensionDirWorld = transformDirection(body, LOCAL_UP)
        val castDirection = Vector3d(suspensionDirWorld).negate().normalize()
        val maxLength = config.suspensionRestLength + config.suspensionTravel + config.wheelRadius
        val result = physLevel.rayCast(wheelMountWorld, castDirection, maxLength)
        val forward = transformDirection(body, LOCAL_FORWARD)
        val right = transformDirection(body, LOCAL_RIGHT)

        if (result == null || result.hitBody.id == body.id) {
            return WheelContact(
                grounded = false,
                contactPointWorld = wheelMountWorld,
                contactNormalWorld = WORLD_UP,
                suspensionDirWorld = suspensionDirWorld,
                hitDistance = maxLength,
                compression = 0.0,
                normalForceEstimate = 0.0,
                wheelForwardWorld = forward,
                wheelRightWorld = right,
                wheelVelocityWorld = velocityAtWorldPoint(body, wheelMountWorld)
            )
        }

        val hitDistance = result.distance
        val contactPoint = Vector3d(wheelMountWorld).fma(hitDistance, castDirection)
        val springLength = hitDistance - config.wheelRadius
        val compressionMeters = config.suspensionRestLength - springLength
        val compression = (compressionMeters / config.suspensionTravel).coerceIn(0.0, 1.0)
        val normalForceEstimate = max(0.0, compressionMeters * config.suspensionStrength)

        return WheelContact(
            grounded = true,
            contactPointWorld = contactPoint,
            contactNormalWorld = Vector3d(result.hitNormal).normalize(),
            suspensionDirWorld = suspensionDirWorld,
            hitDistance = hitDistance,
            compression = compression,
            normalForceEstimate = normalForceEstimate,
            wheelForwardWorld = forward,
            wheelRightWorld = right,
            wheelVelocityWorld = velocityAtWorldPoint(body, contactPoint)
        )
    }

    private fun applySuspension(body: PhysVsBody, contact: WheelContact, config: BikePhysicsConfig) {
        if (!contact.grounded) return

        val compressionMeters = contact.compression * config.suspensionTravel
        val springForceMag = compressionMeters * config.suspensionStrength
        val suspensionVelocity = contact.wheelVelocityWorld.dot(contact.suspensionDirWorld)
        val dampingForceMag = -suspensionVelocity * config.suspensionDamping
        val totalForceMag = max(0.0, springForceMag + dampingForceMag)

        body.applyWorldForce(
            Vector3d(contact.suspensionDirWorld).mul(totalForceMag),
            contact.contactPointWorld
        )
    }

    private fun applyTireForces(body: PhysVsBody, contact: WheelContact, config: BikePhysicsConfig) {
        if (!contact.grounded) return

        val lateralVel = contact.wheelVelocityWorld.dot(contact.wheelRightWorld)
        val forwardVel = contact.wheelVelocityWorld.dot(contact.wheelForwardWorld)
        val safeSpeed = max(abs(forwardVel), 2.0)
        val slip = lateralVel / safeSpeed
        val gripFactor = tanh(slip * config.slipSharpness)
        val maxLateralForce = contact.normalForceEstimate * config.frictionCoefficient * config.lateralGrip
        val lateralForceMag = -gripFactor * maxLateralForce

        body.applyWorldForce(
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
        if (rear.grounded && input.throttle != 0.0) {
            val forceMag = input.throttle.coerceIn(-1.0, 1.0) * config.longitudinalGrip * rear.normalForceEstimate
            body.applyWorldForce(Vector3d(rear.wheelForwardWorld).mul(forceMag), rear.contactPointWorld)
        }

        applyBrake(body, front, input.brake * 0.65, config)
        applyBrake(body, rear, input.brake * 0.35, config)
    }

    private fun applyBrake(body: PhysVsBody, contact: WheelContact, brakeInput: Double, config: BikePhysicsConfig) {
        if (!contact.grounded || brakeInput <= 0.0) return

        val forwardVel = contact.wheelVelocityWorld.dot(contact.wheelForwardWorld)
        val maxBrakeForce = contact.normalForceEstimate * config.frictionCoefficient * config.longitudinalGrip
        val forceMag = (-forwardVel * brakeInput * maxBrakeForce).coerceIn(-maxBrakeForce, maxBrakeForce)
        body.applyWorldForce(Vector3d(contact.wheelForwardWorld).mul(forceMag), contact.contactPointWorld)
    }

    private fun computeTargetLean(forwardSpeed: Double, steerInput: Double, config: BikePhysicsConfig): Double {
        val speedLeanAmount = smoothstep(config.minLeanSpeed, config.fullLeanSpeed, abs(forwardSpeed))
        return -steerInput.coerceIn(-1.0, 1.0) * config.maxLeanAngleRad * speedLeanAmount
    }

    private fun applyBalanceController(
        body: PhysVsBody,
        forward: Vector3d,
        currentUp: Vector3d,
        targetLeanRad: Double,
        config: BikePhysicsConfig,
        forwardSpeed: Double
    ) {
        val leanedUp = Vector3d(WORLD_UP).rotateAxis(targetLeanRad, forward.x, forward.y, forward.z).normalize()
        val uprightAssist = 1.0 - smoothstep(config.uprightAssistStartSpeed, config.uprightAssistEndSpeed, abs(forwardSpeed))
        val desiredUp = slerpDirection(leanedUp, WORLD_UP, uprightAssist)
        val errorAxis = Vector3d(currentUp).cross(desiredUp)
        val torque = errorAxis.mul(config.balanceStrength)
            .sub(Vector3d(body.kinematics.angularVelocity).mul(config.balanceDamping))

        body.applyWorldTorque(torque)
    }

    private fun applyLowSpeedAssist(body: PhysVsBody, input: BikeInput, speed: Double, config: BikePhysicsConfig) {
        val lowSpeedAmount = 1.0 - smoothstep(3.0, 10.0, abs(speed))
        val yawTorque = input.steer.coerceIn(-1.0, 1.0) * config.lowSpeedYawAssist * lowSpeedAmount
        if (yawTorque != 0.0) {
            body.applyWorldTorque(Vector3d(0.0, yawTorque, 0.0))
        }
    }

    private fun applyAntiFlipAssist(
        body: PhysVsBody,
        forward: Vector3d,
        right: Vector3d,
        up: Vector3d,
        config: BikePhysicsConfig
    ) {
        val pitchError = forward.y.coerceIn(-1.0, 1.0)
        if (abs(pitchError) > kotlin.math.sin(config.maxPitchAngleRad)) {
            body.applyWorldTorque(Vector3d(right).mul(-pitchError * config.antiFlipStrength))
        }

        val rollError = right.y.coerceIn(-1.0, 1.0)
        if (abs(rollError) > kotlin.math.sin(config.maxRollAngleRad)) {
            body.applyWorldTorque(Vector3d(forward).mul(rollError * config.antiFlipStrength))
        }

        if (up.dot(WORLD_UP) < 0.0) {
            body.applyWorldTorque(Vector3d(forward).mul(config.antiFlipStrength))
        }
    }

    private fun dampExtremeAngularVelocity(body: PhysVsBody, config: BikePhysicsConfig) {
        val angularVelocity = Vector3d(body.kinematics.angularVelocity)
        val overspeed = angularVelocity.length() - config.maxAngularVelocity
        if (overspeed > 0.0) {
            body.applyWorldTorque(angularVelocity.normalize().mul(-overspeed * config.balanceDamping))
        }
    }

    private fun updateVisualState(
        state: BikeRuntimeState,
        targetLean: Double,
        forwardSpeed: Double,
        config: BikePhysicsConfig,
        dt: Double,
        grounded: Boolean
    ) {
        val alpha = 1.0 - exp(-dt / 0.12)
        state.visualLeanRad = lerp(state.visualLeanRad, targetLean, alpha)
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
        rawNormal.normalize()

        val alpha = 1.0 - exp(-dt / config.groundNormalSmoothingTime)
        state.smoothedGroundNormal.lerp(rawNormal, alpha).normalize()
    }

    private fun velocityAtWorldPoint(body: PhysVsBody, pointWorld: Vector3d): Vector3d {
        val radius = Vector3d(pointWorld).sub(body.kinematics.position)
        return Vector3d(body.kinematics.angularVelocity).cross(radius).add(body.kinematics.velocity)
    }

    private fun transformDirection(body: PhysVsBody, direction: Vector3d): Vector3d {
        return body.kinematics.rotation.transform(Vector3d(direction)).normalize()
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
        return Vector3d(from).lerp(to, alpha.coerceIn(0.0, 1.0)).normalize()
    }
}
