package org.valkyrienskies.skyriders.util

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.skyriders.content.KartPhysicsConfig
import org.valkyrienskies.skyriders.content.KartRuntimeState
import org.valkyrienskies.skyriders.content.VehicleInput
import kotlin.math.abs
import kotlin.math.max

object KartPhysicsSolver {
    private val WORLD_UP = Vector3d(0.0, 1.0, 0.0)
    private val LOCAL_FORWARD = Vector3d(0.0, 0.0, 1.0)
    private val LOCAL_RIGHT = Vector3d(1.0, 0.0, 0.0)
    private val LOCAL_UP = Vector3d(0.0, 1.0, 0.0)
    private const val MIN_LENGTH_SQUARED = 1.0e-9
    private const val MAX_FORCE = 1.0e7
    private const val MAX_TORQUE = 1.0e7

    fun updateKartPhysics(
        body: PhysVsBody,
        physLevel: PhysLevel,
        input: VehicleInput,
        config: KartPhysicsConfig,
        state: KartRuntimeState,
        dt: Double
    ) {
        val forward = transformDirection(body, LOCAL_FORWARD, LOCAL_FORWARD)
        val right = transformDirection(body, LOCAL_RIGHT, LOCAL_RIGHT)
        val up = transformDirection(body, LOCAL_UP, LOCAL_UP)
        val steerRad = input.steer.coerceIn(-1.0, 1.0) * config.maxSteerRad
        val contacts = config.wheelLocalPositions.mapIndexed { index, localPos ->
            sampleWheel(body, physLevel, localPos, index < 2, steerRad, config)
        }
        val groundedContacts = contacts.filter(KartWheelContact::grounded)
        val grounded = groundedContacts.isNotEmpty()
        val speed = body.kinematics.velocity.length()
        val forwardSpeed = safeDot(body.kinematics.velocity, forward)

        state.debugSpeed = if (speed.isFinite()) speed else 0.0
        state.debugGroundedWheels = groundedContacts.size
        state.debugSteerRad = steerRad
        state.debugThrottle = input.throttle

        contacts.forEach { contact ->
            applySuspension(body, contact, config)
            applyLateralGrip(body, contact, config)
        }

        if (input.riderPresent) {
            applyDriveAndBrake(body, contacts, forwardSpeed, input, config)
            if (grounded) {
                applySteeringAssist(body, input, forwardSpeed, WORLD_UP, config)
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
    ): KartWheelContact {
        val mountWorld = body.kinematics.transform.toWorld.transformPosition(Vector3d(wheelLocalPos))
        val castDir = Vector3d(WORLD_UP).negate()
        val maxLength = config.suspensionRestLength + config.suspensionTravel + config.wheelRadius
        val hit = physLevel.rayCast(mountWorld, castDir, maxLength, body.id)
        val baseForward = projectOntoPlane(transformDirection(body, LOCAL_FORWARD, LOCAL_FORWARD), WORLD_UP, LOCAL_FORWARD)
        val wheelForward = if (front && steerRad != 0.0) {
            safeNormalize(Vector3d(baseForward).rotateAxis(steerRad, WORLD_UP.x, WORLD_UP.y, WORLD_UP.z), baseForward)
        } else {
            baseForward
        }
        val wheelRight = safeNormalize(Vector3d(WORLD_UP).cross(wheelForward), transformDirection(body, LOCAL_RIGHT, LOCAL_RIGHT))
        val velocity = velocityAtPoint(body, mountWorld)
        if (hit == null || hit.hitBody.id == body.id || !hit.distance.isFinite()) {
            return KartWheelContact(false, mountWorld, WORLD_UP, maxLength, 0.0, wheelForward, wheelRight, velocity)
        }

        val compression = (maxLength - hit.distance).coerceIn(0.0, config.suspensionTravel + config.wheelRadius)
        val contactPoint = Vector3d(mountWorld).add(Vector3d(castDir).mul(hit.distance))
        return KartWheelContact(
            grounded = true,
            contactPointWorld = contactPoint,
            contactNormalWorld = safeNormalize(hit.hitNormal, WORLD_UP),
            hitDistance = hit.distance,
            compression = compression,
            wheelForwardWorld = wheelForward,
            wheelRightWorld = wheelRight,
            wheelVelocityWorld = velocity
        )
    }

    private fun applySuspension(body: PhysVsBody, contact: KartWheelContact, config: KartPhysicsConfig) {
        if (!contact.grounded) return
        val springVelocity = -safeDot(contact.wheelVelocityWorld, WORLD_UP)
        val forceMag = contact.compression * config.suspensionStrength + springVelocity * config.suspensionDamping
        applyForce(body, Vector3d(WORLD_UP).mul(forceMag.coerceAtLeast(0.0)), contact.contactPointWorld)
    }

    private fun applyLateralGrip(body: PhysVsBody, contact: KartWheelContact, config: KartPhysicsConfig) {
        if (!contact.grounded) return
        val lateralSpeed = safeDot(contact.wheelVelocityWorld, contact.wheelRightWorld)
        val force = Vector3d(contact.wheelRightWorld).mul((-lateralSpeed * config.lateralGrip).coerceIn(-MAX_FORCE, MAX_FORCE))
        applyForce(body, force, contact.contactPointWorld)
    }

    private fun applyDriveAndBrake(
        body: PhysVsBody,
        contacts: List<KartWheelContact>,
        forwardSpeed: Double,
        input: VehicleInput,
        config: KartPhysicsConfig
    ) {
        val rearContacts = contacts.drop(2).filter(KartWheelContact::grounded)
        if (rearContacts.isEmpty()) return

        val throttle = input.throttle.coerceIn(-1.0, 1.0)
        val speedLimitScale = if (abs(forwardSpeed) > config.wheelTopSpeed && throttle * forwardSpeed > 0.0) 0.0 else 1.0
        val driveForce = throttle * config.driveForce * speedLimitScale / rearContacts.size
        rearContacts.forEach { contact ->
            if (driveForce != 0.0) {
                applyForce(body, Vector3d(contact.wheelForwardWorld).mul(driveForce), contact.contactPointWorld)
            }
        }

        val brake = input.brake.coerceIn(0.0, 1.0).coerceAtLeast(input.handbrake.coerceIn(0.0, 1.0))
        if (brake <= 0.0) return
        contacts.filter(KartWheelContact::grounded).forEach { contact ->
            val wheelForwardSpeed = safeDot(contact.wheelVelocityWorld, contact.wheelForwardWorld)
            val brakeForce = (-wheelForwardSpeed * config.brakeForce * brake).coerceIn(-MAX_FORCE, MAX_FORCE)
            applyForce(body, Vector3d(contact.wheelForwardWorld).mul(brakeForce), contact.contactPointWorld)
        }
    }

    private fun applySteeringAssist(
        body: PhysVsBody,
        input: VehicleInput,
        forwardSpeed: Double,
        terrainUp: Vector3d,
        config: KartPhysicsConfig
    ) {
        val steer = input.steer.coerceIn(-1.0, 1.0)
        if (abs(steer) < 1.0e-4 || abs(forwardSpeed) < 0.35) return
        val torque = Vector3d(terrainUp).mul(-steer * forwardSpeed.signOrZero() * config.yawAssist)
        applyTorque(body, torque)
    }

    private fun applyUpright(body: PhysVsBody, up: Vector3d, terrainUp: Vector3d, config: KartPhysicsConfig) {
        val axis = Vector3d(up).cross(terrainUp)
        if (!isFinite(axis) || axis.lengthSquared() < MIN_LENGTH_SQUARED) return
        val correction = axis.mul(config.uprightStrength)
        val damping = Vector3d(body.kinematics.angularVelocity).mul(-config.uprightDamping)
        applyTorque(body, correction.add(damping))
    }

    private fun dampAngularVelocity(body: PhysVsBody) {
        val angularVelocity = body.kinematics.angularVelocity
        if (!isFinite(angularVelocity) || angularVelocity.lengthSquared() < 64.0) return
        applyTorque(body, Vector3d(angularVelocity).mul(-450.0))
    }

    private fun transformDirection(body: PhysVsBody, local: Vector3dc, fallback: Vector3dc): Vector3d {
        return safeNormalize(body.kinematics.transform.rotation.transform(Vector3d(local)), fallback)
    }

    private fun projectOntoPlane(vector: Vector3dc, normal: Vector3dc, fallback: Vector3dc): Vector3d {
        val projected = Vector3d(vector).sub(Vector3d(normal).mul(safeDot(vector, normal)))
        return safeNormalize(projected, fallback)
    }

    private fun velocityAtPoint(body: PhysVsBody, point: Vector3dc): Vector3d {
        val radius = Vector3d(point).sub(body.kinematics.position)
        return Vector3d(body.kinematics.angularVelocity).cross(radius).add(body.kinematics.velocity)
    }

    private fun safeNormalize(vector: Vector3dc, fallback: Vector3dc): Vector3d {
        val result = Vector3d(vector)
        return if (isFinite(result) && result.lengthSquared() > MIN_LENGTH_SQUARED) result.normalize() else Vector3d(fallback)
    }

    private fun safeDot(a: Vector3dc, b: Vector3dc): Double {
        val dot = a.dot(b)
        return if (dot.isFinite()) dot else 0.0
    }

    private fun applyForce(body: PhysVsBody, force: Vector3d, position: Vector3dc) {
        if (!isFinite(force) || !isFinite(position) || force.lengthSquared() > MAX_FORCE * MAX_FORCE) return
        body.applyWorldForce(force, position)
    }

    private fun applyTorque(body: PhysVsBody, torque: Vector3d) {
        if (!isFinite(torque) || torque.lengthSquared() > MAX_TORQUE * MAX_TORQUE) return
        body.applyWorldTorque(torque)
    }

    private fun isFinite(vector: Vector3dc): Boolean {
        return vector.x().isFinite() && vector.y().isFinite() && vector.z().isFinite()
    }

    private fun Double.signOrZero(): Double {
        return when {
            this > 0.0 -> 1.0
            this < 0.0 -> -1.0
            else -> 0.0
        }
    }

    private data class KartWheelContact(
        val grounded: Boolean,
        val contactPointWorld: Vector3d,
        val contactNormalWorld: Vector3d,
        val hitDistance: Double,
        val compression: Double,
        val wheelForwardWorld: Vector3d,
        val wheelRightWorld: Vector3d,
        val wheelVelocityWorld: Vector3d
    )
}
