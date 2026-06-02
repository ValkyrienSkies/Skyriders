package org.valkyrienskies.skyriders.content.bikes

import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.skyriders.content.BikeDefinition
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.BikePhysicsConfig
import org.valkyrienskies.skyriders.content.BikeRuntimeState
import kotlin.math.exp
import kotlin.math.max

class HoverBike(
    bodyId: BodyId,
    boundingBox: AABB,
    level: Level,
    definition: BikeDefinition,
    state: BikeRuntimeState = BikeRuntimeState()
) : DefaultBike(bodyId, boundingBox, level, definition, state) {
    override fun physTick(physLevel: PhysLevel, body: PhysVsBody, input: BikeInput, dt: Double) {
        HoverBikePhysics.update(body, physLevel, input, config, state, dt)
    }
}

private object HoverBikePhysics {
    private val WORLD_UP = Vector3d(0.0, 1.0, 0.0)
    private val LOCAL_FORWARD = Vector3d(0.0, 0.0, 1.0)
    private val LOCAL_RIGHT = Vector3d(1.0, 0.0, 0.0)
    private val LOCAL_UP = Vector3d(0.0, 1.0, 0.0)
    private const val MIN_DIRECTION_LENGTH_SQUARED = 1.0e-9
    private const val MAX_FORCE_MAGNITUDE = 1.0e7
    private const val MAX_TORQUE_MAGNITUDE = 1.0e7
    private const val AIR_CONTROL_SCALE = 0.35

    fun update(
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
        val activeInput = if (input.riderPresent) input else BikeInput.EMPTY
        val hoverUp = safeNormalize(state.smoothedGroundNormal, WORLD_UP)
        val hoverPoints = listOf(config.frontWheelLocalPos, config.rearWheelLocalPos)
        val contacts = hoverPoints.map { point -> sampleHoverContact(body, physLevel, point, hoverUp, config) }
        val groundedContacts = contacts.filter(HoverContact::grounded)
        val grounded = groundedContacts.isNotEmpty()
        val terrainUp = if (grounded) smoothGroundNormal(state, groundedContacts, dt, config) else hoverUp
        val forwardSpeed = safeDot(body.kinematics.velocity, forward)
        val speed = body.kinematics.velocity.length()

        contacts.forEach { contact -> applyHoverForce(body, contact, config) }
        if (input.riderPresent) {
            applyDrive(body, forward, terrainUp, activeInput, forwardSpeed, grounded, config)
            applyBrake(body, forward, right, activeInput, config)
            applySteering(body, terrainUp, activeInput, speed, grounded, config)
            applyBalance(body, forward, right, up, terrainUp, activeInput, speed, grounded, config)
        } else {
            applyParkingDamping(body, config)
            applyBalance(body, forward, right, up, terrainUp, BikeInput.EMPTY, speed, grounded, config)
        }
        dampExtremeAngularVelocity(body, config)
        updateVisualState(state, activeInput, forwardSpeed, speed, grounded, config, dt)
        updateDebugState(state, activeInput, contacts, speed)
        state.lastGrounded = grounded
    }

    private fun sampleHoverContact(
        body: PhysVsBody,
        physLevel: PhysLevel,
        localPoint: Vector3d,
        hoverUp: Vector3d,
        config: BikePhysicsConfig
    ): HoverContact {
        val castDirection = Vector3d(hoverUp).negate()
        val maxLength = config.suspensionRestLength + config.suspensionTravel
        val point = localPointWorld(body, localPoint, hoverUp)
        val result = physLevel.rayCast(point, castDirection, maxLength, body.id)
        if (result == null || result.hitBody.id == body.id || !result.distance.isFinite()) {
            return HoverContact(false, point, hoverUp, maxLength, 0.0, Vector3d())
        }

        val compressionMeters = config.suspensionRestLength - result.distance
        val compression = (compressionMeters / config.suspensionTravel).coerceIn(0.0, 1.0)
        return HoverContact(
            grounded = true,
            pointWorld = point,
            normalWorld = safeNormalize(result.hitNormal, hoverUp),
            distance = result.distance,
            compression = compression,
            velocityWorld = velocityAtWorldPoint(body, point)
        )
    }

