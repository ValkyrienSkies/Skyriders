package org.valkyrienskies.skyriders.content.item

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.EntityModelSet
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraftforge.client.extensions.common.IClientItemExtensions
import net.minecraftforge.client.model.data.ModelData
import org.valkyrienskies.skyriders.client.VehicleOpenModelRenderer
import java.util.function.Consumer

class OpenModelComponentItem(
    properties: Properties,
    val model: ResourceLocation,
    val previewScale: Float = 0.9f,
    val noCull: Boolean = false
) : Item(properties) {
    override fun initializeClient(consumer: Consumer<IClientItemExtensions>) {
        consumer.accept(object : IClientItemExtensions {
            override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer {
                return OpenModelComponentItemRenderer.instance()
            }
        })
    }
}

private class OpenModelComponentItemRenderer(
    dispatcher: BlockEntityRenderDispatcher,
    modelSet: EntityModelSet
) : BlockEntityWithoutLevelRenderer(dispatcher, modelSet) {
    override fun renderByItem(
        stack: ItemStack,
        displayContext: ItemDisplayContext,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val item = stack.item as? OpenModelComponentItem ?: return
        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        poseStack.mulPose(Axis.XP.rotationDegrees(-25.0f))
        poseStack.mulPose(Axis.YP.rotationDegrees(35.0f))
        poseStack.scale(item.previewScale, item.previewScale, item.previewScale)
        poseStack.translate(-0.5, -0.5, -0.5)
        renderModel(item, poseStack, buffer, packedLight)
        poseStack.popPose()
    }

    private fun renderModel(
        item: OpenModelComponentItem,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int
    ) {
        if (VehicleOpenModelRenderer.renderIfNeeded(item.model, poseStack, buffer, packedLight, forceRender = true)) {
            return
        }

        val minecraft = Minecraft.getInstance()
        val missingModel = minecraft.modelManager.missingModel
        val bakedModel = minecraft.modelManager.getModel(item.model).takeUnless { it === missingModel } ?: return
        val renderType = if (item.noCull) VehicleOpenModelRenderer.BLOCK_ATLAS_NO_CULL_RENDER_TYPE else RenderType.cutout()
        minecraft.blockRenderer.modelRenderer.renderModel(
            poseStack.last(),
            buffer.getBuffer(renderType),
            null,
            bakedModel,
            1.0f,
            1.0f,
            1.0f,
            packedLight,
            OverlayTexture.NO_OVERLAY,
            ModelData.EMPTY,
            renderType
        )
    }

    companion object {
        private var renderer: OpenModelComponentItemRenderer? = null

        fun instance(): OpenModelComponentItemRenderer {
            val minecraft = Minecraft.getInstance()
            return renderer ?: OpenModelComponentItemRenderer(
                minecraft.blockEntityRenderDispatcher,
                minecraft.entityModels
            ).also { renderer = it }
        }
    }
}
