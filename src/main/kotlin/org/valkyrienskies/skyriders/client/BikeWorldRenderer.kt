package org.valkyrienskies.skyriders.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.BlockPos
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.model.data.ModelData
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.joml.Quaternionf
import org.joml.Vector3d
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.IBike

object BikeWorldRenderer {
    @SubscribeEvent
    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return

        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val bikes = BikeManager.getBikes(level)
        if (bikes.isEmpty()) return

        val bufferSource = minecraft.renderBuffers().bufferSource()
        val cameraPosition = event.camera.position
        bikes.forEach { bike ->
            renderBike(
                bike = bike,
                poseStack = event.poseStack,
                bufferSource = bufferSource,
                cameraX = cameraPosition.x,
                cameraY = cameraPosition.y,
                cameraZ = cameraPosition.z
            )
        }
        bufferSource.endBatch(RenderType.cutout())
    }

    private fun renderBike(
        bike: IBike,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double
    ) {
        val minecraft = Minecraft.getInstance()
        val transform = try {
            bike.getRenderTransform()
        } catch (_: IllegalStateException) {
            return
        }
        val bodyPosition = transform.toWorld.transformPosition(Vector3d())
        if (!bodyPosition.isFinite()) return

        val render = bike.definition.render
        val model = minecraft.modelManager.getModel(render.model)
        val missingModel = minecraft.modelManager.missingModel
        if (model === missingModel) return

        val packedLight = LevelRenderer.getLightColor(
            bike.level,
            BlockPos.containing(bodyPosition.x, bodyPosition.y, bodyPosition.z)
        )

        poseStack.pushPose()
        poseStack.translate(bodyPosition.x - cameraX, bodyPosition.y - cameraY, bodyPosition.z - cameraZ)
        poseStack.mulPose(Quaternionf(transform.rotation))
        if (render.modelYawRad.isFinite() && render.modelYawRad != 0.0) {
            poseStack.mulPose(Axis.YP.rotation(render.modelYawRad.toFloat()))
        }
        poseStack.translate(render.modelOffset.x, render.modelOffset.y, render.modelOffset.z)
        poseStack.scale(render.modelScale.toFloat(), render.modelScale.toFloat(), render.modelScale.toFloat())

        renderBakedModel(poseStack, bufferSource, model, packedLight)

        if (render.showWheels) {
            render.frontWheelModel
                ?.let(minecraft.modelManager::getModel)
                ?.takeUnless { it === missingModel }
                ?.let {
                    renderWheelPart(
                        poseStack = poseStack,
                        bufferSource = bufferSource,
                        model = it,
                        pivot = render.frontWheelPivot,
                        steerRad = bike.state.visualSteerRad,
                        spinRad = bike.state.frontWheelSpin,
                        packedLight = packedLight
                    )
                }

            render.rearWheelModel
                ?.let(minecraft.modelManager::getModel)
                ?.takeUnless { it === missingModel }
                ?.let {
                    renderWheelPart(
                        poseStack = poseStack,
                        bufferSource = bufferSource,
                        model = it,
                        pivot = render.rearWheelPivot,
                        steerRad = 0.0,
                        spinRad = bike.state.rearWheelSpin,
                        packedLight = packedLight
                    )
                }
        }

        poseStack.popPose()
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
        Minecraft.getInstance().blockRenderer.modelRenderer.renderModel(
            poseStack.last(),
            bufferSource.getBuffer(RenderType.cutout()),
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

    private fun Vector3d.isFinite(): Boolean {
        return x.isFinite() && y.isFinite() && z.isFinite()
    }
}