    private fun applyHoverForce(
        body: PhysVsBody,
        contact: HoverContact,
        config: BikePhysicsConfig
    ) {
        if (!contact.grounded) return

        val compressionMeters = contact.compression * config.suspensionTravel
        val springForce = compressionMeters * config.suspensionStrength
        val dampingForce = -safeDot(contact.velocityWorld, contact.normalWorld) * config.suspensionDamping
        val totalForce = max(0.0, springForce + dampingForce)
        safeApplyWorldForce(body, Vector3d(contact.normalWorld).mul(totalForce), contact.pointWorld)
    }

    private fun applyDrive(
        body: PhysVsBody,
        forward: Vector3d,
        terrainUp: Vector3d,
        input: BikeInput,
        forwardSpeed: Double,
        grounded: Boolean,
        config: BikePhysicsConfig
    ) {
        val throttle = input.throttle.coerceIn(-1.0, 1.0)
        if (throttle == 0.0) return

        val driveForward = projectOntoPlane(forward, terrainUp, forward)
        val speedFactor = computeSpeedLimitFactor(forwardSpeed, throttle, config)
        val controlScale = if (grounded) 1.0 else AIR_CONTROL_SCALE
        val force = throttle * speedFactor * config.mass * 42.0 * config.longitudinalGrip * controlScale
        safeApplyWorldForce(body, driveForward.mul(force), body.kinematics.position)
    }

    private fun applyBrake(
        body: PhysVsBody,
        forward: Vector3d,
        right: Vector3d,
        input: BikeInput,
        config: BikePhysicsConfig
    ) {
        val brake = input.brake.coerceIn(0.0, 1.0)
        if (brake <= 0.0) return

        val velocity = Vector3d(body.kinematics.velocity)
        val forwardVelocity = safeDot(velocity, forward)
        val lateralVelocity = safeDot(velocity, right)
        val damping = Vector3d(forward).mul(-forwardVelocity * config.mass * config.brakeStrength * brake)
            .fma(-lateralVelocity * config.mass * config.brakeStrength * 0.35 * brake, right)
        safeApplyWorldForce(body, damping, body.kinematics.position)
    }

    private fun applySteering(
        body: PhysVsBody,
        terrainUp: Vector3d,
        input: BikeInput,
        speed: Double,
        grounded: Boolean,
        config: BikePhysicsConfig
    ) {
        val steer = input.steer.coerceIn(-1.0, 1.0)
        if (steer == 0.0) return

        val speedAmount = smoothstep(0.5, config.wheelTopSpeed, speed)
        val controlScale = if (grounded) 1.0 else AIR_CONTROL_SCALE
        val yawTorque = steer * config.highSpeedYawAssist * (0.25 + speedAmount) * controlScale
        safeApplyWorldTorque(body, Vector3d(terrainUp).mul(yawTorque))
    }

    private fun applyBalance(
        body: PhysVsBody,
        forward: Vector3d,
        right: Vector3d,
        up: Vector3d,
        terrainUp: Vector3d,
        input: BikeInput,
        speed: Double,
        grounded: Boolean,
        config: BikePhysicsConfig
    ) {
        val speedLeanAmount = smoothstep(config.minLeanSpeed, config.fullLeanSpeed, speed)
        val targetLean = -input.steer.coerceIn(-1.0, 1.0) * config.maxLeanAngleRad * speedLeanAmount
        val desiredUp = Vector3d(terrainUp).rotateAxis(targetLean, forward.x, forward.y, forward.z)
        val errorAxis = Vector3d(up).cross(desiredUp)
        val yawVelocity = safeDot(body.kinematics.angularVelocity, terrainUp)
        val nonYawAngularVelocity = Vector3d(body.kinematics.angularVelocity).fma(-yawVelocity, terrainUp)
        val pitchInput = input.pitch.coerceIn(-1.0, 1.0)
        val pitchTorque = Vector3d(right).mul(-pitchInput * config.groundedPitchControlStrength * 0.45)
        val strengthScale = if (grounded) 1.0 else 0.45
        val torque = errorAxis.mul(config.balanceStrength * strengthScale)
            .sub(nonYawAngularVelocity.mul(config.balanceDamping * strengthScale))
            .add(pitchTorque)
        safeApplyWorldTorque(body, torque)
    }

    private fun applyParkingDamping(
        body: PhysVsBody,
        config: BikePhysicsConfig
    ) {
        val velocity = Vector3d(body.kinematics.velocity).mul(-config.mass * 1.5)
        val angular = Vector3d(body.kinematics.angularVelocity).mul(-config.balanceDamping)
        safeApplyWorldForce(body, velocity, body.kinematics.position)
        safeApplyWorldTorque(body, angular)
    }

