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
    val relativeWheelVelocityWorld: Vector3d,
    val suspensionPointWorld: Vector3d = contactPointWorld,
    val suspensionRelativeVelocityWorld: Vector3d = relativeWheelVelocityWorld,
    val stepRise: Double = 0.0
)

object VehicleWheelPhysics {
    data class StepSettings(val height: Double, val direction: Double, val canRise: (Double) -> Boolean)
    private const val MIN_GROUND_NORMAL_ALIGNMENT = 0.28
    private const val DEFAULT_SURFACE_FRICTION_COEFFICIENT = 0.6
    private const val MIN_SURFACE_FRICTION_SCALE = 0.12
    private const val MAX_SURFACE_FRICTION_SCALE = 1.65

    fun sampleSphereWheel(
        body: PhysVsBody, physLevel: PhysLevel, mountWorld: Vector3dc,
        maxLength: Double, radius: Double, wheelForwardWorld: Vector3dc,
        groundedMaxDistance: Double = maxLength,
        step: StepSettings? = null
    ): VehicleWheelContact {
        val direction = Vector3d(0.0, -1.0, 0.0)
        val originLift = (maxLength - radius).coerceAtLeast(radius)
        val hit = physLevel.castSphere(Vector3d(mountWorld).fma(-originLift, direction), radius,
            direction, maxLength - radius + originLift, body.id)
        val valid = hit?.let { it.geometrySupported && !it.initialOverlap && it.distance.isFinite() &&
            it.normal.isFinite && it.velocity.isFinite && it.normal.y() >= MIN_GROUND_NORMAL_ALIGNMENT &&
            it.normal.dot(direction) <= -MIN_GROUND_NORMAL_ALIGNMENT } == true
        val distance = if (valid) hit!!.distance + radius - originLift else maxLength
        val point = if (valid) Vector3d(hit!!.point) else Vector3d(mountWorld)
        val normal = if (valid) Vector3d(hit!!.normal).normalize() else Vector3d(direction).negate()
        val surfaceVelocity = if (valid) Vector3d(hit!!.velocity) else Vector3d()
        val velocity = VehiclePhysicsMath.velocityAtPoint(body, point)
        val up = if (valid) normal else Vector3d(direction).negate()
        val forward = VehiclePhysicsMath.projectOntoPlane(wheelForwardWorld, up, Vector3d(0.0, 0.0, 1.0))
        val right = Vector3d(up).cross(forward).normalize()
        val contact = VehicleWheelContact(valid && distance <= groundedMaxDistance, if (valid) hit!!.hitBody else null,
            point, normal, direction, distance, (maxLength - distance).coerceAtLeast(0.0),
            if (valid) surfaceFrictionScale(hit!!.staticFriction, hit.dynamicFriction) else 1.0,
            forward, right, velocity, surfaceVelocity,
            Vector3d(velocity).sub(surfaceVelocity), Vector3d(mountWorld),
            VehiclePhysicsMath.velocityAtPoint(body, mountWorld).sub(surfaceVelocity))
        return if (step != null) stepContact(body, physLevel, contact, mountWorld, radius, maxLength,
            groundedMaxDistance, step) else contact
    }

