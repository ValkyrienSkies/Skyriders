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

object KartPhysicsSolver {
    private val WORLD_UP = Vector3d(0.0, 1.0, 0.0)
    private val LOCAL_FORWARD = Vector3d(0.0, 0.0, 1.0)
    private val LOCAL_RIGHT = Vector3d(1.0, 0.0, 0.0)
    private val LOCAL_UP = Vector3d(0.0, 1.0, 0.0)
    private const val MAX_FORCE = VehiclePhysicsMath.MAX_FORCE_MAGNITUDE

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
        val steerRad = updateSteerAngle(
            state,
            computeTargetSteerRad(input.steer, forwardSpeed, config),
            dt,
            config
        )
        val contacts = config.wheelLocalPositions.mapIndexed { index, localPos ->
            sampleWheel(body, physLevel, localPos, index < 2, steerRad, config)
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
        state.debugLateralSlip = averageLateralSlip(appliedContacts)

        appliedContacts.forEach { contact ->
            applyLateralGrip(body, contact, config)
        }

        if (input.riderPresent) {
            applyDriveAndBrake(body, appliedContacts, forwardSpeed, input, config)
            if (grounded) {
                applySteeringAssist(body, steerRad, forwardSpeed, WORLD_UP, config)
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
        config: KartPhysicsConfig
    ): VehicleWheelContact {
        val mountWorld = body.kinematics.transform.toWorld.transformPosition(Vector3d(wheelLocalPos))
        val castDir = Vector3d(WORLD_UP).negate()
        val maxLength = config.suspensionRestLength + config.suspensionTravel + config.wheelRadius
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
        return VehicleWheelPhysics.sampleRaycastWheel(
            body = body,
            physLevel = physLevel,
            mountWorld = mountWorld,
            suspensionDirWorld = castDir,
            maxLength = maxLength,
            wheelForwardWorld = wheelForward,
            wheelRightWorld = wheelRight
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

    private fun applyLateralGrip(body: PhysVsBody, kartContact: KartContact, config: KartPhysicsConfig) {
        val contact = kartContact.contact
        if (!contact.grounded || kartContact.normalForce <= 0.0) return

        val lateralSpeed = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.wheelRightWorld)
        val forwardSpeed = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.wheelForwardWorld)
        val grip = if (kartContact.front) config.frontLateralGrip else config.rearLateralGrip
        val maxLateralForce = (kartContact.normalForce * config.tireFrictionCoefficient * grip)
            .coerceIn(0.0, MAX_FORCE)
        val slip = lateralSpeed / max(abs(forwardSpeed), 1.5)
        val shapedSlip = slip / (abs(slip) + 0.24)
        val force = Vector3d(contact.wheelRightWorld).mul((-shapedSlip * maxLateralForce).coerceIn(-MAX_FORCE, MAX_FORCE))
        VehicleWheelPhysics.applyContactForce(body, contact, force)
    }

    private fun applyDriveAndBrake(
        body: PhysVsBody,
        contacts: List<KartContact>,
        forwardSpeed: Double,
        input: VehicleInput,
        config: KartPhysicsConfig
    ) {
        val rearContacts = contacts.filter { !it.front && it.contact.grounded && it.normalForce > 0.0 }
        if (rearContacts.isEmpty()) return

        val throttle = input.throttle.coerceIn(-1.0, 1.0)
        val speedLimitScale = computeSpeedLimitScale(forwardSpeed, throttle, config)
        val driveForce = throttle * config.driveForce * speedLimitScale / rearContacts.size
        rearContacts.forEach { kartContact ->
            if (driveForce != 0.0) {
                val contact = kartContact.contact
                val maxLongitudinalForce = kartContact.normalForce * config.tireFrictionCoefficient * config.longitudinalGrip
                val limitedDriveForce = driveForce.coerceIn(-maxLongitudinalForce, maxLongitudinalForce)
                VehicleWheelPhysics.applyContactForce(body, contact, Vector3d(contact.wheelForwardWorld).mul(limitedDriveForce))
            }
        }

        val brake = input.brake.coerceIn(0.0, 1.0).coerceAtLeast(input.handbrake.coerceIn(0.0, 1.0))
        contacts.filter { it.contact.grounded && it.normalForce > 0.0 }.forEach { kartContact ->
            val contact = kartContact.contact
            val wheelForwardSpeed = VehiclePhysicsMath.safeDot(contact.wheelVelocityWorld, contact.wheelForwardWorld)
            val rollingForce = (-wheelForwardSpeed * config.rollingResistance / contacts.size).coerceIn(-MAX_FORCE, MAX_FORCE)
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
        val torque = Vector3d(terrainUp).mul(-steer * forwardSpeed.signOrZero() * config.yawAssist * speedT)
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

    private fun computeSpeedLimitScale(forwardSpeed: Double, throttle: Double, config: KartPhysicsConfig): Double {
        if (throttle == 0.0) return 0.0
        val topSpeed = config.wheelTopSpeed
        if (!topSpeed.isFinite() || topSpeed <= 0.0) return 1.0

        val signedSpeed = forwardSpeed * throttle.signOrZero()
        if (signedSpeed <= topSpeed * (1.0 - config.speedLimitSoftness.coerceIn(0.02, 0.8))) return 1.0
        if (signedSpeed >= topSpeed) return 0.0
        return 1.0 - smoothstep(topSpeed * (1.0 - config.speedLimitSoftness.coerceIn(0.02, 0.8)), topSpeed, signedSpeed)
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
