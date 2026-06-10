package org.valkyrienskies.skyriders.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.client.model.data.ModelData
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.entity.CavendishEntity
import org.valkyrienskies.skyriders.content.entity.SugarRocketEntity
import kotlin.math.atan2

class RacingOpenModelEntityRenderer<T : net.minecraft.world.entity.Entity>(
    context: EntityRendererProvider.Context,
    private val modelLocation: ResourceLocation,
    private val textureLocation: ResourceLocation,
    private val scale: Float = 1.0f,
    private val yOffset: Double = 0.0,
    private val modelSelector: (T) -> ResourceLocation = { modelLocation }
) : EntityRenderer<T>(context) {
    override fun render(
        entity: T,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ) {
        val minecraft = Minecraft.getInstance()
        val light = LevelRenderer.getLightColor(entity.level(), BlockPos.containing(entity.x, entity.y, entity.z))
        val selectedModel = modelSelector(entity)

        poseStack.pushPose()
        poseStack.translate(0.0, yOffset, 0.0)
        applyOrientation(entity, entityYaw, poseStack)
        poseStack.translate(-0.5, 0.0, -0.5)
        poseStack.scale(scale, scale, scale)

        if (!VehicleOpenModelRenderer.renderTexturedIfNeeded(selectedModel, textureLocation, poseStack, bufferSource, light)) {
            val model = minecraft.modelManager.getModel(selectedModel)
            minecraft.blockRenderer.modelRenderer.renderModel(
                poseStack.last(),
                bufferSource.getBuffer(RenderType.cutout()),
                null,
                model,
                1.0f,
                1.0f,
                1.0f,
                light,
                OverlayTexture.NO_OVERLAY,
                ModelData.EMPTY,
                RenderType.cutout()
            )
        }
        poseStack.popPose()
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
    }

    override fun getTextureLocation(entity: T): ResourceLocation = textureLocation

    private fun applyOrientation(entity: T, entityYaw: Float, poseStack: PoseStack) {
        if (entity is SugarRocketEntity) {
            val velocity = entity.deltaMovement
            if (velocity.lengthSqr() > 1.0e-8) {
                val horizontal = kotlin.math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
                poseStack.mulPose(Axis.YP.rotationDegrees(Math.toDegrees(atan2(-velocity.x, -velocity.z)).toFloat()))
                poseStack.mulPose(Axis.XP.rotationDegrees(Math.toDegrees(atan2(velocity.y, horizontal)).toFloat()))
                val rollSpeed = if (entity.hasFuel) 46.0f else 8.0f
                poseStack.mulPose(Axis.ZP.rotationDegrees((entity.tickCount * rollSpeed) % 360.0f))
                return
            }
        }
        poseStack.mulPose(Axis.YP.rotationDegrees(-entityYaw))
    }

    companion object {
        val CAVENDISH_MODEL = ResourceLocation(SkyridersMod.MOD_ID, "entity/cavendish")
        val SUGAR_ROCKET_MODEL = ResourceLocation(SkyridersMod.MOD_ID, "entity/sugar_rocket")
        val HOMING_SUGAR_ROCKET_MODEL = ResourceLocation(SkyridersMod.MOD_ID, "entity/homing_sugar_rocket")
        private val CAVENDISH_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/entity/cavendish.png")
        private val SUGAR_ROCKET_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/entity/sugar_rocket.png")

        fun cavendish(context: EntityRendererProvider.Context): RacingOpenModelEntityRenderer<CavendishEntity> {
            return RacingOpenModelEntityRenderer(
                context = context,
                modelLocation = CAVENDISH_MODEL,
                textureLocation = CAVENDISH_TEXTURE,
                scale = 0.75f,
                yOffset = -0.02
            )
        }

        fun sugarRocket(context: EntityRendererProvider.Context): RacingOpenModelEntityRenderer<SugarRocketEntity> {
            return RacingOpenModelEntityRenderer(
                context = context,
                modelLocation = SUGAR_ROCKET_MODEL,
                textureLocation = SUGAR_ROCKET_TEXTURE,
                scale = 1.0f,
                modelSelector = { entity -> if (entity.homing) HOMING_SUGAR_ROCKET_MODEL else SUGAR_ROCKET_MODEL }
            )
        }
    }
}
