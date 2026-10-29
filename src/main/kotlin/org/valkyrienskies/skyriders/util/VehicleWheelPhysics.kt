package org.valkyrienskies.skyriders.util

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.physics.RayCastResult

data class VehicleWheelContact(
    val grounded: Boolean,
    val hitBody: PhysVsBody?,
    val contactPointWorld: Vector3d,
    val contactNormalWorld: Vector3d,
    val suspensionDirWorld: Vector3d,
    val hitDistance: Double,
    val compressionDistance: Double,
    val surfaceFriction: Double,
    val wheelForwardWorld: Vector3d,
    val wheelRightWorld: Vector3d,
    val wheelVelocityWorld: Vector3d,
    val surfaceVelocityWorld: Vector3d,
    val relativeWheelVelocityWorld: Vector3d
)

object VehicleWheelPhysics {
    private const val MIN_GROUND_NORMAL_ALIGNMENT = 0.28
    private const val DEFAULT_SURFACE_FRICTION_COEFFICIENT = 0.6
    private const val MIN_SURFACE_FRICTION_SCALE = 0.12
    private const val MAX_SURFACE_FRICTION_SCALE = 1.65

    fun sampleSphereWheel(
        body: PhysVsBody, physLevel: PhysLevel, mountWorld: Vector3dc, suspensionDirWorld: Vector3dc,
        maxLength: Double, radius: Double, wheelForwardWorld: Vector3dc, wheelRightWorld: Vector3dc,
        groundedMaxDistance: Double = maxLength
    ): VehicleWheelContact {
        val direction = VehiclePhysicsMath.safeNormalize(suspensionDirWorld, Vector3d(0.0, -1.0, 0.0))
        if (direction.y > -MIN_GROUND_NORMAL_ALIGNMENT) direction.set(0.0, -1.0, 0.0)
        val originLift = (maxLength - radius).coerceAtLeast(radius)
        fun cast() = physLevel.castSphere(Vector3d(mountWorld).fma(-originLift, direction), radius,
            direction, maxLength - radius + originLift, body.id)
        var hit = cast()
        fun isValid() = hit?.let { it.geometrySupported && !it.initialOverlap && it.distance.isFinite() &&
            it.normal.isFinite && it.velocity.isFinite && it.normal.y() >= MIN_GROUND_NORMAL_ALIGNMENT &&
            it.normal.dot(direction) <= -MIN_GROUND_NORMAL_ALIGNMENT } == true
        if (!isValid() && direction.y > -0.9999) {
            direction.set(0.0, -1.0, 0.0)
            hit = cast()
        }
        val valid = isValid()
        val point = if (valid) Vector3d(hit!!.point) else Vector3d(mountWorld)
        val normal = if (valid) Vector3d(hit!!.normal).normalize() else Vector3d(direction).negate()
        val surfaceVelocity = if (valid) Vector3d(hit!!.velocity) else Vector3d()
        val velocity = VehiclePhysicsMath.velocityAtPoint(body, point)
        val distance = if (valid) hit!!.distance + radius - originLift else maxLength
        val up = Vector3d(direction).negate()
        val forward = VehiclePhysicsMath.projectOntoPlane(wheelForwardWorld, up, Vector3d(0.0, 0.0, 1.0))
        val right = Vector3d(up).cross(forward).normalize()
        return VehicleWheelContact(valid && distance <= groundedMaxDistance, if (valid) hit!!.hitBody else null,
            point, normal, direction, distance, (maxLength - distance).coerceAtLeast(0.0),
            if (valid) surfaceFrictionScale(hit!!.staticFriction, hit.dynamicFriction) else 1.0,
            forward, right, velocity, surfaceVelocity,
            Vector3d(velocity).sub(surfaceVelocity))
    }