    private fun stepContact(body: PhysVsBody, level: PhysLevel, contact: VehicleWheelContact,
                            mount: Vector3dc, radius: Double, maxLength: Double,
                            groundedMaxDistance: Double, step: StepSettings): VehicleWheelContact {
        val maxStep = step.height
        if (!maxStep.isFinite() || maxStep <= 0.0) return contact
        val down = Vector3d(0.0, -1.0, 0.0)
        val up = Vector3d(0.0, 1.0, 0.0)
        val floor = level.rayCast(Vector3d(mount).add(0.0, maxStep + 0.1, 0.0), down,
            maxLength + maxStep * 2.0 + 0.1, body.id) ?: return contact
        if (!isSupportHit(floor, body.id, maxLength + maxStep * 2.0 + 0.1, up)) return contact
        val floorPoint = Vector3d(mount).add(0.0, maxStep + 0.1 - floor.distance, 0.0)
        val velocity = Vector3d(contact.suspensionRelativeVelocityWorld).mul(1.0, 0.0, 1.0)
        val forward = Vector3d(contact.wheelForwardWorld).mul(1.0, 0.0, 1.0).normalize()
        val travel = if (velocity.lengthSquared() > 0.25) velocity.normalize() else Vector3d(forward).mul(if (step.direction < 0.0) -1.0 else 1.0)
        if (kotlin.math.abs(travel.dot(forward)) < 0.25) return contact
        val reach = radius + maxStep * 1.5
        val probeReach = reach / 0.2
        var profileRise = 0.0
        val profileNormal = Vector3d(0.0, 1.0, 0.0)
        var bestHit: RayCastResult? = null
        var cursor = 0.0
        var height = floorPoint.y
        for (probe in 0 until 4) {
            if (cursor >= probeReach) break
            val start = Vector3d(floorPoint).fma(cursor, travel).setComponent(1, height + radius * 0.4)
            val face = level.rayCast(start, travel, probeReach - cursor, body.id) ?: break
            if (!face.distance.isFinite() || face.distance <= 1.0e-5 || !face.hitNormal.isFinite ||
                face.hitNormal.y() > 0.25 || face.hitNormal.dot(travel) > -0.2) break
            val edgeDistance = cursor + face.distance
            val faceDistance = edgeDistance * -face.hitNormal.dot(travel)
            if (faceDistance >= reach) break
            val topStart = Vector3d(floorPoint).fma(edgeDistance + 0.02, travel)
                .setComponent(1, height + maxStep + 0.04)
            val top = level.rayCast(topStart, down, maxStep + 0.05, body.id) ?: break
            if (!isSupportHit(top, body.id, maxStep + 0.05, up)) break
            val topHeight = topStart.y - top.distance
            if (topHeight - height !in 0.05..maxStep) break
            val t = (1.0 - faceDistance / reach).coerceIn(0.0, 1.0)
            val rise = topHeight - height
            profileRise += rise * t * t * (3.0 - 2.0 * t)
            val slope = rise * 6.0 * t * (1.0 - t) / reach
            profileNormal.fma(slope, face.hitNormal)
            bestHit = top
            cursor = edgeDistance + 0.04
            height = topHeight
        }
        val hit = bestHit ?: return contact
        val bestHeight = floorPoint.y + profileRise
        if (bestHeight <= mount.y() - contact.hitDistance + 1.0e-4 || !step.canRise(maxOf(profileRise, (height - floorPoint.y).coerceAtMost(maxStep)))) return contact
        val bestNormal = profileNormal.normalize()
        val point = Vector3d(mount).setComponent(1, bestHeight)
        val distance = mount.y() - bestHeight
        val wheelVelocity = VehiclePhysicsMath.velocityAtPoint(body, point)
        val surfaceVelocity = Vector3d(hit.velocity)
        val wheelForward = VehiclePhysicsMath.projectOntoPlane(contact.wheelForwardWorld, bestNormal, forward)
        return contact.copy(grounded = distance <= groundedMaxDistance, hitBody = hit.hitBody,
            contactPointWorld = point, contactNormalWorld = bestNormal, suspensionDirWorld = down,
            hitDistance = distance, compressionDistance = (maxLength - distance).coerceAtLeast(0.0),
            wheelForwardWorld = wheelForward, wheelRightWorld = Vector3d(bestNormal).cross(wheelForward).normalize(),
            wheelVelocityWorld = wheelVelocity, surfaceVelocityWorld = surfaceVelocity,
            relativeWheelVelocityWorld = Vector3d(wheelVelocity).sub(surfaceVelocity),
            suspensionRelativeVelocityWorld = VehiclePhysicsMath.velocityAtPoint(body, mount).sub(surfaceVelocity),
            stepRise = bestHeight - floorPoint.y)
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
