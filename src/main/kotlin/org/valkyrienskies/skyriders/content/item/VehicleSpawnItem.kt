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
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraftforge.client.extensions.common.IClientItemExtensions
import net.minecraftforge.client.model.data.ModelData
import org.joml.Vector3d
import org.valkyrienskies.skyriders.client.VehicleOpenModelRenderer
import org.valkyrienskies.skyriders.content.VehicleDefinition
import org.valkyrienskies.skyriders.content.VehicleDefinitions
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleModelPartRenderDefinition
import org.valkyrienskies.skyriders.content.VehicleVisualRotationAxis
import org.valkyrienskies.skyriders.content.VehicleWheelRenderDefinition
import java.util.function.Consumer
import kotlin.math.max

class VehicleSpawnItem(
    properties: Properties,
    val vehicleId: ResourceLocation
) : Item(properties) {
    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        if (level.isClientSide) {
            return InteractionResult.SUCCESS
        }

        val serverLevel = level as? ServerLevel ?: return InteractionResult.FAIL
        val clicked = context.clickedPos
        val spawnPos = clicked.relative(context.clickedFace)
        val position = Vector3d(spawnPos.x + 0.5, spawnPos.y.toDouble(), spawnPos.z + 0.5)
        try {
            VehicleManager.createVehicle(vehicleId, serverLevel, position)
        } catch (_: IllegalArgumentException) {
            return InteractionResult.FAIL
        } catch (_: IllegalStateException) {
            return InteractionResult.FAIL
        }

        if (context.player?.abilities?.instabuild != true) {
            context.itemInHand.shrink(1)
        }
        return InteractionResult.CONSUME
    }

    override fun initializeClient(consumer: Consumer<IClientItemExtensions>) {
        consumer.accept(object : IClientItemExtensions {
            override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer {
                return VehicleSpawnItemRenderer.instance()
            }
        })
    }
}

