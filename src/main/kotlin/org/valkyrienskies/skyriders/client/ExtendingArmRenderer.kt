package org.valkyrienskies.skyriders.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3d
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.entity.ExtendingArmEntity
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.sqrt

class ExtendingArmRenderer(context: EntityRendererProvider.Context) : EntityRenderer<ExtendingArmEntity>(context) {
    override fun render(
        entity: ExtendingArmEntity,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ) {
        renderTether(entity, partialTick, poseStack, bufferSource)
        renderHead(entity, poseStack, bufferSource)
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    override fun getTextureLocation(entity: ExtendingArmEntity): ResourceLocation = headTexture(entity)

    private fun renderHead(entity: ExtendingArmEntity, poseStack: PoseStack, bufferSource: MultiBufferSource) {
        val texture = headTexture(entity)
        val light = LevelRenderer.getLightColor(entity.level(), BlockPos.containing(entity.x, entity.y, entity.z))
        poseStack.pushPose()
        val velocity = entity.deltaMovement
        if (!entity.retracting && velocity.lengthSqr() > 1.0e-8) {
            val horizontal = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
            poseStack.mulPose(Axis.YP.rotationDegrees(Math.toDegrees(atan2(-velocity.x, -velocity.z)).toFloat()))
            poseStack.mulPose(Axis.XP.rotationDegrees(Math.toDegrees(atan2(velocity.y, horizontal)).toFloat()))
        } else {
            poseStack.mulPose(Axis.YP.rotationDegrees(-entity.yRot))
            poseStack.mulPose(Axis.XP.rotationDegrees(entity.xRot))
        }
        poseStack.pushPose()
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f))
        poseStack.scale(HEAD_MODEL_SCALE, HEAD_MODEL_SCALE, HEAD_MODEL_SCALE)
        poseStack.translate(-0.5, -0.5, -0.5)
        val renderedModel = VehicleOpenModelRenderer.renderTexturedIfNeeded(headModel(entity), texture, poseStack, bufferSource, light)
        poseStack.popPose()
        if (!renderedModel) {
            val buffer = bufferSource.getBuffer(RenderType.entityCutout(texture))
            drawBox(buffer, poseStack.last().pose(), poseStack.last().normal(), light, -HEAD_HALF, -HEAD_HALF, -HEAD_HALF, HEAD_HALF, HEAD_HALF, HEAD_HALF)
        }
        poseStack.popPose()
    }

    private fun renderTether(entity: ExtendingArmEntity, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource) {
        val owner = ownerPosition(entity) ?: return
        val entityX = entity.xOld + (entity.x - entity.xOld) * partialTick
        val entityY = entity.yOld + (entity.y - entity.yOld) * partialTick
        val entityZ = entity.zOld + (entity.z - entity.zOld) * partialTick
        val dx = owner.x - entityX
        val dy = owner.y + OWNER_Y_OFFSET - entityY
        val dz = owner.z - entityZ
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 0.05) return

        val buffer = bufferSource.getBuffer(RenderType.entityCutout(EXTENDING_SPRING_TEXTURE))
        val light = LevelRenderer.getLightColor(entity.level(), BlockPos.containing(entity.x, entity.y, entity.z))
        val horizontal = sqrt(dx * dx + dz * dz)

        poseStack.pushPose()
        poseStack.mulPose(Axis.YP.rotationDegrees(Math.toDegrees(atan2(dx, dz)).toFloat()))
        poseStack.mulPose(Axis.XP.rotationDegrees(Math.toDegrees(atan2(-dy, horizontal)).toFloat()))

        val segments = ceil(length / TETHER_TILE_LENGTH).toInt().coerceAtLeast(1)
        val segmentLength = length / segments
        var z = 0.0
        repeat(segments) {
            val nextZ = z + segmentLength
            drawTetherSegment(buffer, poseStack.last().pose(), poseStack.last().normal(), light, z.toFloat(), nextZ.toFloat())
            z = nextZ
        }
        poseStack.popPose()
    }

    private fun drawTetherSegment(buffer: VertexConsumer, matrix: Matrix4f, normal: Matrix3f, light: Int, z0: Float, z1: Float) {
        val h = TETHER_HALF_WIDTH
        quad(buffer, matrix, normal, light, -h, -h, z0, h, -h, z0, h, -h, z1, -h, -h, z1, SPRING_U_MIN, 0.0f, SPRING_U_MAX, 1.0f)
        quad(buffer, matrix, normal, light, h, h, z0, -h, h, z0, -h, h, z1, h, h, z1, SPRING_U_MIN, 0.0f, SPRING_U_MAX, 1.0f)
        quad(buffer, matrix, normal, light, -h, h, z0, -h, -h, z0, -h, -h, z1, -h, h, z1, SPRING_U_MIN, 0.0f, SPRING_U_MAX, 1.0f)
        quad(buffer, matrix, normal, light, h, -h, z0, h, h, z0, h, h, z1, h, -h, z1, SPRING_U_MIN, 0.0f, SPRING_U_MAX, 1.0f)
    }

    private fun drawBox(
        buffer: VertexConsumer,
        matrix: Matrix4f,
        normal: Matrix3f,
        light: Int,
        minX: Float,
        minY: Float,
        minZ: Float,
        maxX: Float,
        maxY: Float,
        maxZ: Float
    ) {
        quad(buffer, matrix, normal, light, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ)
        quad(buffer, matrix, normal, light, maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ)
        quad(buffer, matrix, normal, light, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ)
        quad(buffer, matrix, normal, light, maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ)
        quad(buffer, matrix, normal, light, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ)
        quad(buffer, matrix, normal, light, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ)
    }

    private fun quad(
        buffer: VertexConsumer,
        matrix: Matrix4f,
        normal: Matrix3f,
        light: Int,
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float,
        y2: Float,
        z2: Float,
        x3: Float,
        y3: Float,
        z3: Float,
        x4: Float,
        y4: Float,
        z4: Float,
        u1: Float = 0.0f,
        v1: Float = 0.0f,
        u2: Float = 1.0f,
        v2: Float = 1.0f
    ) {
        vertex(buffer, matrix, normal, x1, y1, z1, u1, v2, light)
        vertex(buffer, matrix, normal, x2, y2, z2, u2, v2, light)
        vertex(buffer, matrix, normal, x3, y3, z3, u2, v1, light)
        vertex(buffer, matrix, normal, x4, y4, z4, u1, v1, light)
    }

    private fun vertex(
        buffer: VertexConsumer,
        matrix: Matrix4f,
        normal: Matrix3f,
        x: Float,
        y: Float,
        z: Float,
        u: Float,
        v: Float,
        light: Int
    ) {
        buffer.vertex(matrix, x, y, z)
            .color(1.0f, 1.0f, 1.0f, 1.0f)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(light)
            .normal(normal, 0.0f, 1.0f, 0.0f)
            .endVertex()
    }

    private fun ownerPosition(entity: ExtendingArmEntity): Vector3d? {
        val body = entity.level().shipWorld?.allBodies?.getById(entity.ownerBodyId) ?: return null
        return Vector3d(body.kinematics.position)
    }

    private fun headTexture(entity: ExtendingArmEntity): ResourceLocation {
        return if (entity.armKind == ExtendingArmEntity.GRABBY_HAND) GRABBY_HEAD_TEXTURE else BOXING_HEAD_TEXTURE
    }

    private fun headModel(entity: ExtendingArmEntity): ResourceLocation {
        return if (entity.armKind == ExtendingArmEntity.GRABBY_HAND) GRABBY_HEAD_MODEL else BOXING_HEAD_MODEL
    }

    companion object {
        val BOXING_HEAD_MODEL = ResourceLocation(SkyridersMod.MOD_ID, "entity/boxing_glove")
        val GRABBY_HEAD_MODEL = ResourceLocation(SkyridersMod.MOD_ID, "entity/grabby_hand")
        private val BOXING_HEAD_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/entity/boxing_glove.png")
        private val GRABBY_HEAD_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/entity/grabby_hand.png")
        private val EXTENDING_SPRING_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/entity/extending_spring.png")
        private const val HEAD_HALF = 0.32f
        private const val HEAD_MODEL_SCALE = 0.76f
        private const val TETHER_HALF_WIDTH = 0.09f
        private const val TETHER_TILE_LENGTH = 0.7
        private const val SPRING_U_MIN = 6.0f / 16.0f
        private const val SPRING_U_MAX = 10.0f / 16.0f
        private const val OWNER_Y_OFFSET = 0.5
    }
}
