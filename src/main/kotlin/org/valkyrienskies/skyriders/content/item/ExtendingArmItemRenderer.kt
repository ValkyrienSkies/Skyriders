package org.valkyrienskies.skyriders.content.item

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.EntityModelSet
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.client.ExtendingArmRenderer
import org.valkyrienskies.skyriders.client.VehicleOpenModelRenderer
import org.valkyrienskies.skyriders.content.entity.ExtendingArmEntity

class ExtendingArmItemRenderer(
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
        val item = stack.item as? ExtendingArmItem ?: return
        val model = if (item.armKind == ExtendingArmEntity.GRABBY_HAND) {
            ExtendingArmRenderer.GRABBY_HEAD_MODEL
        } else {
            ExtendingArmRenderer.BOXING_HEAD_MODEL
        }
        val texture = if (item.armKind == ExtendingArmEntity.GRABBY_HAND) {
            GRABBY_HEAD_TEXTURE
        } else {
            BOXING_HEAD_TEXTURE
        }

        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        poseStack.mulPose(Axis.XP.rotationDegrees(-28.0f))
        poseStack.mulPose(Axis.YP.rotationDegrees(38.0f))
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f))
        poseStack.scale(PREVIEW_SCALE, PREVIEW_SCALE, PREVIEW_SCALE)
        poseStack.translate(-0.5, -0.5, -0.5)
        VehicleOpenModelRenderer.renderTexturedIfNeeded(model, texture, poseStack, buffer, packedLight)
        poseStack.popPose()
    }

    companion object {
        private val BOXING_HEAD_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/entity/boxing_glove.png")
        private val GRABBY_HEAD_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/entity/grabby_hand.png")
        private const val PREVIEW_SCALE = 0.86f
        private var renderer: ExtendingArmItemRenderer? = null

        fun instance(): ExtendingArmItemRenderer {
            val minecraft = Minecraft.getInstance()
            return renderer ?: ExtendingArmItemRenderer(
                minecraft.blockEntityRenderDispatcher,
                minecraft.entityModels
            ).also { renderer = it }
        }
    }
}
