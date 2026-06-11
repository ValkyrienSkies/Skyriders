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
import org.joml.Vector3d
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.entity.CavendishEntity
import org.valkyrienskies.skyriders.content.entity.HoneyHeisterEntity
import org.valkyrienskies.skyriders.content.entity.SugarRocketEntity
import org.valkyrienskies.mod.api.shipWorld
import kotlin.math.atan2

class RacingOpenModelEntityRenderer<T : net.minecraft.world.entity.Entity>(
    context: EntityRendererProvider.Context,
    private val modelLocation: ResourceLocation,
    private val textureLocation: ResourceLocation,
    private val scale: Float = 1.0f,
    private val yOffset: Double = 0.0,
    private val modelSelector: (T) -> ResourceLocation = { modelLocation },
    private val textureSelector: (T) -> ResourceLocation = { textureLocation }
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
        val selectedTexture = textureSelector(entity)
        if (entity is HoneyHeisterEntity) {
            renderHoneyTether(entity, partialTick, poseStack, bufferSource)
        }

        poseStack.pushPose()
        poseStack.translate(0.0, yOffset, 0.0)
        applyOrientation(entity, entityYaw, poseStack)
        poseStack.translate(-0.5, 0.0, -0.5)
        poseStack.scale(scale, scale, scale)

        if (!VehicleOpenModelRenderer.renderTexturedIfNeeded(selectedModel, selectedTexture, poseStack, bufferSource, light)) {
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

    override fun getTextureLocation(entity: T): ResourceLocation = textureSelector(entity)

    private fun applyOrientation(entity: T, entityYaw: Float, poseStack: PoseStack) {
        if (entity is SugarRocketEntity) {
            if (!entity.ignited) {
                poseStack.mulPose(Axis.YP.rotationDegrees(-entity.yRot))
                poseStack.mulPose(Axis.XP.rotationDegrees(entity.xRot))
                return
            }
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
        if (entity is HoneyHeisterEntity) {
            if (!entity.ignited || entity.attached) {
                poseStack.mulPose(Axis.YP.rotationDegrees(-entity.yRot))
                poseStack.mulPose(Axis.XP.rotationDegrees(entity.xRot))
                return
            }
            val velocity = entity.deltaMovement
            if (velocity.lengthSqr() > 1.0e-8) {
                val horizontal = kotlin.math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
                poseStack.mulPose(Axis.YP.rotationDegrees(Math.toDegrees(atan2(-velocity.x, -velocity.z)).toFloat()))
                poseStack.mulPose(Axis.XP.rotationDegrees(Math.toDegrees(atan2(velocity.y, horizontal)).toFloat()))
                val rollSpeed = if (entity.hasFuel) 32.0f else 7.0f
                poseStack.mulPose(Axis.ZP.rotationDegrees((entity.tickCount * rollSpeed) % 360.0f))
                return
            }
        }
        poseStack.mulPose(Axis.YP.rotationDegrees(-entityYaw))
    }

    private fun renderHoneyTether(
        entity: HoneyHeisterEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource
    ) {
        if (!entity.lineConnected) return
        val body = entity.level().shipWorld?.allBodies?.getById(entity.ownerBodyId) ?: return
        val source = Vector3d(body.kinematics.position)
        val entityX = entity.xOld + (entity.x - entity.xOld) * partialTick
        val entityY = entity.yOld + (entity.y - entity.yOld) * partialTick
        val entityZ = entity.zOld + (entity.z - entity.zOld) * partialTick
        val dx = (source.x - entityX).toFloat()
        val dy = (source.y - entityY + 0.45).toFloat()
        val dz = (source.z - entityZ).toFloat()
        val buffer = bufferSource.getBuffer(RenderType.lines())
        val pose = poseStack.last()
        buffer.vertex(pose.pose(), 0.0f, 0.0f, 0.0f)
            .color(1.0f, 0.62f, 0.08f, 0.95f)
            .normal(pose.normal(), 0.0f, 1.0f, 0.0f)
            .endVertex()
        buffer.vertex(pose.pose(), dx, dy, dz)
            .color(1.0f, 0.38f, 0.02f, 0.95f)
            .normal(pose.normal(), 0.0f, 1.0f, 0.0f)
            .endVertex()
    }

    companion object {
        val CAVENDISH_MODEL = ResourceLocation(SkyridersMod.MOD_ID, "entity/cavendish")
        val SUGAR_ROCKET_MODEL = ResourceLocation(SkyridersMod.MOD_ID, "entity/sugar_rocket")
        val HOMING_SUGAR_ROCKET_MODEL = ResourceLocation(SkyridersMod.MOD_ID, "entity/homing_sugar_rocket")
        val HONEY_HEISTER_MODEL = ResourceLocation(SkyridersMod.MOD_ID, "entity/honey_heister")
        private val CAVENDISH_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/entity/cavendish.png")
        private val SUGAR_ROCKET_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/entity/sugar_rocket.png")
        private val HOMING_SUGAR_ROCKET_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/entity/sugar_rocket_homing.png")
        private val HONEY_HEISTER_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/entity/honey_heister.png")

        fun cavendish(context: EntityRendererProvider.Context): RacingOpenModelEntityRenderer<CavendishEntity> {
            return RacingOpenModelEntityRenderer(
                context = context,
                modelLocation = CAVENDISH_MODEL,
                textureLocation = CAVENDISH_TEXTURE,
                scale = 1.25f,
                yOffset = 0.0
            )
        }

        fun sugarRocket(context: EntityRendererProvider.Context): RacingOpenModelEntityRenderer<SugarRocketEntity> {
            return RacingOpenModelEntityRenderer(
                context = context,
                modelLocation = SUGAR_ROCKET_MODEL,
                textureLocation = SUGAR_ROCKET_TEXTURE,
                scale = 1.0f,
                modelSelector = { entity -> if (entity.homing) HOMING_SUGAR_ROCKET_MODEL else SUGAR_ROCKET_MODEL },
                textureSelector = { entity -> if (entity.homing) HOMING_SUGAR_ROCKET_TEXTURE else SUGAR_ROCKET_TEXTURE }
            )
        }

        fun honeyHeister(context: EntityRendererProvider.Context): RacingOpenModelEntityRenderer<HoneyHeisterEntity> {
            return RacingOpenModelEntityRenderer(
                context = context,
                modelLocation = HONEY_HEISTER_MODEL,
                textureLocation = HONEY_HEISTER_TEXTURE,
                scale = 1.0f
            )
        }
    }
}
