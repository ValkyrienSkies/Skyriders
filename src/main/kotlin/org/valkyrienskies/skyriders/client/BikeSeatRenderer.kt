package org.valkyrienskies.skyriders.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.util.Mth
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.model.data.ModelData
import org.joml.Quaternionf
import org.joml.Vector3d
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity

class BikeSeatRenderer(context: EntityRendererProvider.Context) : EntityRenderer<BikeSeatEntity>(context) {
    override fun render(
        entity: BikeSeatEntity,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ) {
        val bike = BikeManager.getBike(entity.level(), entity.bodyId) ?: return
        val transform = BikeClientMountTransforms.getBikeRenderTransform(entity) ?: return
        val model = Minecraft.getInstance().modelManager.getModel(bike.definition.render.model)
        val missingModel = Minecraft.getInstance().modelManager.missingModel
        if (model === missingModel) return

        val bodyPosition = transform.toWorld.transformPosition(Vector3d())
        if (!bodyPosition.isFinite()) return

        val entityPosition = Vec3(
            Mth.lerp(partialTick.toDouble(), entity.xOld, entity.x),
            Mth.lerp(partialTick.toDouble(), entity.yOld, entity.y),
            Mth.lerp(partialTick.toDouble(), entity.zOld, entity.z)
        )
        val render = bike.definition.render

        poseStack.pushPose()
        poseStack.translate(
            bodyPosition.x - entityPosition.x,
            bodyPosition.y - entityPosition.y,
            bodyPosition.z - entityPosition.z
        )
        poseStack.mulPose(Quaternionf(transform.rotation))
        poseStack.translate(render.modelOffset.x, render.modelOffset.y, render.modelOffset.z)
        poseStack.scale(render.modelScale.toFloat(), render.modelScale.toFloat(), render.modelScale.toFloat())

        val vertexConsumer = bufferSource.getBuffer(RenderType.cutout())
        Minecraft.getInstance().blockRenderer.modelRenderer.renderModel(
            poseStack.last(),
            vertexConsumer,
            null,
            model,
            1.0f,
            1.0f,
            1.0f,
            packedLight,
            OverlayTexture.NO_OVERLAY,
            ModelData.EMPTY,
            RenderType.cutout()
        )
        poseStack.popPose()

        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    override fun getTextureLocation(entity: BikeSeatEntity): ResourceLocation {
        return BikeManager.getBike(entity.level(), entity.bodyId)?.definition?.render?.seatTexture ?: TEXTURE
    }

    private fun Vector3d.isFinite(): Boolean {
        return x.isFinite() && y.isFinite() && z.isFinite()
    }

    companion object {
        private val TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/bikes/debug_bike.png")
    }
}