    fun sampleRaycastWheel(
        body: PhysVsBody,
        physLevel: PhysLevel,
        mountWorld: Vector3dc,
        suspensionDirWorld: Vector3dc,
        maxLength: Double,
        wheelForwardWorld: Vector3dc,
        wheelRightWorld: Vector3dc,
        groundedMaxDistance: Double = maxLength
    ): VehicleWheelContact {
        val suspensionDir = VehiclePhysicsMath.safeNormalize(suspensionDirWorld, Vector3d(0.0, -1.0, 0.0))
        val hit = physLevel.rayCast(mountWorld, suspensionDir, maxLength, body.id)
        val velocity = VehiclePhysicsMath.velocityAtPoint(body, mountWorld)
        if (!isSupportHit(hit, body.id, maxLength, Vector3d(suspensionDir).negate())) {
            return VehicleWheelContact(
                grounded = false,
                hitBody = null,
                contactPointWorld = Vector3d(mountWorld),
                contactNormalWorld = Vector3d(suspensionDir).negate(),
                suspensionDirWorld = suspensionDir,
                hitDistance = maxLength,
                compressionDistance = 0.0,
                surfaceFriction = 1.0,
                wheelForwardWorld = Vector3d(wheelForwardWorld),
                wheelRightWorld = Vector3d(wheelRightWorld),
                wheelVelocityWorld = velocity,
                surfaceVelocityWorld = Vector3d(),
                relativeWheelVelocityWorld = Vector3d(velocity)
            )
        }

        requireNotNull(hit)
        val contactPoint = Vector3d(mountWorld).fma(hit.distance, suspensionDir)
        val wheelVelocity = VehiclePhysicsMath.velocityAtPoint(body, contactPoint)
        val surfaceVelocity = Vector3d(hit.velocity)
        val contactNormal = Vector3d(hit.hitNormal).normalize()
        val suspensionUp = Vector3d(suspensionDir).negate()
        val loaded = hit.distance <= groundedMaxDistance &&
            VehiclePhysicsMath.safeDot(contactNormal, suspensionUp) >= MIN_GROUND_NORMAL_ALIGNMENT
        return VehicleWheelContact(
            grounded = loaded,
            hitBody = hit.hitBody,
            contactPointWorld = contactPoint,
            contactNormalWorld = contactNormal,
            suspensionDirWorld = suspensionDir,
            hitDistance = hit.distance,
            compressionDistance = (maxLength - hit.distance).coerceAtLeast(0.0),
            surfaceFriction = surfaceFrictionScale(hit.staticFrictionCoefficient, hit.dynamicFrictionCoefficient),
            wheelForwardWorld = Vector3d(wheelForwardWorld),
            wheelRightWorld = Vector3d(wheelRightWorld),
            wheelVelocityWorld = wheelVelocity,
            surfaceVelocityWorld = surfaceVelocity,
            relativeWheelVelocityWorld = Vector3d(wheelVelocity).sub(surfaceVelocity)
        )
    }

    internal fun isSupportHit(hit: RayCastResult?, bodyId: Long, maxLength: Double, up: Vector3dc): Boolean {
        if (hit == null || hit.hitBody.id == bodyId || !hit.distance.isFinite() ||
            hit.distance <= 1.0e-6 || hit.distance > maxLength || !hit.hitNormal.isFinite || !hit.velocity.isFinite) return false
        val length = hit.hitNormal.length()
        return length > 1.0e-6 && hit.hitNormal.dot(up) / length >= MIN_GROUND_NORMAL_ALIGNMENT
    }

    fun surfaceFrictionScale(staticFrictionCoefficient: Double, dynamicFrictionCoefficient: Double): Double {
        val coefficient = dynamicFrictionCoefficient
            .takeIf { it.isFinite() && it > 0.0 }
            ?: staticFrictionCoefficient.takeIf { it.isFinite() && it > 0.0 }
            ?: DEFAULT_SURFACE_FRICTION_COEFFICIENT
        return (coefficient / DEFAULT_SURFACE_FRICTION_COEFFICIENT)
            .coerceIn(MIN_SURFACE_FRICTION_SCALE, MAX_SURFACE_FRICTION_SCALE)
    }

    fun applyContactForce(
        body: PhysVsBody,
        contact: VehicleWheelContact,
        force: Vector3dc,
        position: Vector3dc = contact.contactPointWorld
    ) {
        VehiclePhysicsMath.safeApplyWorldForce(body, force, position)

        val hitBody = contact.hitBody ?: return
        if (hitBody.id == body.id || hitBody.isStatic) return
        VehiclePhysicsMath.safeApplyWorldForce(hitBody, Vector3d(force).negate(), position)
    }
}
