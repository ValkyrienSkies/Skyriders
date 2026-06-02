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
import kotlin.math.exp

object BikeWorldRenderer {
    private val visualStatesByBodyId = HashMap<Long, RenderVisualState>()

    @SubscribeEvent
    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return

        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val bikes = BikeManager.getBikes(level)
        if (bikes.isEmpty()) return
        visualStatesByBodyId.keys.retainAll(bikes.mapTo(HashSet()) { it.bodyId })

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
        val visualState = updateRenderVisualState(bike)

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
                        spinRad = visualState.frontWheelSpin,
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
                        spinRad = visualState.rearWheelSpin,
                        packedLight = packedLight
                    )
                }
        }

        poseStack.popPose()
    }

    private fun updateRenderVisualState(bike: IBike): RenderVisualState {
        val now = System.nanoTime()
        val state = visualStatesByBodyId.getOrPut(bike.bodyId) {
            RenderVisualState(
                lastRenderNanos = now,
                lastRawFrontWheelSpin = bike.state.frontWheelSpin,
                lastRawRearWheelSpin = bike.state.rearWheelSpin,
                targetFrontWheelSpin = bike.state.frontWheelSpin * bike.definition.render.wheelSpinVisualScale,
                targetRearWheelSpin = bike.state.rearWheelSpin * bike.definition.render.wheelSpinVisualScale,
                frontWheelSpin = bike.state.frontWheelSpin * bike.definition.render.wheelSpinVisualScale,
                rearWheelSpin = bike.state.rearWheelSpin * bike.definition.render.wheelSpinVisualScale
            )
        }

        val render = bike.definition.render
        val scale = render.wheelSpinVisualScale.takeIf { it.isFinite() && it >= 0.0 } ?: 1.0
        val rawFront = bike.state.frontWheelSpin
        val rawRear = bike.state.rearWheelSpin
        if (rawFront.isFinite()) {
            val delta = rawFront - state.lastRawFrontWheelSpin
            if (delta.isFinite()) {
                state.targetFrontWheelSpin += delta * scale
            }
            state.lastRawFrontWheelSpin = rawFront
        }
        if (rawRear.isFinite()) {
            val delta = rawRear - state.lastRawRearWheelSpin
            if (delta.isFinite()) {
                state.targetRearWheelSpin += delta * scale
            }
            state.lastRawRearWheelSpin = rawRear
        }

        val dt = ((now - state.lastRenderNanos) / 1.0e9).coerceIn(0.0, 0.1)
        state.lastRenderNanos = now
        val smoothingTime = render.wheelSpinSmoothingTime.takeIf { it.isFinite() && it > 1.0e-4 } ?: 0.08
        val alpha = 1.0 - exp(-dt / smoothingTime)
        state.frontWheelSpin += (state.targetFrontWheelSpin - state.frontWheelSpin) * alpha
        state.rearWheelSpin += (state.targetRearWheelSpin - state.rearWheelSpin) * alpha
        return state
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

    private data class RenderVisualState(
        var lastRenderNanos: Long,
        var lastRawFrontWheelSpin: Double,
        var lastRawRearWheelSpin: Double,
        var targetFrontWheelSpin: Double,
        var targetRearWheelSpin: Double,
        var frontWheelSpin: Double,
        var rearWheelSpin: Double
    )
}
