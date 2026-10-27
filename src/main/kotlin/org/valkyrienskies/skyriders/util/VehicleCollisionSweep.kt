package org.valkyrienskies.skyriders.util

import net.minecraft.world.phys.AABB
import org.joml.Matrix4d
import org.joml.Matrix4dc
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.properties.BodyTransform
import org.valkyrienskies.skyriders.content.VehicleCollisionBoxDefinition
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max

internal object VehicleCollisionSweep {
    private const val CONTACT_TOLERANCE = 1.0e-5
    private val axes = listOf(Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, 1.0))

    data class Hit(val fraction: Double, val point: Vector3d, val normal: Vector3d)
    private data class Box(val center: Vector3d, val edges: List<Vector3d>)
    private data class Separation(val distance: Double, val normal: Vector3d)

    fun cast(
        shapes: List<VehicleCollisionBoxDefinition>,
        from: BodyTransform,
        to: BodyTransform,
        target: AABB,
        targetMovement: Vector3dc = Vector3d()
    ): Hit? = shapes.mapNotNull { cast(it, from, to, target, targetMovement) }.minByOrNull { it.fraction }

    fun bounds(shapes: List<VehicleCollisionBoxDefinition>, transform: Matrix4dc): AABB =
        shapes.map { shape ->
            val box = box(shape, transform)
            val extent = Vector3d()
            box.edges.forEach { extent.add(Vector3d(it).absolute()) }
            AABB(box.center.x - extent.x, box.center.y - extent.y, box.center.z - extent.z,
                box.center.x + extent.x, box.center.y + extent.y, box.center.z + extent.z)
        }.reduce(AABB::minmax)

    fun sweptBounds(shapes: List<VehicleCollisionBoxDefinition>, from: BodyTransform, to: BodyTransform): AABB {
        val radius = shapes.maxOf { radius(it, from, to) } * maxScale(from, to)
        return AABB(from.position.x(), from.position.y(), from.position.z(),
            to.position.x(), to.position.y(), to.position.z()).inflate(radius)
    }

    private fun cast(shape: VehicleCollisionBoxDefinition, from: BodyTransform, to: BodyTransform,
                     target: AABB, targetMovement: Vector3dc): Hit? {
        val targetCenter = Vector3d((target.minX + target.maxX) * 0.5,
            (target.minY + target.maxY) * 0.5, (target.minZ + target.maxZ) * 0.5)
        val targetHalf = Vector3d(target.xsize, target.ysize, target.zsize).mul(0.5)
        val start = box(shape, from.toWorld)
        val end = box(shape, to.toWorld)
        val angle = 2.0 * acos(abs(from.rotation.dot(to.rotation)).coerceIn(0.0, 1.0))
        val unchangedShape = angle < 1.0e-10 && from.scaling.distanceSquared(to.scaling) < 1.0e-20 &&
            from.positionInModel.distanceSquared(to.positionInModel) < 1.0e-20
        val fraction: Double
        if (unchangedShape) {
            fraction = linearCast(start, Vector3d(end.center).sub(start.center), targetCenter, targetHalf, targetMovement) ?: return null
        } else {
            val localRadius = radius(shape, from, to)
            val speedBound = Vector3d(to.position).sub(from.position).sub(targetMovement).length() +
                angle * maxScale(from, to) * localRadius + from.scaling.distance(to.scaling) * localRadius +
                from.positionInModel.distance(to.positionInModel) * maxScale(from, to)
            fun separationAt(t: Double): Separation = separation(box(shape, transform(from, to, t)),
                Vector3d(targetCenter).fma(t, targetMovement), targetHalf)
            val initial = separationAt(0.0)
            if (initial.distance <= CONTACT_TOLERANCE) fraction = 0.0
            else if (speedBound <= 1.0e-12) return null
            else {
                fun search(low: Double, high: Double): Double? {
                    val middle = (low + high) * 0.5
                    val gap = separationAt(middle).distance
                    if (gap > speedBound * (high - low) * 0.5 + CONTACT_TOLERANCE) return null
                    if (speedBound * (high - low) <= CONTACT_TOLERANCE) return middle
                    return search(low, middle) ?: search(middle, high)
                }
                fraction = search(0.0, 1.0) ?: return null
            }
        }
        val matrix = transform(from, to, fraction)
        val targetAtHit = Vector3d(targetCenter).fma(fraction, targetMovement)
        val worldBox = box(shape, matrix)
        val normal = separation(worldBox, targetAtHit, targetHalf).normal
        val local = matrix.invert(Matrix4d()).transformPosition(targetAtHit, Vector3d()).sub(shape.offset)
        shape.rotationQuaternion().invert().transform(local)
        local.x = local.x.coerceIn(-shape.size.x * 0.5, shape.size.x * 0.5)
        local.y = local.y.coerceIn(-shape.size.y * 0.5, shape.size.y * 0.5)
        local.z = local.z.coerceIn(-shape.size.z * 0.5, shape.size.z * 0.5)
        val point = matrix.transformPosition(shape.rotationQuaternion().transform(local).add(shape.offset))
        return Hit(fraction, point, normal)
    }

    private fun linearCast(box: Box, movement: Vector3dc, target: Vector3dc, half: Vector3dc,
                           targetMovement: Vector3dc): Double? {
        val offset = Vector3d(target).sub(box.center)
        val relative = Vector3d(targetMovement).sub(movement)
        var first = 0.0
        var last = 1.0
        for (axis in separatingAxes(box)) {
            val radius = projectionRadius(box, half, axis)
            val position = offset.dot(axis)
            val speed = relative.dot(axis)
            if (abs(speed) < 1.0e-12) {
                if (abs(position) > radius) return null
            } else {
                val a = (-radius - position) / speed
                val b = (radius - position) / speed
                first = maxOf(first, minOf(a, b))
                last = minOf(last, maxOf(a, b))
                if (first > last) return null
            }
        }
        return first
    }

    private fun separation(box: Box, target: Vector3dc, half: Vector3dc): Separation {
        val offset = Vector3d(target).sub(box.center)
        var best = Separation(Double.NEGATIVE_INFINITY, Vector3d(0.0, 1.0, 0.0))
        for (axis in separatingAxes(box)) {
            val projected = offset.dot(axis)
            val gap = abs(projected) - projectionRadius(box, half, axis)
            if (gap > best.distance) best = Separation(gap, Vector3d(axis).mul(if (projected >= 0.0) 1.0 else -1.0))
        }
        return best
    }

    private fun separatingAxes(box: Box): List<Vector3d> = buildList {
        addAll(axes)
        for (i in 0..2) add(Vector3d(box.edges[i]).cross(box.edges[(i + 1) % 3]))
        for (edge in box.edges) for (axis in axes) add(Vector3d(edge).cross(axis))
    }.filter { it.lengthSquared() > 1.0e-16 }.map { Vector3d(it).normalize() }

    private fun projectionRadius(box: Box, half: Vector3dc, axis: Vector3dc): Double =
        box.edges.sumOf { abs(it.dot(axis)) } + abs(axis.x()) * half.x() + abs(axis.y()) * half.y() + abs(axis.z()) * half.z()

    private fun box(shape: VehicleCollisionBoxDefinition, transform: Matrix4dc): Box {
        val rotation = shape.rotationQuaternion()
        return Box(transform.transformPosition(shape.offset, Vector3d()), axes.mapIndexed { index, axis ->
            transform.transformDirection(rotation.transform(Vector3d(axis)).mul(shape.size[index] * 0.5))
        })
    }

    private fun transform(from: BodyTransform, to: BodyTransform, t: Double): Matrix4d = Matrix4d()
        .translate(Vector3d(from.position).lerp(to.position, t))
        .rotate(Quaterniond(from.rotation).slerp(to.rotation, t))
        .scale(Vector3d(from.scaling).lerp(to.scaling, t))
        .translate(Vector3d(from.positionInModel).lerp(to.positionInModel, t).negate())

    private fun radius(shape: VehicleCollisionBoxDefinition, from: BodyTransform, to: BodyTransform): Double =
        max(shape.offset.distance(from.positionInModel), shape.offset.distance(to.positionInModel)) + shape.size.length() * 0.5

    private fun maxScale(from: BodyTransform, to: BodyTransform): Double = maxOf(
        abs(from.scaling.x()), abs(from.scaling.y()), abs(from.scaling.z()),
        abs(to.scaling.x()), abs(to.scaling.y()), abs(to.scaling.z()))
}
