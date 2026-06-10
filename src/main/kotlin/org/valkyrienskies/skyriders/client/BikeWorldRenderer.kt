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
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.model.data.ModelData
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.joml.Quaternionf
import org.joml.Vector3d
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.IBike
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.VehicleInteractionDefinition
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleRaceParticipants
import org.valkyrienskies.skyriders.content.VehicleRefuelSources
import org.valkyrienskies.skyriders.content.VehicleWheelSpinSource
import org.valkyrienskies.skyriders.content.VehicleWheelSteerSource
import org.valkyrienskies.skyriders.content.item.RaceFlagItem
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle
import kotlin.math.exp

object BikeWorldRenderer {
    private val TEMP_FUEL_CAP_MARKER_IDS = setOf(
        "skyriders:dirt_bike",
        "skyriders:speedster",
        "skyriders:atv"
    )
    private val visualStatesByBodyId = HashMap<Long, RenderVisualState>()
    private val flagStacksByColorRgb = HashMap<Int, ItemStack>()
    private val flagAttachments = mapOf(
        "skyriders:dirt_bike" to FlagAttachment(
            localPos = Vector3d(0.18, 0.72, -0.62),
            yawDegrees = 205.0f,
            scale = 0.56f
        ),
        "skyriders:speedster" to FlagAttachment(
            localPos = Vector3d(0.34, 0.78, -0.86),
            yawDegrees = 195.0f,
            scale = 0.68f
        ),
        "skyriders:atv" to FlagAttachment(
            localPos = Vector3d(0.28, 0.72, -0.82),
            yawDegrees = 195.0f,
            scale = 0.62f
        )
    )

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
        bufferSource.endBatch(RenderType.lines())
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
        renderTemporaryFuelCapMarker(vehicle, poseStack, bufferSource)
        renderRaceFlagMarker(vehicle, poseStack, bufferSource, packedLight)
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

    private fun renderRaceFlagMarker(
        vehicle: IVehicle,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ) {
        val color = VehicleRaceParticipants.color(vehicle) ?: return
        val attachment = flagAttachments[vehicle.vehicleDefinition.id.toString()]
            ?: fallbackFlagAttachment(vehicle)
        poseStack.pushPose()
        poseStack.translate(attachment.localPos.x, attachment.localPos.y, attachment.localPos.z)
        poseStack.mulPose(Axis.YP.rotationDegrees(attachment.yawDegrees))
        poseStack.scale(attachment.scale, attachment.scale, attachment.scale)
        Minecraft.getInstance().itemRenderer.renderStatic(
            flagStack(color),
            ItemDisplayContext.FIXED,
            packedLight,
            OverlayTexture.NO_OVERLAY,
            poseStack,
            bufferSource,
            vehicle.level,
            0
        )
        poseStack.popPose()
    }

    private fun fallbackFlagAttachment(vehicle: IVehicle): FlagAttachment {
        val body = vehicle.vehicleDefinition.body
        return FlagAttachment(
            localPos = Vector3d(
                body.collisionBoxOffset.x,
                body.collisionBoxOffset.y + body.collisionBoxSize.y * 0.5 + 0.05,
                body.collisionBoxOffset.z - body.collisionBoxSize.z * 0.35
            )
        )
    }

    private fun flagStack(colorRgb: Int): ItemStack {
        val normalizedColor = colorRgb and 0xFFFFFF
        return flagStacksByColorRgb.getOrPut(normalizedColor) {
            ItemStack(SkyridersMod.RACE_FLAG.get()).also { RaceFlagItem.setColor(it, normalizedColor) }
        }
    }

    private fun renderTemporaryFuelCapMarker(
        vehicle: IVehicle,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource
    ) {
        if (vehicle.vehicleDefinition.id.toString() !in TEMP_FUEL_CAP_MARKER_IDS) return
        val player = Minecraft.getInstance().player ?: return
        val zone = vehicle.vehicleDefinition.interactions.zone(VehicleInteractionDefinition.FUEL_CAP) ?: return
        if (!VehicleRefuelSources.hasActiveRefuelSource(player, vehicle, zone)) return
        if (!zone.center.isFinite() || !zone.size.isFinite()) return
        val halfX = zone.size.x * 0.5
        val halfY = zone.size.y * 0.5
        val halfZ = zone.size.z * 0.5
        val box = AABB(
            zone.center.x - halfX,
            zone.center.y - halfY,
            zone.center.z - halfZ,
            zone.center.x + halfX,
            zone.center.y + halfY,
            zone.center.z + halfZ
        )
        LevelRenderer.renderLineBox(
            poseStack,
            bufferSource.getBuffer(RenderType.lines()),
            box,
            0.8f,
            0.1f,
            0.8f,
            1.0f
        )
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

    private data class FlagAttachment(
        val localPos: Vector3d,
        val yawDegrees: Float = 180.0f,
        val scale: Float = 0.9f
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
