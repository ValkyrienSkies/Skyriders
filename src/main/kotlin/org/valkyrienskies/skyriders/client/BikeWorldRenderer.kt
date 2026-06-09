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
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.model.data.ModelData
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.joml.Quaternionf
import org.joml.Vector3d
import org.valkyrienskies.skyriders.content.IBike
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleWheelSpinSource
import org.valkyrienskies.skyriders.content.VehicleWheelSteerSource
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle
import kotlin.math.exp

object BikeWorldRenderer {
    private val visualStatesByBodyId = HashMap<Long, RenderVisualState>()

    @SubscribeEvent
    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return

        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val vehicles = VehicleManager.getVehicles(level)
        if (vehicles.isEmpty()) return
        visualStatesByBodyId.keys.retainAll(vehicles.mapTo(HashSet()) { it.bodyId })

        val bufferSource = minecraft.renderBuffers().bufferSource()
        val cameraPosition = event.camera.position
        vehicles.forEach { vehicle ->
            renderVehicle(
                vehicle = vehicle,
                poseStack = event.poseStack,
                bufferSource = bufferSource,
                cameraX = cameraPosition.x,
                cameraY = cameraPosition.y,
                cameraZ = cameraPosition.z
            )
        }
        bufferSource.endBatch(RenderType.cutout())
    }

    private fun renderVehicle(
        vehicle: IVehicle,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double
    ) {
        val minecraft = Minecraft.getInstance()
        val transform = try {
            vehicle.getRenderTransform()
        } catch (_: IllegalStateException) {
            return
        }
        val carriedPlayer = Minecraft.getInstance().player?.takeIf {
            BikeClientHoistState.hoisting && BikeClientHoistState.bodyId == vehicle.bodyId
        }
        val bodyPosition = carriedPlayer?.let(BikeClientHoistState::carriedPosition)
            ?: transform.toWorld.transformPosition(Vector3d())
        if (!bodyPosition.isFinite()) return

        val render = vehicle.vehicleDefinition.render
        val missingModel = minecraft.modelManager.missingModel
        val model = minecraft.modelManager.getModel(render.model)
            .takeUnless { it === missingModel }
        val visualState = updateRenderVisualState(vehicle)

        val packedLight = LevelRenderer.getLightColor(
            vehicle.level,
            BlockPos.containing(bodyPosition.x, bodyPosition.y, bodyPosition.z)
        )

        poseStack.pushPose()
        poseStack.translate(bodyPosition.x - cameraX, bodyPosition.y - cameraY, bodyPosition.z - cameraZ)
        poseStack.mulPose(Quaternionf(carriedPlayer?.let(BikeClientHoistState::carriedRotation) ?: transform.rotation))
        if (render.modelYawRad.isFinite() && render.modelYawRad != 0.0) {
            poseStack.mulPose(Axis.YP.rotation(render.modelYawRad.toFloat()))
        }
        poseStack.translate(render.modelOffset.x, render.modelOffset.y, render.modelOffset.z)
        poseStack.scale(render.modelScale.toFloat(), render.modelScale.toFloat(), render.modelScale.toFloat())

        if (!VehicleOpenModelRenderer.renderIfNeeded(render.model, poseStack, bufferSource, packedLight)) {
            model?.let { renderBakedModel(poseStack, bufferSource, it, packedLight) } ?: run {
                poseStack.popPose()
                return
            }
        }

        render.resolvedWheelParts().forEach { wheelPart ->
            val wheelModel = minecraft.modelManager.getModel(wheelPart.model)
                .takeUnless { it === missingModel }
            val bike = vehicle as? IBike
            val kart = vehicle as? KartVehicle
            val wheeled = vehicle as? WheeledVehicle
            renderWheelPart(
                poseStack = poseStack,
                bufferSource = bufferSource,
                modelLocation = wheelPart.model,
                model = wheelModel,
                pivot = wheelPart.pivot,
                visualOffset = wheelPart.visualOffset,
                steerRad = when (wheelPart.steerSource) {
                    VehicleWheelSteerSource.FRONT -> bike?.state?.visualSteerRad
                        ?: kart?.kartState?.debugSteerRad
                        ?: wheeled?.wheeledState?.debugSteerRad
                        ?: 0.0
                    VehicleWheelSteerSource.NONE -> 0.0
                },
                spinRad = when (wheelPart.spinSource) {
                    VehicleWheelSpinSource.FRONT -> visualState.frontWheelSpin
                    VehicleWheelSpinSource.REAR -> visualState.rearWheelSpin
                    VehicleWheelSpinSource.NONE -> 0.0
                },
                suspensionOffset = when (wheelPart.spinSource) {
                    VehicleWheelSpinSource.FRONT -> visualState.frontWheelSuspensionOffset
                    VehicleWheelSpinSource.REAR -> visualState.rearWheelSuspensionOffset
                    VehicleWheelSpinSource.NONE -> 0.0
                },
                packedLight = packedLight
            )
        }

        poseStack.popPose()
    }

    private fun updateRenderVisualState(vehicle: IVehicle): RenderVisualState {
        val now = System.nanoTime()
        val wheelSpin = wheelSpin(vehicle)
        val suspensionOffset = suspensionOffset(vehicle)
        val render = vehicle.vehicleDefinition.render
        val scale = render.wheelSpinVisualScale.takeIf { it.isFinite() && it >= 0.0 } ?: 1.0
        val state = visualStatesByBodyId.getOrPut(vehicle.bodyId) {
            RenderVisualState(
                lastRenderNanos = now,
                lastRawFrontWheelSpin = wheelSpin.front,
                lastRawRearWheelSpin = wheelSpin.rear,
                targetFrontWheelSpin = wheelSpin.front * scale,
                targetRearWheelSpin = wheelSpin.rear * scale,
                frontWheelSpin = wheelSpin.front * scale,
                rearWheelSpin = wheelSpin.rear * scale,
                targetFrontWheelSuspensionOffset = suspensionOffset.front,
                targetRearWheelSuspensionOffset = suspensionOffset.rear,
                frontWheelSuspensionOffset = suspensionOffset.front,
                rearWheelSuspensionOffset = suspensionOffset.rear
            )
        }

        val rawFront = wheelSpin.front
        val rawRear = wheelSpin.rear
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
        state.targetFrontWheelSuspensionOffset = finiteOrZero(suspensionOffset.front)
        state.targetRearWheelSuspensionOffset = finiteOrZero(suspensionOffset.rear)

        val dt = ((now - state.lastRenderNanos) / 1.0e9).coerceIn(0.0, 0.1)
        state.lastRenderNanos = now
        val smoothingTime = render.wheelSpinSmoothingTime.takeIf { it.isFinite() && it > 1.0e-4 } ?: 0.08
        val alpha = 1.0 - exp(-dt / smoothingTime)
        state.frontWheelSpin += (state.targetFrontWheelSpin - state.frontWheelSpin) * alpha
        state.rearWheelSpin += (state.targetRearWheelSpin - state.rearWheelSpin) * alpha
        state.frontWheelSuspensionOffset += (state.targetFrontWheelSuspensionOffset - state.frontWheelSuspensionOffset) * alpha
        state.rearWheelSuspensionOffset += (state.targetRearWheelSuspensionOffset - state.rearWheelSuspensionOffset) * alpha
        return state
    }

    private fun wheelSpin(vehicle: IVehicle): WheelPair {
        return when (vehicle) {
            is IBike -> WheelPair(vehicle.state.frontWheelSpin, vehicle.state.rearWheelSpin)
            is KartVehicle -> WheelPair(vehicle.kartState.frontWheelSpin, vehicle.kartState.rearWheelSpin)
            is WheeledVehicle -> WheelPair(vehicle.wheeledState.frontWheelSpin, vehicle.wheeledState.rearWheelSpin)
            else -> WheelPair(0.0, 0.0)
        }
    }

    private fun suspensionOffset(vehicle: IVehicle): WheelPair {
        return when (vehicle) {
            is IBike -> WheelPair(vehicle.state.frontWheelSuspensionOffset, vehicle.state.rearWheelSuspensionOffset)
            is KartVehicle -> WheelPair(vehicle.kartState.frontWheelSuspensionOffset, vehicle.kartState.rearWheelSuspensionOffset)
            is WheeledVehicle -> WheelPair(vehicle.wheeledState.frontWheelSuspensionOffset, vehicle.wheeledState.rearWheelSuspensionOffset)
            else -> WheelPair(0.0, 0.0)
        }
    }

    private fun renderWheelPart(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        modelLocation: ResourceLocation,
        model: BakedModel?,
        pivot: Vector3d,
        visualOffset: Vector3d,
        steerRad: Double,
        spinRad: Double,
        suspensionOffset: Double,
        packedLight: Int
    ) {
        poseStack.pushPose()
        if (suspensionOffset.isFinite() && suspensionOffset != 0.0) {
            poseStack.translate(0.0, suspensionOffset, 0.0)
        }
        if (visualOffset.isFinite()) {
            poseStack.translate(visualOffset.x, visualOffset.y, visualOffset.z)
        }
        poseStack.translate(pivot.x, pivot.y, pivot.z)
        if (steerRad.isFinite() && steerRad != 0.0) {
            poseStack.mulPose(Axis.YP.rotation(steerRad.toFloat()))
        }
        if (spinRad.isFinite() && spinRad != 0.0) {
            poseStack.mulPose(Axis.XP.rotation((-spinRad).toFloat()))
        }
        poseStack.translate(-pivot.x, -pivot.y, -pivot.z)
        if (!VehicleOpenModelRenderer.renderIfNeeded(modelLocation, poseStack, bufferSource, packedLight)) {
            model?.let { renderBakedModel(poseStack, bufferSource, it, packedLight) }
        }
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

    private fun finiteOrZero(value: Double): Double {
        return if (value.isFinite()) value else 0.0
    }

    private data class WheelPair(
        val front: Double,
        val rear: Double
    )

    private data class RenderVisualState(
        var lastRenderNanos: Long,
        var lastRawFrontWheelSpin: Double,
        var lastRawRearWheelSpin: Double,
        var targetFrontWheelSpin: Double,
        var targetRearWheelSpin: Double,
        var frontWheelSpin: Double,
        var rearWheelSpin: Double,
        var targetFrontWheelSuspensionOffset: Double,
        var targetRearWheelSuspensionOffset: Double,
        var frontWheelSuspensionOffset: Double,
        var rearWheelSuspensionOffset: Double
    )
}