private class VehicleSpawnItemRenderer(
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
        val item = stack.item as? VehicleSpawnItem ?: return
        val definition = VehicleDefinitions.get(item.vehicleId) ?: return

        poseStack.pushPose()
        applyItemTransform(definition, displayContext, poseStack)
        renderDefinition(definition, poseStack, buffer, packedLight)
        poseStack.popPose()
    }

    private fun applyItemTransform(
        definition: VehicleDefinition,
        displayContext: ItemDisplayContext,
        poseStack: PoseStack
    ) {
        poseStack.translate(0.5, 0.5, 0.5)
        when (displayContext) {
            ItemDisplayContext.GUI -> {
                poseStack.translate(0.0, -0.08, 0.0)
                poseStack.mulPose(Axis.XP.rotationDegrees(-24.0f))
                poseStack.mulPose(Axis.YP.rotationDegrees(38.0f))
            }
            ItemDisplayContext.GROUND -> {
                poseStack.translate(0.0, 0.08, 0.0)
                poseStack.mulPose(Axis.YP.rotationDegrees(25.0f))
            }
            ItemDisplayContext.FIXED -> {
                poseStack.mulPose(Axis.XP.rotationDegrees(-18.0f))
                poseStack.mulPose(Axis.YP.rotationDegrees(35.0f))
            }
            ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
            ItemDisplayContext.FIRST_PERSON_RIGHT_HAND -> {
                poseStack.translate(0.0, -0.05, 0.08)
                poseStack.mulPose(Axis.XP.rotationDegrees(-18.0f))
                poseStack.mulPose(Axis.YP.rotationDegrees(30.0f))
            }
            ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
            ItemDisplayContext.THIRD_PERSON_RIGHT_HAND -> {
                poseStack.translate(0.0, -0.05, 0.04)
                poseStack.mulPose(Axis.XP.rotationDegrees(-12.0f))
                poseStack.mulPose(Axis.YP.rotationDegrees(30.0f))
            }
            else -> {
                poseStack.mulPose(Axis.YP.rotationDegrees(35.0f))
            }
        }

        val body = definition.body
        val maxExtent = max(max(body.collisionBoxSize.x, body.collisionBoxSize.y), body.collisionBoxSize.z)
            .takeIf { it.isFinite() && it > 0.0 }
            ?: 2.0
        val previewScale = (0.92 / maxExtent).toFloat()
        poseStack.scale(previewScale, previewScale, previewScale)
        poseStack.translate(-body.collisionBoxOffset.x, -body.collisionBoxOffset.y, -body.collisionBoxOffset.z)
    }

    private fun renderDefinition(
        definition: VehicleDefinition,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int
    ) {
        val render = definition.render
        poseStack.pushPose()
        if (render.modelYawRad.isFinite() && render.modelYawRad != 0.0) {
            poseStack.mulPose(Axis.YP.rotation(render.modelYawRad.toFloat()))
        }
        poseStack.translate(render.modelOffset.x, render.modelOffset.y, render.modelOffset.z)
        poseStack.scale(render.modelScale.toFloat(), render.modelScale.toFloat(), render.modelScale.toFloat())

        renderModel(render.model, render.renderOpenModelNoCull, poseStack, buffer, packedLight)
        render.modelParts.forEach { part ->
            renderModelPart(part, poseStack, buffer, packedLight)
        }
        render.resolvedWheelParts().forEach { wheel ->
            renderWheel(wheel, poseStack, buffer, packedLight)
        }
        poseStack.popPose()
    }

    private fun renderModelPart(
        part: VehicleModelPartRenderDefinition,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int
    ) {
        poseStack.pushPose()
        if (part.visualOffset.isFinite()) {
            poseStack.translate(part.visualOffset.x, part.visualOffset.y, part.visualOffset.z)
        }
        poseStack.translate(part.pivot.x, part.pivot.y, part.pivot.z)
        if (part.closedRotationDegrees.x.isFinite() && part.closedRotationDegrees.x != 0.0) {
            poseStack.mulPose(Axis.XP.rotationDegrees(part.closedRotationDegrees.x.toFloat()))
        }
        if (part.closedRotationDegrees.y.isFinite() && part.closedRotationDegrees.y != 0.0) {
            poseStack.mulPose(Axis.YP.rotationDegrees(part.closedRotationDegrees.y.toFloat()))
        }
        if (part.closedRotationDegrees.z.isFinite() && part.closedRotationDegrees.z != 0.0) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(part.closedRotationDegrees.z.toFloat()))
        }
        poseStack.translate(-part.pivot.x, -part.pivot.y, -part.pivot.z)
        renderModel(part.model, part.renderOpenModelNoCull, poseStack, buffer, packedLight)
        poseStack.popPose()
    }

    private fun renderWheel(
        wheel: VehicleWheelRenderDefinition,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int
    ) {
        poseStack.pushPose()
        if (wheel.visualOffset.isFinite()) {
            poseStack.translate(wheel.visualOffset.x, wheel.visualOffset.y, wheel.visualOffset.z)
        }
        poseStack.translate(wheel.pivot.x, wheel.pivot.y, wheel.pivot.z)
        applyPreviewSteer(wheel, poseStack)
        poseStack.translate(-wheel.pivot.x, -wheel.pivot.y, -wheel.pivot.z)
        renderModel(wheel.model, noCull = false, poseStack, buffer, packedLight)
        poseStack.popPose()
    }

    private fun applyPreviewSteer(wheel: VehicleWheelRenderDefinition, poseStack: PoseStack) {
        val steerRad = if (wheel.steerSource == org.valkyrienskies.skyriders.content.VehicleWheelSteerSource.FRONT) {
            Math.toRadians(16.0) * wheel.steerVisualScale
        } else {
            0.0
        }
        if (!steerRad.isFinite() || steerRad == 0.0) return
        when (wheel.steerAxis) {
            VehicleVisualRotationAxis.X -> poseStack.mulPose(Axis.XP.rotation(steerRad.toFloat()))
            VehicleVisualRotationAxis.Y -> poseStack.mulPose(Axis.YP.rotation(steerRad.toFloat()))
            VehicleVisualRotationAxis.Z -> poseStack.mulPose(Axis.ZP.rotation(steerRad.toFloat()))
        }
    }

    private fun renderModel(
        modelLocation: ResourceLocation,
        noCull: Boolean,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int
    ) {
        if (VehicleOpenModelRenderer.renderIfNeeded(modelLocation, poseStack, buffer, packedLight, forceRender = true)) {
            return
        }

        val minecraft = Minecraft.getInstance()
        val missingModel = minecraft.modelManager.missingModel
        val model = minecraft.modelManager.getModel(modelLocation).takeUnless { it === missingModel } ?: return
        val renderType = if (noCull) VehicleOpenModelRenderer.BLOCK_ATLAS_NO_CULL_RENDER_TYPE else RenderType.cutout()
        minecraft.blockRenderer.modelRenderer.renderModel(
            poseStack.last(),
            buffer.getBuffer(renderType),
            null,
            model,
            1.0f,
            1.0f,
            1.0f,
            packedLight,
            OverlayTexture.NO_OVERLAY,
            ModelData.EMPTY,
            renderType
        )
    }

    private fun Vector3d.isFinite(): Boolean {
        return x.isFinite() && y.isFinite() && z.isFinite()
    }

    companion object {
        private var renderer: VehicleSpawnItemRenderer? = null

        fun instance(): VehicleSpawnItemRenderer {
            val minecraft = Minecraft.getInstance()
            return renderer ?: VehicleSpawnItemRenderer(
                minecraft.blockEntityRenderDispatcher,
                minecraft.entityModels
            ).also { renderer = it }
        }
    }
}
