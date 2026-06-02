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
    val wheelForwardWorld: Vector3d,
    val wheelRightWorld: Vector3d,
    val wheelVelocityWorld: Vector3d
)

object VehicleWheelPhysics {
    fun sampleRaycastWheel(
        body: PhysVsBody,
        physLevel: PhysLevel,
        mountWorld: Vector3dc,
        suspensionDirWorld: Vector3dc,
        maxLength: Double,
        wheelForwardWorld: Vector3dc,
        wheelRightWorld: Vector3dc
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
                wheelForwardWorld = Vector3d(wheelForwardWorld),
                wheelRightWorld = Vector3d(wheelRightWorld),
                wheelVelocityWorld = velocity
            )
        }

        val contactPoint = Vector3d(mountWorld).fma(hit.distance, suspensionDir)
        return VehicleWheelContact(
            grounded = true,
            hitBody = hit.hitBody,
            contactPointWorld = contactPoint,
            contactNormalWorld = VehiclePhysicsMath.safeNormalize(hit.hitNormal, Vector3d(suspensionDir).negate()),
            suspensionDirWorld = suspensionDir,
            hitDistance = hit.distance,
            compressionDistance = (maxLength - hit.distance).coerceAtLeast(0.0),
            wheelForwardWorld = Vector3d(wheelForwardWorld),
            wheelRightWorld = Vector3d(wheelRightWorld),
            wheelVelocityWorld = VehiclePhysicsMath.velocityAtPoint(body, contactPoint)
        )
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
