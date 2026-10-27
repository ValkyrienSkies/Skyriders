package org.valkyrienskies.skyriders.util

import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.shape.BoxBodyShapeData
import org.valkyrienskies.core.api.bodies.shape.CompoundBodyShapeData
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.skyriders.content.VehicleCollisionBoxDefinition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class VehicleStepState {
    internal data class Surface(val point: Vector3d, val body: Long, val segment: Long)
    internal val surfaces = ArrayList<Surface>()
    val surfaceVelocity = Vector3d()
    val surfaceAngularVelocity = Vector3d()
    val surfaceAnchor = Vector3d()
    val direction = Vector3d()
    val initialNormal = Vector3d(0.0, 1.0, 0.0)
    val initialPoint = Vector3d()
    var remaining = 0.0
    var targetSpeed = 0.0
    var rideHeight = 0.0
    var rise = 0.0

    fun clear() { surfaces.clear(); remaining = 0.0 }
}

object VehicleStepTraversal {
    private val UP = Vector3d(0.0, 1.0, 0.0)
    private val DOWN = Vector3d(0.0, -1.0, 0.0)

    fun update(body: PhysVsBody, level: PhysLevel, contacts: List<VehicleWheelContact>,
               size: Vector3dc, offset: Vector3dc, mass: Double, maxStep: Double, strength: Double, nominalRideHeight: Double,
               enabled: Boolean, throttle: Double, suspensionForce: Double,
               state: VehicleStepState, dt: Double, collisionBoxes: List<VehicleCollisionBoxDefinition>? = null): Double {
        if (!enabled || !maxStep.isFinite() || maxStep <= 0.0 || !strength.isFinite() || strength <= 0.0 ||
            !mass.isFinite() || mass <= 0.0 || dt <= 0.0 || !dt.isFinite()) {
            state.clear()
            return 0.0
        }
        val grounded = contacts.filter { it.grounded }
        val pose = body.kinematics
        val rideHeight = (nominalRideHeight + pose.positionInModel.y()) * pose.scaling.y()
        val chassis = collisionBoxes?.map { ChassisBox(it.offset, it.rotationQuaternion(), Vector3d(it.size).mul(0.5)) }
            ?: chassisBoxes(body, size, offset)
        if (chassis.isEmpty()) { state.clear(); return 0.0 }
        fun castChassis(position: Vector3dc, rotation: Quaterniondc, direction: Vector3dc, distance: Double) =
            chassis.mapNotNull { box ->
                val boxCenter = rotation.transform(Vector3d(box.offset).sub(pose.positionInModel).mul(pose.scaling)).add(position)
                val boxRotation = Quaterniond(rotation).mul(box.rotation)
                val boxHalf = Vector3d(box.halfSize).mul(pose.scaling).sub(0.015, 0.015, 0.015)
                level.castBox(boxCenter, boxRotation, boxHalf, direction, distance, body.id)
            }.minByOrNull { it.distance }
        val center = pose.toWorld.transformPosition(offset, Vector3d())
        val halfSize = Vector3d(size).mul(pose.scaling).mul(0.5).sub(0.015, 0.015, 0.015)
        val forward = VehiclePhysicsMath.transformDirection(body, Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 0.0, 1.0))
        forward.y = 0.0
        if (forward.lengthSquared() < 0.1) { state.clear(); return 0.0 }
        forward.normalize()
        val supportVelocity = if (grounded.isNotEmpty()) {
            grounded.fold(Vector3d()) { sum, contact -> sum.add(contact.surfaceVelocityWorld) }.div(grounded.size.toDouble())
        } else Vector3d(state.surfaceVelocity)
        val relativeVelocity = Vector3d(pose.velocity).sub(supportVelocity)
        val signedSpeed = relativeVelocity.dot(forward)
        val direction = Vector3d(forward).mul(if (abs(signedSpeed) > 0.5) Math.signum(signedSpeed) else Math.signum(throttle))
        val speed = max(abs(signedSpeed), abs(throttle) * 1.5)
        state.remaining -= dt
        if (state.remaining <= 0 || state.direction.dot(direction) < 0.5) state.clear()
        if (state.surfaces.isNotEmpty()) {
            val angularSpeed = state.surfaceAngularVelocity.length()
            val rotation = if (angularSpeed > 1.0e-9) Quaterniond().rotationAxis(angularSpeed * dt,
                state.surfaceAngularVelocity.x / angularSpeed, state.surfaceAngularVelocity.y / angularSpeed,
                state.surfaceAngularVelocity.z / angularSpeed) else Quaterniond()
            fun advance(point: Vector3d) {
                point.sub(state.surfaceAnchor)
                rotation.transform(point)
                point.add(state.surfaceAnchor).fma(dt, state.surfaceVelocity)
            }
            state.surfaces.forEach { advance(it.point) }
            advance(state.initialPoint)
            rotation.transform(state.initialNormal)
            rotation.transform(state.direction)
        }

