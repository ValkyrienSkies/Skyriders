package org.valkyrienskies.skyriders.util

import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.world.PhysLevel

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
    val wheelVelocityWorld: Vector3d
)

object VehicleWheelPhysics {
    private const val MIN_GROUND_NORMAL_ALIGNMENT = 0.28
    private const val DEFAULT_SURFACE_FRICTION_COEFFICIENT = 0.6
    private const val MIN_SURFACE_FRICTION_SCALE = 0.12
    private const val MAX_SURFACE_FRICTION_SCALE = 1.65

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
        if (hit == null || hit.hitBody.id == body.id || !hit.distance.isFinite()) {
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
                wheelVelocityWorld = velocity
            )
        }

        val contactPoint = Vector3d(mountWorld).fma(hit.distance, suspensionDir)
        val contactNormal = VehiclePhysicsMath.safeNormalize(hit.hitNormal, Vector3d(suspensionDir).negate())
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
            wheelVelocityWorld = VehiclePhysicsMath.velocityAtPoint(body, contactPoint)
        )
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
