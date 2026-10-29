package org.valkyrienskies.skyriders.util

import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.shape.BoxBodyShapeData
import org.valkyrienskies.core.api.bodies.shape.CompoundBodyShapeData
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.skyriders.content.VehicleCollisionBoxDefinition

object VehicleStepClearance {
    fun canRise(body: PhysVsBody, level: PhysLevel, height: Double, size: Vector3dc, offset: Vector3dc,
                collisionBoxes: List<VehicleCollisionBoxDefinition>? = null): Boolean {
        if (!height.isFinite() || height <= 0.0) return false
        val shape = body.collisionShape
        val boxes = collisionBoxes?.mapIndexed { index, box ->
            CompoundBodyShapeData.Child(BoxBodyShapeData(box.size), box.offset, box.rotationQuaternion(), segmentId = index)
        } ?: when (shape) {
            is BoxBodyShapeData -> listOf(CompoundBodyShapeData.Child(shape, segmentId = 0))
            is CompoundBodyShapeData -> shape.children
            null -> listOf(CompoundBodyShapeData.Child(BoxBodyShapeData(size), offset, segmentId = 0))
            else -> return false
        }
        val pose = body.kinematics
        return boxes.all { child ->
            val box = child.shape as? BoxBodyShapeData ?: return@all false
            val offset = child.rotation.transform(Vector3d(child.collisionShapeOffset).mul(child.collisionShapeScaling)).add(child.position)
            val center = pose.toWorld.transformPosition(offset)
            val halfSize = Vector3d(box.lengths).mul(child.collisionShapeScaling * 0.5).mul(pose.scaling).sub(0.015, 0.015, 0.015)
            val hit = level.castBox(center, Quaterniond(pose.rotation).mul(child.rotation), halfSize,
                Vector3d(0.0, 1.0, 0.0), height + 0.03, body.id)
            hit == null || (hit.geometrySupported && hit.normal.isFinite && hit.normal.y() >= -0.25)
        }
    }
}