        if (state.surfaces.isEmpty() && grounded.isNotEmpty() && speed > 0.4 && direction.lengthSquared() > 0.5) {
            val support = grounded.fold(Vector3d()) { sum, contact -> sum.add(contact.contactPointWorld) }.div(grounded.size.toDouble())
            val normal = VehiclePhysicsMath.safeNormalize(grounded.fold(Vector3d()) { sum, contact -> sum.add(contact.contactNormalWorld) }, UP)
            if (normal.y < 0.65) return 0.0
            val motion = VehiclePhysicsMath.projectOntoPlane(direction, normal, direction)
            val lookAhead = (speed * 0.42 + 0.35).coerceIn(0.7, 12.0)
            val chassisHit = castChassis(pose.position, pose.rotation, motion, lookAhead)
            val probeCenter = Vector3d(center)
            val probeHalf = Vector3d(halfSize)
            probeHalf.y = max(0.05, (center.y - support.y - 0.1) * 0.5)
            probeCenter.y = support.y + 0.1 + probeHalf.y
            val obstacle = chassisHit ?: level.castBox(probeCenter, pose.rotation, probeHalf, motion, lookAhead, body.id)
            if (obstacle != null && obstacle.geometrySupported && !obstacle.initialOverlap &&
                obstacle.normal.dot(UP) < 0.25 && obstacle.normal.dot(direction) < -0.5) {
                val topStart = Vector3d(obstacle.point).fma(0.15, direction)
                val predictedHeight = planeHeight(support, normal, topStart)
                topStart.y = predictedHeight + maxStep + 0.2
                val top = level.castSphere(topStart, 0.05, DOWN, maxStep + 0.3, body.id)
                if (top != null && top.geometrySupported && !top.initialOverlap && top.normal.y() > 0.65 &&
                    top.hitBody.id == obstacle.hitBody.id && top.segmentId == obstacle.segmentId) {
                    val rise = top.point.y() - predictedHeight
                    val lift = planeHeight(support, normal, pose.position) + rise + rideHeight - pose.position.y()
                    if (rise >= 0.12 && rise <= maxStep + 1.0e-4 && lift > 0.0 && lift <= maxStep + 0.2) {
                        val ceiling = castChassis(pose.position, pose.rotation, UP, lift + 0.03)
                        val elevated = Vector3d(pose.position).add(0.0, lift + 0.03, 0.0)
                        val ahead = if (ceiling == null) castChassis(elevated, pose.rotation,
                            motion, obstacle.distance + 0.3) else ceiling
                        if (ceiling == null && ahead == null) {
                            state.surfaces.add(VehicleStepState.Surface(Vector3d(top.point), top.hitBody.id, top.segmentId))
                            state.surfaceVelocity.set(top.velocity)
                            state.surfaceAngularVelocity.set(top.angularVelocity)
                            state.surfaceAnchor.set(top.point)
                            state.direction.set(direction)
                            state.initialNormal.set(normal)
                            state.initialPoint.set(support)
                            state.rise = rise
                            state.remaining = ((size.z() + obstacle.distance + max(4.0, speed * 0.6)) / max(speed, 1.0) + 0.6).coerceIn(0.6, 3.0)
                            state.targetSpeed = max(0.0, relativeVelocity.dot(direction))
                            state.rideHeight = rideHeight
                            for (index in 1..(max(4.0, speed * 0.6) / 0.5).toInt()) {
                                val previous = state.surfaces.last()
                                val sample = Vector3d(previous.point).fma(0.5, direction).add(0.0, maxStep + 0.15, 0.0)
                                val next = level.castSphere(sample, 0.05, DOWN, maxStep + 0.3, body.id) ?: break
                                if (!next.geometrySupported || next.initialOverlap || next.normal.y() < 0.65 ||
                                    next.point.y() - previous.point.y !in -0.1..(maxStep + 1.0e-4) ||
                                    Vector3d(next.velocity).sub(top.velocity).lengthSquared() > 0.25) break
                                state.surfaces.add(VehicleStepState.Surface(Vector3d(next.point), next.hitBody.id, next.segmentId))
                            }
                        }
                    }
                }
            }
        }
        if (state.surfaces.isEmpty()) return 0.0
        val nearest = state.surfaces.minBy { Vector3d(it.point).sub(pose.position).let { p -> p.x * p.x + p.z * p.z } }
        val probe = level.castSphere(Vector3d(nearest.point).add(0.0, 0.3, 0.0), 0.05, DOWN, 0.6, body.id)
        if (probe == null || !probe.geometrySupported || probe.initialOverlap ||
            probe.hitBody.id != nearest.body || probe.segmentId != nearest.segment || probe.normal.y() < 0.65) {
            state.clear()
            return 0.0
        }
        state.surfaceVelocity.set(probe.velocity)
        state.surfaceAngularVelocity.set(probe.angularVelocity)
        state.surfaceAnchor.set(probe.point)
        val relative = Vector3d(pose.velocity).sub(probe.velocity)
        val targetPoint = Vector3d(pose.position).fma(speed * 0.15, state.direction)
        val targetHeight = heightAt(state, targetPoint)
        fun slopeAt(point: Vector3dc) = ((heightAt(state, Vector3d(point).fma(1.0, state.direction)) -
            heightAt(state, Vector3d(point).fma(-1.0, state.direction))) * 0.5).coerceIn(0.0, 0.75)
        val slope = max(slopeAt(pose.position), slopeAt(targetPoint))
        val targetNormal = Vector3d(UP).fma(-slope, state.direction).normalize()
        val targetRotation = Quaterniond().rotationTo(UP, targetNormal).mul(Quaterniond().rotationTo(Vector3d(0.0, 0.0, 1.0), forward))
        val clearancePosition = Vector3d(pose.position)
        clearancePosition.y += max(0.0, targetHeight + rideHeight - pose.position.y())
        val blocked = castChassis(clearancePosition, targetRotation, UP, 0.0)
        if (blocked != null) { state.clear(); return 0.0 }
        val error = targetHeight + state.rideHeight - pose.position.y()
        val frequency = 10.0
        val ascentSlope = max(-state.initialNormal.dot(state.direction) / state.initialNormal.y,
            state.surfaces.maxOf { slopeAt(it.point) })
        val entryAscent = max(0.0, ascentSlope * state.targetSpeed)
        val crestFlight = entryAscent * entryAscent / (2.0 * 9.81)
        val heightBudget = state.surfaces.maxOf { it.point.y } + rideHeight + 0.25 + crestFlight - pose.position.y() - max(0.0, relative.y) * 0.12
        val stoppingUpSpeed = sqrt(max(0.0, 2.0 * 22.0 * heightBudget))
        val targetUp = min(stoppingUpSpeed, slope * max(0.0, relative.dot(state.direction)) +
            min(error * frequency, sqrt(max(0.0, 2.0 * 22.0 * error))))
        val acceleration = (targetUp - relative.y) * frequency
        val load = (mass * (9.81 + acceleration) - suspensionForce).coerceIn(-mass * 22.0, min(strength, mass * 45.0))
        var carry = ((state.targetSpeed - relative.dot(state.direction)) * mass * 5.0).coerceIn(0.0, mass * 4.0)
        val motionSpeed = relative.length()
        if (motionSpeed > 1.0) {
            val impending = castChassis(pose.position, pose.rotation, Vector3d(relative).div(motionSpeed), motionSpeed * 0.35)
            if (impending != null && impending.geometrySupported && !impending.initialOverlap &&
                impending.normal.y() < 0.25 && impending.normal.dot(state.direction) < -0.5) {
                val missingHeight = (heightAt(state, impending.point) - impending.point.y() + 0.08).coerceAtLeast(0.0)
                val time = impending.distance / motionSpeed
                if (missingHeight > 0.01) {
                    val safeFraction = sqrt(30.0 * time * time / (2.0 * missingHeight)).coerceIn(0.0, 1.0)
                    carry = min(carry, mass * relative.dot(state.direction) * (safeFraction - 1.0) / dt)
                        .coerceAtLeast(-mass * 60.0)
                }
            }
        }
        val force = Vector3d(state.direction).mul(carry).add(0.0, load, 0.0)
        VehiclePhysicsMath.safeApplyWorldForce(body, force, pose.position)
        val bodyUp = VehiclePhysicsMath.transformDirection(body, UP, UP)
        val tilt = Vector3d(bodyUp).cross(targetNormal).mul(mass * 60.0)
            .sub(Vector3d(pose.angularVelocity).sub(probe.angularVelocity).mul(mass * 12.0))
        tilt.fma(-tilt.dot(UP), UP)
        VehiclePhysicsMath.safeApplyWorldTorque(body, tilt)
        if (!probe.hitBody.isStatic) {
            VehiclePhysicsMath.safeApplyWorldForce(probe.hitBody, Vector3d(force).negate(), pose.position)
            VehiclePhysicsMath.safeApplyWorldTorque(probe.hitBody, Vector3d(tilt).negate())
        }
        val end = state.surfaces.last().point
        if (grounded.size == contacts.size && abs(error) < 0.15 &&
            grounded.all { it.contactPointWorld.y >= end.y - 0.12 }) state.clear()
        return max(0.0, load / mass)
    }

    private data class ChassisBox(val offset: Vector3dc, val rotation: Quaterniondc, val halfSize: Vector3d)

    private fun chassisBoxes(body: PhysVsBody, size: Vector3dc, offset: Vector3dc): List<ChassisBox> =
        when (val shape = body.collisionShape) {
            null -> listOf(ChassisBox(offset, Quaterniond(), Vector3d(size).mul(0.5)))
            is BoxBodyShapeData -> listOf(ChassisBox(Vector3d(), Quaterniond(), Vector3d(shape.lengths).mul(0.5)))
            is CompoundBodyShapeData -> if (shape.children.all { it.shape is BoxBodyShapeData }) {
                shape.children.map { child ->
                    ChassisBox(child.rotation.transform(Vector3d(child.collisionShapeOffset).mul(child.collisionShapeScaling)).add(child.position),
                        child.rotation, Vector3d((child.shape as BoxBodyShapeData).lengths).mul(child.collisionShapeScaling * 0.5))
                }
            } else emptyList()
            else -> emptyList()
        }

    private fun planeHeight(point: Vector3dc, normal: Vector3dc, at: Vector3dc): Double =
        point.y() - (normal.x() * (at.x() - point.x()) + normal.z() * (at.z() - point.z())) / normal.y()

    private fun heightAt(state: VehicleStepState, at: Vector3dc): Double {
        val first = state.surfaces.first().point
        val progress = Vector3d(at).sub(first).dot(state.direction)
        if (progress < 0) return min(first.y, planeHeight(state.initialPoint, state.initialNormal, at) + state.rise)
        for (index in 1 until state.surfaces.size) {
            val before = state.surfaces[index - 1].point
            val after = state.surfaces[index].point
            val end = Vector3d(after).sub(first).dot(state.direction)
            if (progress <= end) {
                val start = Vector3d(before).sub(first).dot(state.direction)
                return before.y + (after.y - before.y) * ((progress - start) / (end - start).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            }
        }
        return state.surfaces.last().point.y
    }
}
