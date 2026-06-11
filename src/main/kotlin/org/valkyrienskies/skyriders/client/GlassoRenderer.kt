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
import org.valkyrienskies.skyriders.content.entity.GlassoEntity
import kotlin.math.atan2
import kotlin.math.sqrt

class GlassoRenderer(context: EntityRendererProvider.Context) : EntityRenderer<GlassoEntity>(context) {
    override fun render(
        entity: GlassoEntity,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ) {
        renderTether(entity, partialTick, poseStack, bufferSource)
        renderBolt(entity, poseStack, bufferSource)
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    override fun getTextureLocation(entity: GlassoEntity): ResourceLocation = GLASSO_BOLT_TEXTURE

    private fun renderBolt(entity: GlassoEntity, poseStack: PoseStack, bufferSource: MultiBufferSource) {
        val velocity = entity.deltaMovement
        val light = LevelRenderer.getLightColor(entity.level(), BlockPos.containing(entity.x, entity.y, entity.z))
        poseStack.pushPose()
        if (velocity.lengthSqr() > 1.0e-8) {
            val horizontal = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
            poseStack.mulPose(Axis.YP.rotationDegrees(Math.toDegrees(atan2(-velocity.x, -velocity.z)).toFloat()))
            poseStack.mulPose(Axis.XP.rotationDegrees(Math.toDegrees(atan2(velocity.y, horizontal)).toFloat()))
        } else {
            poseStack.mulPose(Axis.YP.rotationDegrees(-entity.yRot))
            poseStack.mulPose(Axis.XP.rotationDegrees(entity.xRot))
        }

        val buffer = bufferSource.getBuffer(RenderType.entityCutout(GLASSO_BOLT_TEXTURE))
        val pose = poseStack.last()
        val matrix = pose.pose()
        val normal = pose.normal()
        texturedQuad(buffer, matrix, normal, light, -BOLT_HALF_WIDTH, -BOLT_HALF_WIDTH, -BOLT_LENGTH * 0.5f, BOLT_HALF_WIDTH, BOLT_HALF_WIDTH, BOLT_LENGTH * 0.5f)
        poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f))
        texturedQuad(buffer, poseStack.last().pose(), poseStack.last().normal(), light, -BOLT_HALF_WIDTH, -BOLT_HALF_WIDTH, -BOLT_LENGTH * 0.5f, BOLT_HALF_WIDTH, BOLT_HALF_WIDTH, BOLT_LENGTH * 0.5f)
        poseStack.popPose()
    }

    private fun texturedQuad(
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
        vertex(buffer, matrix, normal, minX, minY, minZ, 0.0f, 1.0f, light)
        vertex(buffer, matrix, normal, maxX, maxY, maxZ, 1.0f, 0.0f, light)
        vertex(buffer, matrix, normal, maxX, minY, maxZ, 1.0f, 1.0f, light)
        vertex(buffer, matrix, normal, minX, maxY, minZ, 0.0f, 0.0f, light)
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

    private fun renderTether(entity: GlassoEntity, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource) {
        val source = tetherSource(entity) ?: return
        val entityX = entity.xOld + (entity.x - entity.xOld) * partialTick
        val entityY = entity.yOld + (entity.y - entity.yOld) * partialTick
        val entityZ = entity.zOld + (entity.z - entity.zOld) * partialTick
        val dx = (source.x - entityX).toFloat()
        val dy = (source.y - entityY + 0.45).toFloat()
        val dz = (source.z - entityZ).toFloat()
        val buffer = bufferSource.getBuffer(RenderType.lines())
        val pose = poseStack.last()
        buffer.vertex(pose.pose(), 0.0f, 0.0f, 0.0f)
            .color(0.02f, 0.018f, 0.016f, 0.95f)
            .normal(pose.normal(), 0.0f, 1.0f, 0.0f)
            .endVertex()
        buffer.vertex(pose.pose(), dx, dy, dz)
            .color(0.02f, 0.018f, 0.016f, 0.95f)
            .normal(pose.normal(), 0.0f, 1.0f, 0.0f)
            .endVertex()
    }

    private fun tetherSource(entity: GlassoEntity): Vector3d? {
        val body = entity.level().shipWorld?.allBodies?.getById(entity.ownerBodyId) ?: return null
        return Vector3d(body.kinematics.position)
    }

    companion object {
        private val GLASSO_BOLT_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/entity/glasso_bolt.png")
        private const val BOLT_LENGTH = 0.95f
        private const val BOLT_HALF_WIDTH = 0.045f
    }
}
