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
import org.valkyrienskies.skyriders.content.VehicleDamage
import org.valkyrienskies.skyriders.content.VehicleInteractionDefinition
import org.valkyrienskies.skyriders.content.VehicleInteractionActions
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleModelPartRenderDefinition
import org.valkyrienskies.skyriders.content.VehiclePartTypes
import org.valkyrienskies.skyriders.content.VehicleRaceParticipants
import org.valkyrienskies.skyriders.content.VehicleRefuelSources
import org.valkyrienskies.skyriders.content.VehicleVisualRotationAxis
import org.valkyrienskies.skyriders.content.VehicleWheelSpinSource
import org.valkyrienskies.skyriders.content.VehicleWheelSteerSource
import org.valkyrienskies.skyriders.content.item.RaceFlagItem
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle
import kotlin.math.exp
import kotlin.math.pow

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
        bufferSource.endBatch(VehicleOpenModelRenderer.BLOCK_ATLAS_NO_CULL_RENDER_TYPE)
        bufferSource.endBatch(RenderType.cutout())
        bufferSource.endBatch(RenderType.lines())
        VehicleOpenModelRenderer.endDamageCrackBatches(bufferSource)
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
            vehicle.getRenderTransform() ?: return
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

        val bodyRenderType = if (render.renderOpenModelNoCull) {
            VehicleOpenModelRenderer.BLOCK_ATLAS_NO_CULL_RENDER_TYPE
        } else {
            RenderType.cutout()
        }
        if (!VehicleOpenModelRenderer.renderIfNeeded(render.model, poseStack, bufferSource, packedLight)) {
            model?.let { renderBakedModel(poseStack, bufferSource, it, packedLight, bodyRenderType) } ?: run {
                if (!VehicleOpenModelRenderer.renderIfNeeded(render.model, poseStack, bufferSource, packedLight, forceRender = true)) {
                    poseStack.popPose()
                    return
                }
            }
        }
        VehicleOpenModelRenderer.renderDamageCracksIfNeeded(
            modelLocation = render.model,
            poseStack = poseStack,
            bufferSource = bufferSource,
            packedLight = packedLight,
            zones = damageCrackZones(vehicle, includeWheelParts = false),
            modelOffset = render.modelOffset,
            modelScale = render.modelScale,
            modelYawRad = render.modelYawRad
        )

        render.modelParts.forEach { modelPart ->
            val partModel = minecraft.modelManager.getModel(modelPart.model)
                .takeUnless { it === missingModel }
            renderModelPart(
                vehicle = vehicle,
                poseStack = poseStack,
                bufferSource = bufferSource,
                modelPart = modelPart,
                model = partModel,
                visualState = visualState,
                packedLight = packedLight
            )
        }

        render.resolvedWheelParts().forEach { wheelPart ->
            val damagePartId = wheelDamagePartId(wheelPart.id)
            if (damagePartId != null && VehicleDamage.isPartDestroyed(vehicle, damagePartId)) {
                return@forEach
            }
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
                } * wheelPart.steerVisualScale,
                steerAxis = wheelPart.steerAxis,
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
                packedLight = packedLight,
                damageFraction = damagePartId?.let { VehicleDamage.damageFraction(vehicle, it) } ?: 0.0
            )
        }

        poseStack.popPose()
    }

    private fun renderModelPart(
        vehicle: IVehicle,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        modelPart: VehicleModelPartRenderDefinition,
        model: BakedModel?,
        visualState: RenderVisualState,
        packedLight: Int
    ) {
        val openAmount = modelPartOpenAmount(vehicle, modelPart, visualState)
        val rotation = Vector3d(modelPart.closedRotationDegrees).lerp(modelPart.openRotationDegrees, openAmount)

        poseStack.pushPose()
        if (modelPart.visualOffset.isFinite()) {
            poseStack.translate(modelPart.visualOffset.x, modelPart.visualOffset.y, modelPart.visualOffset.z)
        }
        poseStack.translate(modelPart.pivot.x, modelPart.pivot.y, modelPart.pivot.z)
        if (rotation.x.isFinite() && rotation.x != 0.0) {
            poseStack.mulPose(Axis.XP.rotationDegrees(rotation.x.toFloat()))
        }
        if (rotation.y.isFinite() && rotation.y != 0.0) {
            poseStack.mulPose(Axis.YP.rotationDegrees(rotation.y.toFloat()))
        }
        if (rotation.z.isFinite() && rotation.z != 0.0) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(rotation.z.toFloat()))
        }
        poseStack.translate(-modelPart.pivot.x, -modelPart.pivot.y, -modelPart.pivot.z)
        val partRenderType = if (modelPart.renderOpenModelNoCull) {
            VehicleOpenModelRenderer.BLOCK_ATLAS_NO_CULL_RENDER_TYPE
        } else {
            RenderType.cutout()
        }
        if (!VehicleOpenModelRenderer.renderIfNeeded(modelPart.model, poseStack, bufferSource, packedLight)) {
            model?.let { renderBakedModel(poseStack, bufferSource, it, packedLight, partRenderType) } ?: run {
                VehicleOpenModelRenderer.renderIfNeeded(
                    modelPart.model,
                    poseStack,
                    bufferSource,
                    packedLight,
                    forceRender = true
                )
            }
        }
        poseStack.popPose()
    }

    private fun modelPartOpenAmount(
        vehicle: IVehicle,
        modelPart: VehicleModelPartRenderDefinition,
        visualState: RenderVisualState
    ): Double {
        val target = modelPart.partStateId
            ?.let { vehicle.vehicleState.partStates[it]?.data?.getBoolean("open") }
            ?.let { if (it) 1.0 else 0.0 }
            ?: 0.0
        val key = modelPart.partStateId ?: modelPart.id
        val animation = visualState.modelPartOpenAmounts.getOrPut(key) {
            ModelPartOpenAnimation(current = target, start = target, target = target, elapsedSeconds = MODEL_PART_OPEN_DURATION_SECONDS)
        }
        if (animation.target != target) {
            animation.start = animation.current
            animation.target = target
            animation.elapsedSeconds = 0.0
        }

        animation.elapsedSeconds = (animation.elapsedSeconds + visualState.lastDeltaSeconds)
            .coerceAtMost(MODEL_PART_OPEN_DURATION_SECONDS)
        val progress = (animation.elapsedSeconds / MODEL_PART_OPEN_DURATION_SECONDS).coerceIn(0.0, 1.0)
        val eased = easeOutBack(progress).coerceIn(0.0, 1.0)
        animation.current = animation.start + (animation.target - animation.start) * eased
        if (progress >= 1.0 || kotlin.math.abs(animation.current - animation.target) < 0.001) {
            animation.current = animation.target
        }
        return animation.current
    }

    private fun easeOutBack(t: Double): Double {
        val c1 = MODEL_PART_BACK_OVERSHOOT
        val c3 = c1 + 1.0
        val x = t - 1.0
        return 1.0 + c3 * x.pow(3.0) + c1 * x.pow(2.0)
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
        poseStack.mulPose(Axis.YP.rotationDegrees(attachment.yawDegrees + FLAG_MODEL_YAW_CORRECTION_DEGREES))
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
                rearWheelSuspensionOffset = suspensionOffset.rear,
                lastDeltaSeconds = 0.0
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
        state.lastDeltaSeconds = dt
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

    private fun wheelDamagePartId(renderPartId: String): String? {
        return when (renderPartId) {
            "front" -> VehicleDamage.FRONT_WHEEL_PART_ID
            "rear" -> VehicleDamage.REAR_WHEEL_PART_ID
            VehicleDamage.FRONT_WHEEL_PART_ID,
            VehicleDamage.REAR_WHEEL_PART_ID,
            "front_left_wheel",
            "front_right_wheel",
            "rear_left_wheel",
            "rear_right_wheel" -> renderPartId
            else -> null
        }
    }

    private fun damageCrackZones(
        vehicle: IVehicle,
        includeWheelParts: Boolean
    ): List<VehicleOpenModelRenderer.DamageCrackZone> {
        return vehicle.vehicleDefinition.interactions.zones.mapNotNull { zone ->
            val partId = zone.partId ?: return@mapNotNull null
            if (VehicleInteractionActions.REPAIR !in zone.actions) return@mapNotNull null
            if (!zone.center.isFinite() || !zone.size.isFinite()) return@mapNotNull null
            val part = vehicle.vehicleDefinition.parts.firstOrNull { it.id == partId } ?: return@mapNotNull null
            if (!includeWheelParts && part.type == VehiclePartTypes.WHEEL) return@mapNotNull null
            val damage = VehicleDamage.damageFraction(vehicle, partId)
            if (damage < VehicleOpenModelRenderer.MIN_CRACK_DAMAGE_FRACTION) return@mapNotNull null
            VehicleOpenModelRenderer.DamageCrackZone(
                center = Vector3d(zone.center),
                size = Vector3d(zone.size),
                damageFraction = damage
            )
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
        steerAxis: VehicleVisualRotationAxis,
        spinRad: Double,
        suspensionOffset: Double,
        packedLight: Int,
        damageFraction: Double
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
            applyRotation(poseStack, steerAxis, steerRad)
        }
        if (spinRad.isFinite() && spinRad != 0.0) {
            poseStack.mulPose(Axis.XP.rotation((-spinRad).toFloat()))
        }
        poseStack.translate(-pivot.x, -pivot.y, -pivot.z)
        if (!VehicleOpenModelRenderer.renderIfNeeded(modelLocation, poseStack, bufferSource, packedLight)) {
            model?.let { renderBakedModel(poseStack, bufferSource, it, packedLight) }
        }
        VehicleOpenModelRenderer.renderWholeModelDamageCracksIfNeeded(
            modelLocation = modelLocation,
            poseStack = poseStack,
            bufferSource = bufferSource,
            packedLight = packedLight,
            damageFraction = damageFraction
        )
        poseStack.popPose()
    }

    private fun applyRotation(poseStack: PoseStack, axis: VehicleVisualRotationAxis, radians: Double) {
        when (axis) {
            VehicleVisualRotationAxis.X -> poseStack.mulPose(Axis.XP.rotation(radians.toFloat()))
            VehicleVisualRotationAxis.Y -> poseStack.mulPose(Axis.YP.rotation(radians.toFloat()))
            VehicleVisualRotationAxis.Z -> poseStack.mulPose(Axis.ZP.rotation(radians.toFloat()))
        }
    }

    private fun renderBakedModel(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        model: BakedModel,
        packedLight: Int,
        renderType: RenderType = RenderType.cutout()
    ) {
        Minecraft.getInstance().blockRenderer.modelRenderer.renderModel(
            poseStack.last(),
            bufferSource.getBuffer(renderType),
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

    private const val FLAG_MODEL_YAW_CORRECTION_DEGREES = 180.0f
    private const val MODEL_PART_OPEN_DURATION_SECONDS = 0.12
    private const val MODEL_PART_BACK_OVERSHOOT = 1.65

    private data class ModelPartOpenAnimation(
        var current: Double,
        var start: Double,
        var target: Double,
        var elapsedSeconds: Double
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
        var rearWheelSuspensionOffset: Double,
        var lastDeltaSeconds: Double,
        val modelPartOpenAmounts: MutableMap<String, ModelPartOpenAnimation> = HashMap()
    )
}