    private fun dampExtremeAngularVelocity(
        body: PhysVsBody,
        config: BikePhysicsConfig
    ) {
        val angularVelocity = Vector3d(body.kinematics.angularVelocity)
        val overspeed = if (isFinite(angularVelocity)) angularVelocity.length() - config.maxAngularVelocity else 0.0
        if (overspeed > 0.0) {
            safeApplyWorldTorque(body, safeNormalize(angularVelocity, WORLD_UP).mul(-overspeed * config.balanceDamping))
        }
    }

    private fun updateVisualState(
        state: BikeRuntimeState,
        input: BikeInput,
        forwardSpeed: Double,
        speed: Double,
        grounded: Boolean,
        config: BikePhysicsConfig,
        dt: Double
    ) {
        val alpha = 1.0 - exp(-dt / 0.12)
        val speedLeanAmount = smoothstep(config.minLeanSpeed, config.fullLeanSpeed, speed)
        val targetLean = -input.steer.coerceIn(-1.0, 1.0) * config.maxLeanAngleRad * speedLeanAmount
        val targetSteer = input.steer.coerceIn(-1.0, 1.0) * config.maxSteerHighSpeedRad
        state.visualLeanRad = lerp(state.visualLeanRad, targetLean, alpha)
        state.visualSteerRad = lerp(state.visualSteerRad, targetSteer, alpha)
        state.frontWheelSpin += forwardSpeed / max(config.wheelRadius, 0.05) * dt
        state.rearWheelSpin += forwardSpeed / max(config.wheelRadius, 0.05) * dt
        if (!grounded && input.jump <= 0.0) {
            state.jumpCharge = 0.0
        }
    }

    private fun updateDebugState(
        state: BikeRuntimeState,
        input: BikeInput,
        contacts: List<HoverContact>,
        speed: Double
    ) {
        state.debugSpeed = speed
        state.debugFrontWheelGrounded = contacts.getOrNull(0)?.grounded ?: false
        state.debugRearWheelGrounded = contacts.getOrNull(1)?.grounded ?: false
        state.debugThrottle = input.throttle
        state.debugSteeringAngleRad = state.visualSteerRad
        state.debugDrifting = false
    }

    private fun smoothGroundNormal(
        state: BikeRuntimeState,
        contacts: List<HoverContact>,
        dt: Double,
        config: BikePhysicsConfig
    ): Vector3d {
        val rawNormal = Vector3d()
        contacts.forEach { rawNormal.add(it.normalWorld) }
        rawNormal.set(safeNormalize(rawNormal, state.smoothedGroundNormal))
        val alpha = 1.0 - exp(-dt / config.groundNormalSmoothingTime)
        state.smoothedGroundNormal.set(safeNormalize(Vector3d(state.smoothedGroundNormal).lerp(rawNormal, alpha), WORLD_UP))
        return Vector3d(state.smoothedGroundNormal)
    }

    private fun computeSpeedLimitFactor(
        forwardSpeed: Double,
        throttle: Double,
        config: BikePhysicsConfig
    ): Double {
        val topSpeed = config.wheelTopSpeed
        if (!topSpeed.isFinite() || topSpeed <= 0.0) return 1.0
        val signedSpeed = forwardSpeed * if (throttle > 0.0) 1.0 else -1.0
        if (signedSpeed <= topSpeed * 0.85) return 1.0
        if (signedSpeed >= topSpeed) return 0.0
        return 1.0 - smoothstep(topSpeed * 0.85, topSpeed, signedSpeed)
    }

    private fun localPointWorld(body: PhysVsBody, localPoint: Vector3d, terrainUp: Vector3d): Vector3d {
        val forward = transformDirection(body, LOCAL_FORWARD, LOCAL_FORWARD)
        val right = transformDirection(body, LOCAL_RIGHT, LOCAL_RIGHT)
        return Vector3d(body.kinematics.position)
            .fma(localPoint.z, forward)
            .fma(localPoint.x, right)
            .fma(localPoint.y, terrainUp)
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

    private data class HoverContact(
        val grounded: Boolean,
        val pointWorld: Vector3d,
        val normalWorld: Vector3d,
        val distance: Double,
        val compression: Double,
        val velocityWorld: Vector3d
    )
}
