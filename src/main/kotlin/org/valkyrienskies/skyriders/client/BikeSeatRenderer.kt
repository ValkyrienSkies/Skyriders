package org.valkyrienskies.skyriders.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.util.Mth
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.model.data.ModelData
import org.joml.Quaternionf
import org.joml.Vector3d
import com.mojang.math.Axis
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

        renderBakedModel(poseStack, bufferSource, model, packedLight)

        if (render.showWheels) {
            val frontWheelModel = render.frontWheelModel?.let(Minecraft.getInstance().modelManager::getModel)
            val rearWheelModel = render.rearWheelModel?.let(Minecraft.getInstance().modelManager::getModel)
            if (frontWheelModel != null && frontWheelModel !== missingModel) {
                renderWheelPart(
                    poseStack = poseStack,
                    bufferSource = bufferSource,
                    model = frontWheelModel,
                    pivot = render.frontWheelPivot,
                    steerRad = bike.state.visualSteerRad,
                    spinRad = bike.state.frontWheelSpin,
                    packedLight = packedLight
                )
            }
            if (rearWheelModel != null && rearWheelModel !== missingModel) {
                renderWheelPart(
                    poseStack = poseStack,
                    bufferSource = bufferSource,
                    model = rearWheelModel,
                    pivot = render.rearWheelPivot,
                    steerRad = 0.0,
                    spinRad = bike.state.rearWheelSpin,
                    packedLight = packedLight
                )
            }
        }
        poseStack.popPose()

        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    override fun getTextureLocation(entity: BikeSeatEntity): ResourceLocation {
        return BikeManager.getBike(entity.level(), entity.bodyId)?.definition?.render?.seatTexture ?: TEXTURE
    }

    private fun Vector3d.isFinite(): Boolean {
        return x.isFinite() && y.isFinite() && z.isFinite()
    }

    private fun renderWheelPart(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        model: BakedModel,
        pivot: Vector3d,
        steerRad: Double,
        spinRad: Double,
        packedLight: Int
    ) {
        poseStack.pushPose()
        poseStack.translate(pivot.x, pivot.y, pivot.z)
        if (steerRad.isFinite() && steerRad != 0.0) {
            poseStack.mulPose(Axis.YP.rotation(steerRad.toFloat()))
        }
        if (spinRad.isFinite() && spinRad != 0.0) {
            poseStack.mulPose(Axis.XP.rotation((-spinRad).toFloat()))
        }
        poseStack.translate(-pivot.x, -pivot.y, -pivot.z)
        renderBakedModel(poseStack, bufferSource, model, packedLight)
        poseStack.popPose()
    }

    private fun renderBakedModel(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        model: BakedModel,
        packedLight: Int
    ) {
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
    }

    companion object {
        private val TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/bikes/debug_bike.png")
    }
}
