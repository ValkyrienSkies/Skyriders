package org.valkyrienskies.skyriders.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.entity.BadExplosionEntity

class BadExplosionRenderer(context: EntityRendererProvider.Context) : EntityRenderer<BadExplosionEntity>(context) {
    override fun render(
        entity: BadExplosionEntity,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ) {
        val age = (entity.tickCount + partialTick).coerceAtLeast(0.0f)
        val frame = age.toInt().coerceIn(0, BadExplosionEntity.FRAME_COUNT - 1)
        val frameHeight = 1.0f / BadExplosionEntity.FRAME_COUNT.toFloat()
        val minV = frame * frameHeight
        val maxV = minV + frameHeight
        val fade = (1.0f - ((age - 13.0f) / 4.0f).coerceIn(0.0f, 1.0f)).coerceIn(0.0f, 1.0f)
        val alpha = (255.0f * fade).toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        poseStack.pushPose()
        poseStack.mulPose(entityRenderDispatcher.cameraOrientation())
        val half = SIZE * 0.5f
        val pose = poseStack.last()
        val matrix = pose.pose()
        val normal = pose.normal()
        val consumer = bufferSource.getBuffer(RenderType.entityTranslucent(TEXTURE))

        vertex(consumer, matrix, normal, -half, -half, 0.0f, 0.0f, maxV, alpha, packedLight)
        vertex(consumer, matrix, normal, half, -half, 0.0f, 1.0f, maxV, alpha, packedLight)
        vertex(consumer, matrix, normal, half, half, 0.0f, 1.0f, minV, alpha, packedLight)
        vertex(consumer, matrix, normal, -half, half, 0.0f, 0.0f, minV, alpha, packedLight)
        poseStack.popPose()

        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    override fun getTextureLocation(entity: BadExplosionEntity): ResourceLocation = TEXTURE

    private fun vertex(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        matrix: Matrix4f,
        normal: Matrix3f,
        x: Float,
        y: Float,
        z: Float,
        u: Float,
        v: Float,
        alpha: Int,
        packedLight: Int
    ) {
        consumer.vertex(matrix, x, y, z)
            .color(255, 255, 255, alpha)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(packedLight)
            .normal(normal, 0.0f, 1.0f, 0.0f)
            .endVertex()
    }

    companion object {
        private const val SIZE = 4.5f
        private val TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/effects/badexplosion.png")
    }
}
