package org.valkyrienskies.skyriders.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.item.RaceFlagItem
import org.valkyrienskies.skyriders.content.racing.RaceMarkerBlockEntity
import org.valkyrienskies.skyriders.content.racing.RaceMarkerTypes

class RaceMarkerBlockEntityRenderer(
    private val context: BlockEntityRendererProvider.Context
) : BlockEntityRenderer<RaceMarkerBlockEntity> {
    override fun shouldRenderOffScreen(blockEntity: RaceMarkerBlockEntity): Boolean = true

    override fun getViewDistance(): Int = TEXT_RENDER_DISTANCE.toInt()

    override fun render(
        blockEntity: RaceMarkerBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        renderFlag(blockEntity, poseStack, bufferSource, packedLight)
        renderCheckpointIndex(blockEntity, poseStack, bufferSource)
    }

    private fun renderFlag(
        blockEntity: RaceMarkerBlockEntity,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ) {
        val color = blockEntity.colorId
        if (color < 0) return

        poseStack.pushPose()
        poseStack.translate(0.5, FLAG_Y_OFFSET, 0.5)
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0f))
        poseStack.scale(0.8f, 0.8f, 0.8f)
        Minecraft.getInstance().itemRenderer.renderStatic(
            flagStack(color),
            ItemDisplayContext.FIXED,
            packedLight,
            OverlayTexture.NO_OVERLAY,
            poseStack,
            bufferSource,
            blockEntity.level,
            0
        )
        poseStack.popPose()
    }

    private fun renderCheckpointIndex(
        blockEntity: RaceMarkerBlockEntity,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource
    ) {
        if (blockEntity.markerType != RaceMarkerTypes.CHECKPOINT) return
        if (!holdingRaceSetupItem()) return

        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val center = blockEntity.blockPos.center
        if (player.distanceToSqr(center) > TEXT_RENDER_DISTANCE * TEXT_RENDER_DISTANCE) return

        val text = Component.literal(blockEntity.checkpointIndex.toString())
        val font = context.font
        val width = font.width(text)

        poseStack.pushPose()
        poseStack.translate(0.5, 1.72, 0.5)
        poseStack.mulPose(minecraft.entityRenderDispatcher.cameraOrientation())
        poseStack.scale(-TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE)
        font.drawInBatch(
            text,
            -width / 2.0f,
            0.0f,
            0xFFFFFFFF.toInt(),
            false,
            poseStack.last().pose(),
            bufferSource,
            Font.DisplayMode.SEE_THROUGH,
            TEXT_BACKGROUND_COLOR,
            LightTexture.FULL_BRIGHT
        )
        poseStack.popPose()
    }

    private fun holdingRaceSetupItem(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        return isRaceSetupItem(player.mainHandItem) || isRaceSetupItem(player.offhandItem)
    }

    private fun isRaceSetupItem(stack: ItemStack): Boolean {
        return stack.item == SkyridersMod.RACE_MARKER_ITEM.get() ||
            stack.item == SkyridersMod.RACE_ENDPOINT_ITEM.get() ||
            stack.item == SkyridersMod.RACE_FLAG.get()
    }

    private fun flagStack(colorRgb: Int): ItemStack {
        val normalizedColor = colorRgb and 0xFFFFFF
        return flagStacksByColorRgb.getOrPut(normalizedColor) {
            ItemStack(SkyridersMod.RACE_FLAG.get()).also { RaceFlagItem.setColor(it, normalizedColor) }
        }
    }

    companion object {
        private const val FLAG_Y_OFFSET = 1.36
        private const val TEXT_RENDER_DISTANCE = 48.0
        private const val TEXT_SCALE = 0.025f
        private const val TEXT_BACKGROUND_COLOR = 0x66000000
        private val flagStacksByColorRgb = HashMap<Int, ItemStack>()
    }
}
