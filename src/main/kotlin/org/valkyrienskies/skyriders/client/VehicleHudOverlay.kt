package org.valkyrienskies.skyriders.client

import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import org.joml.Vector3d
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.SkyridersSounds
import org.valkyrienskies.skyriders.content.VehicleDamage
import org.valkyrienskies.skyriders.content.VehicleInteractionActions
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehiclePartTypes
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

object VehicleHudOverlay {
    private const val STALE_AFTER_MILLIS = 750L
    private const val DASHBOARD_TEXTURE_WIDTH = 256
    private const val DASHBOARD_TEXTURE_HEIGHT = 256
    private const val METER_TEXTURE_WIDTH = 128
    private const val METER_TEXTURE_HEIGHT = 128
    private const val MINI_CRT_TEXTURE_WIDTH = 128
    private const val MINI_CRT_TEXTURE_HEIGHT = 128
    private const val MINI_CRT_WIDGET_SIZE = 96
    private const val MINI_CRT_SCREEN_X = 13
    private const val MINI_CRT_SCREEN_Y = 14
    private const val MINI_CRT_SCREEN_RIGHT = 85
    private const val MINI_CRT_SCREEN_BOTTOM = 84
    private const val MINI_CRT_SCREEN_PADDING = 4
    private const val MINI_CRT_DASHBOARD_OVERLAP = 20
    private const val DAMAGE_PART_MIN_PIXELS = 3
    private const val CRITICAL_WARNING_COLOR = 0xB8B54CFF.toInt()
    private const val CRITICAL_TREMBLE_PIXELS = 3
    private const val CRITICAL_WARNING_FLASH_MILLIS = 85L

    private val DASHBOARD_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/gui/dashboard.png")
    private val METER_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/gui/meter_round.png")
    private val MINI_CRT_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/gui/mini_crt.png")

    private val DASHBOARD_BASE = HudTextureRegion(0, 0, 176, 64)
    private val MINI_CRT_BASE = HudTextureRegion(0, 0, 96, 96)
    private val DASHBOARD_STEERING_WHEEL = HudTextureRegion(0, 64, 80, 80)
    private val DASHBOARD_LIGHT = HudTextureRegion(176, 32, 5, 5)
    private val DASHBOARD_SMALL_LIGHT = HudTextureRegion(176, 38, 4, 4)
    private val DASHBOARD_WARN_LIGHT = HudTextureRegion(182, 32, 5, 5)
    private val DASHBOARD_KEY_OFF = HudTextureRegion(176, 0, 9, 16)
    private val DASHBOARD_KEY_ON = HudTextureRegion(176, 16, 9, 16)

    private val METER_BASE = HudTextureRegion(0, 0, 96, 96)
    private val METER_COUNTER_BACKGROUND = HudTextureRegion(96, 0, 23, 15)
    private val METER_DIAL = HudTextureRegion(108, 21, 4, 35)
    private val METER_DIAL_PIVOT = HudTextureRegion(107, 16, 8, 4)
    private const val STEERING_VISUAL_MAX_DEGREES = 85.0
    private const val STEERING_VISUAL_STIFFNESS = 95.0
    private const val STEERING_VISUAL_DAMPING = 17.0
    private const val METER_WIDGET_PIVOT_X = 48.0
    private const val METER_WIDGET_PIVOT_Y = 48.0
    private const val METER_SMOOTH_THRESHOLD = 0.25
    private const val METER_SMOOTH_RESPONSE = 22.0
    private const val FUEL_SLOSH_STIFFNESS = 46.0
    private const val FUEL_SLOSH_DAMPING = 11.5
    private const val FUEL_SLOSH_MAX_TILT_PIXELS = 45.0

    /*
     * These atlas constants are intentionally easy to retarget after the actual
     * character sheet layout is finalized in the textures.
     */
    private val DASHBOARD_ATLAS = HudCharacterAtlas.fromRows(
        texture = DASHBOARD_TEXTURE,
        textureWidth = DASHBOARD_TEXTURE_WIDTH,
        textureHeight = DASHBOARD_TEXTURE_HEIGHT,
        originX = 192,
        originY = 0,
        charWidth = 5,
        charHeight = 9,
        columnSpacing = 1,
        rowSpacing = 1,
        rows = listOf(
            "08u",
            "19L",
            "2rn",
            "3p",
            "4u",
            "5e",
            "6d",
            "7f"
        )
    )
    private val METER_ATLAS = HudCharacterAtlas.fromRows(
        texture = METER_TEXTURE,
        textureWidth = METER_TEXTURE_WIDTH,
        textureHeight = METER_TEXTURE_HEIGHT,
        originX = 96,
        originY = 16,
        charWidth = 5,
        charHeight = 9,
        columnSpacing = 1,
        rowSpacing = 1,
        rows = listOf(
            "08",
            "19",
            "2r",
            "3p",
            "4u",
            "5e",
            "6d",
            "7"
        )
    )

    private val dashboardLights = listOf(
        BooleanTextureWidget(12, 36, null, DASHBOARD_LIGHT) { it.engineOn },
        BooleanTextureWidget(18, 37, null, DASHBOARD_SMALL_LIGHT) { it.engineStalled },
        BooleanTextureWidget(12, 50, null, DASHBOARD_WARN_LIGHT) { it.parkingBrakeEngaged },
        BooleanTextureWidget(154, 51, null, DASHBOARD_WARN_LIGHT) { it.fuel <= 0.25 }
    )
    private val dashboardKeyWidget = ThreeStateTextureWidget(
        x = 11,
        y = 21,
        offRegion = DASHBOARD_KEY_OFF,
        onRegion = DASHBOARD_KEY_ON
    ) { VehicleHudKeyState.HIDDEN }
    //private val dashboardSpeedCounter = HudAtlasCounterWidget(92, 45, maxLength = 3, atlas = DASHBOARD_ATLAS)
    private val dashboardGearCounter = HudAtlasCounterWidget(29, 38, maxLength = 1, atlas = DASHBOARD_ATLAS)
    private val dashboardFuelCounter = HudAtlasCounterWidget(141, 38, maxLength = 3, atlas = DASHBOARD_ATLAS)

    private val meters = listOf(
        RoundMeterWidget(
            title = "rev",
            anchorOffsetX = -108,
            value = { snapshot -> snapshot.engineRpm.coerceIn(0.0, 7000.0) / 7000.0 },
            counter = { snapshot -> (snapshot.engineRpm / 1000.0).roundToInt().coerceIn(0, 999).toString() },
            minDegrees = -145.0f,
            maxDegrees = 148.0f
        ),
        RoundMeterWidget(
            title = "5u5",
            anchorOffsetX = -108,
            value = { snapshot -> snapshot.jumpCharge.coerceIn(0.0, 1.0) },
            counter = { snapshot -> (snapshot.jumpCharge * 100.0).roundToInt().coerceIn(0, 999).toString() },
            minDegrees = -145.0f,
            maxDegrees = 148.0f
        ),
        RoundMeterWidget(
            title = "5pd",
            anchorOffsetX = -108,
            value = { snapshot -> snapshot.speed.coerceIn(0.0, snapshot.maxSpeed) / snapshot.maxSpeed },
            counter = { snapshot -> snapshot.speed.roundToInt().coerceIn(0, 999).toString() },
            minDegrees = -145.0f,
            maxDegrees = 145.0f
        ),
    )

    private var snapshot: VehicleHudSnapshot? = null
    private var lastRenderMillis = 0L
    private var visualSteeringDegrees = 0.0
    private var visualSteeringVelocityDegrees = 0.0
    private var visualFuelTiltPixels = 0.0
    private var visualFuelTiltVelocity = 0.0
    private var previousFuelForwardSpeed: Double? = null
    private var previousCriticalBodyId: Long? = null
    private var previousCriticalFailure = false
    private var lastWarningBeepCycle = Long.MIN_VALUE
    private val visualMeterValues = HashMap<String, Double>()

    fun updateVehicle(packet: SkyridersNetwork.VehicleTelemetryPacket) {
        snapshot = VehicleHudSnapshot(
            bodyId = packet.bodyId,
            maxSpeed = packet.maxSpeed,
            speed = packet.speed,
            forwardSpeed = packet.forwardSpeed,
            steer = packet.steer,
            engineOn = packet.engineOn,
            transmissionGear = packet.transmissionGear,
            parkingBrakeEngaged = packet.parkingBrakeEngaged || packet.drifting,
            engineRpm = packet.engineRpm,
            engineStalled = packet.engineStalled,
            fuel = packet.fuel.coerceIn(0.0, 1.0),
            hasRevs = packet.hasTransmission,
            hasJump = packet.hasJump,
            jumpCharge = packet.jumpCharge,
            receivedAtMillis = System.currentTimeMillis()
        )
    }

    fun render(guiGraphics: GuiGraphics, screenWidth: Int, screenHeight: Int) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val seat = player.vehicle as? BikeSeatEntity ?: run {
            snapshot = null
            resetVisualState()
            return
        }
        val current = snapshot ?: return
        if (current.bodyId != seat.bodyId) return
//        if (System.currentTimeMillis() - current.receivedAtMillis > STALE_AFTER_MILLIS) {
//            resetVisualState()
//            return
//        }
        val dt = consumeRenderDt()
        val fuelSlosh = updateFuelTankSlosh(current, dt)
        val level = minecraft.level
        val vehicle = level?.let { VehicleManager.getVehicle(it, current.bodyId) }
        val criticalFailure = vehicle?.let(VehicleDamage::isCriticalFailure) == true
        val renderMillis = System.currentTimeMillis()
        updateCriticalHudSounds(minecraft, current.bodyId, criticalFailure, renderMillis)

        renderDamageCrt(guiGraphics, screenHeight, current, vehicle, criticalFailure, renderMillis)
        renderDashboard(guiGraphics, screenWidth, screenHeight, current, dt, fuelSlosh, criticalFailure)
        renderMeters(guiGraphics, screenWidth, screenHeight, current, dt, criticalFailure)
        renderRacePlacement(guiGraphics, screenWidth, current)
    }

    private fun renderDashboard(
        guiGraphics: GuiGraphics,
        screenWidth: Int,
        screenHeight: Int,
        snapshot: VehicleHudSnapshot,
        dt: Double,
        fuelSlosh: FuelSloshState,
        criticalFailure: Boolean
    ) {
        val shake = criticalTremble(criticalFailure, 0.15, horizontal = EdgeShake.OUT_NEGATIVE, vertical = EdgeShake.OUT_POSITIVE)
        val x = shake.x
        val y = screenHeight - DASHBOARD_BASE.height + shake.y
        val steeringDegrees = updateVisualSteeringDegrees(snapshot.steer, dt)
        drawFuelTank(guiGraphics, x + 117, y + 8, 44, 22, snapshot.fuel, fuelSlosh)
        guiGraphics.blitRegion(DASHBOARD_TEXTURE, DASHBOARD_BASE, x, y, DASHBOARD_TEXTURE_WIDTH, DASHBOARD_TEXTURE_HEIGHT)
        drawRotatedRegion(
            guiGraphics = guiGraphics,
            texture = DASHBOARD_TEXTURE,
            textureWidth = DASHBOARD_TEXTURE_WIDTH,
            textureHeight = DASHBOARD_TEXTURE_HEIGHT,
            region = DASHBOARD_STEERING_WHEEL,
            x = x + 40,
            y = y,
            pivotX = x + 80.0,
            pivotY = y + 40.0,
            degrees = steeringDegrees.toFloat()
        )
        dashboardLights.forEach { it.render(guiGraphics, DASHBOARD_TEXTURE, DASHBOARD_TEXTURE_WIDTH, DASHBOARD_TEXTURE_HEIGHT, x, y, snapshot) }
        dashboardKeyWidget.render(guiGraphics, DASHBOARD_TEXTURE, DASHBOARD_TEXTURE_WIDTH, DASHBOARD_TEXTURE_HEIGHT, x, y, snapshot)
        //dashboardSpeedCounter.render(guiGraphics, x, y, snapshot.speed.roundToInt().coerceIn(0, 999).toString())
        dashboardGearCounter.render(guiGraphics, x, y, formatGear(snapshot.transmissionGear))
        dashboardFuelCounter.render(guiGraphics, x, y, (snapshot.fuel * 100.0).roundToInt().coerceIn(0, 999).toString())
    }

    private fun renderDamageCrt(
        guiGraphics: GuiGraphics,
        screenHeight: Int,
        snapshot: VehicleHudSnapshot,
        vehicle: IVehicle?,
        criticalFailure: Boolean,
        renderMillis: Long
    ) {
        val shake = criticalTremble(criticalFailure, 2.2, horizontal = EdgeShake.OUT_NEGATIVE, vertical = EdgeShake.FREE)
        val x = shake.x
        val y = screenHeight - DASHBOARD_BASE.height - MINI_CRT_WIDGET_SIZE + MINI_CRT_DASHBOARD_OVERLAP + shake.y
        guiGraphics.blitRegion(MINI_CRT_TEXTURE, MINI_CRT_BASE, x, y, MINI_CRT_TEXTURE_WIDTH, MINI_CRT_TEXTURE_HEIGHT)

        val screenX = x + MINI_CRT_SCREEN_X
        val screenY = y + MINI_CRT_SCREEN_Y
        val screenWidth = MINI_CRT_SCREEN_RIGHT - MINI_CRT_SCREEN_X + 1
        val screenContentHeight = MINI_CRT_SCREEN_BOTTOM - MINI_CRT_SCREEN_Y + 1

        if (vehicle == null) return
        val damageParts = damageHudParts(vehicle)
        if (damageParts.isEmpty()) return

        guiGraphics.enableScissor(screenX, screenY, screenX + screenWidth, screenY + screenContentHeight)
        drawDamageHudParts(guiGraphics, screenX, screenY, screenWidth, screenContentHeight, damageParts)
        if (criticalFailure) {
            drawCriticalCrtWarning(guiGraphics, screenX, screenY, screenWidth, screenContentHeight, renderMillis)
        }
        guiGraphics.disableScissor()
    }

    private fun drawCriticalCrtWarning(guiGraphics: GuiGraphics, screenX: Int, screenY: Int, screenWidth: Int, screenHeight: Int, now: Long) {
        if (!isCriticalWarningFlashVisible(now)) return
        val centerX = screenX + screenWidth / 2
        val centerY = screenY + screenHeight / 2
        val block = (screenWidth / 10).coerceIn(4, 7)
        val gap = (block / 2).coerceAtLeast(2)
        val barHeight = (screenHeight * 0.46).roundToInt().coerceAtLeast(block * 3)
        val left = centerX - block / 2
        val top = centerY - barHeight / 2 - gap
        guiGraphics.fill(left, top, left + block, top + barHeight, CRITICAL_WARNING_COLOR)
        guiGraphics.fill(left, top + barHeight + gap, left + block, top + barHeight + gap + block, CRITICAL_WARNING_COLOR)
    }

    private fun drawDamageHudParts(
        guiGraphics: GuiGraphics,
        screenX: Int,
        screenY: Int,
        screenWidth: Int,
        screenHeight: Int,
        parts: List<DamageHudPart>
    ) {
        val minX = parts.minOf { it.centerX - it.sizeX * 0.5 }
        val maxX = parts.maxOf { it.centerX + it.sizeX * 0.5 }
        val minZ = parts.minOf { it.centerZ - it.sizeZ * 0.5 }
        val maxZ = parts.maxOf { it.centerZ + it.sizeZ * 0.5 }
        val contentWidth = (screenWidth - MINI_CRT_SCREEN_PADDING * 2).coerceAtLeast(1)
        val contentHeight = (screenHeight - MINI_CRT_SCREEN_PADDING * 2).coerceAtLeast(1)
        val spanX = (maxX - minX).takeIf { it.isFinite() && it > 1.0e-4 } ?: 1.0
        val spanZ = (maxZ - minZ).takeIf { it.isFinite() && it > 1.0e-4 } ?: 1.0
        val scale = minOf(contentWidth / spanX, contentHeight / spanZ)
        val screenCenterX = screenX + screenWidth * 0.5
        val screenCenterY = screenY + screenHeight * 0.5
        val contentCenterX = (minX + maxX) * 0.5
        val contentCenterZ = (minZ + maxZ) * 0.5

        parts.sortedBy { it.drawOrder }.forEach { part ->
            val left = (screenCenterX - (part.centerX + part.sizeX * 0.5 - contentCenterX) * scale).roundToInt()
            val right = (screenCenterX - (part.centerX - part.sizeX * 0.5 - contentCenterX) * scale).roundToInt()
            val top = (screenCenterY - (part.centerZ + part.sizeZ * 0.5 - contentCenterZ) * scale).roundToInt()
            val bottom = (screenCenterY - (part.centerZ - part.sizeZ * 0.5 - contentCenterZ) * scale).roundToInt()
            val minWidth = if (part.type == VehiclePartTypes.WHEEL) DAMAGE_PART_MIN_PIXELS + 1 else DAMAGE_PART_MIN_PIXELS
            val drawLeft = left.coerceAtMost(right - minWidth)
            val drawTop = top.coerceAtMost(bottom - minWidth)
            val drawRight = right.coerceAtLeast(drawLeft + minWidth)
            val drawBottom = bottom.coerceAtLeast(drawTop + minWidth)
            val color = damageHudColor(part.healthFraction)
            val fillAlpha = if (part.type == VehiclePartTypes.BODY) 0x76000000 else 0xB8000000.toInt()
            val fillColor = (color and 0x00FFFFFF) or fillAlpha
            val outlineColor = lightenColor((color and 0x00FFFFFF) or 0xCC000000.toInt())
            guiGraphics.fill(drawLeft, drawTop, drawRight, drawBottom, fillColor)
            drawRectOutline(guiGraphics, drawLeft, drawTop, drawRight, drawBottom, outlineColor)
        }
    }

    private fun drawRectOutline(guiGraphics: GuiGraphics, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        guiGraphics.fill(left, top, right, top + 1, color)
        guiGraphics.fill(left, bottom - 1, right, bottom, color)
        guiGraphics.fill(left, top, left + 1, bottom, color)
        guiGraphics.fill(right - 1, top, right, bottom, color)
    }

    private fun damageHudParts(vehicle: IVehicle): List<DamageHudPart> {
        val partsById = vehicle.vehicleDefinition.parts.associateBy { it.id }
        val zoneParts = vehicle.vehicleDefinition.interactions.zones.mapNotNull { zone ->
            val partId = zone.partId ?: return@mapNotNull null
            val part = partsById[partId] ?: return@mapNotNull null
            if (VehicleInteractionActions.REPAIR !in zone.actions) return@mapNotNull null
            if (part.type != VehiclePartTypes.BODY && part.type != VehiclePartTypes.ENGINE && part.type != VehiclePartTypes.WHEEL) {
                return@mapNotNull null
            }
            if (!zone.center.isFinite() || !zone.size.isFinite()) return@mapNotNull null
            DamageHudPart(
                id = partId,
                type = part.type,
                centerX = zone.center.x,
                centerZ = zone.center.z,
                sizeX = zone.size.x.coerceAtLeast(0.18),
                sizeZ = zone.size.z.coerceAtLeast(0.18),
                healthFraction = VehicleDamage.healthFraction(vehicle, partId)
            )
        }
        if (zoneParts.any { it.type == VehiclePartTypes.BODY }) return zoneParts

        val bodyPart = partsById[VehicleDamage.BODY_PART_ID] ?: return zoneParts
        return zoneParts + DamageHudPart(
            id = VehicleDamage.BODY_PART_ID,
            type = bodyPart.type,
            centerX = vehicle.vehicleDefinition.body.collisionBoxOffset.x,
            centerZ = vehicle.vehicleDefinition.body.collisionBoxOffset.z,
            sizeX = vehicle.vehicleDefinition.body.collisionBoxSize.x.coerceAtLeast(0.4),
            sizeZ = vehicle.vehicleDefinition.body.collisionBoxSize.z.coerceAtLeast(0.4),
            healthFraction = VehicleDamage.healthFraction(vehicle, VehicleDamage.BODY_PART_ID)
        )
    }

    private fun damageHudColor(healthFraction: Double): Int {
        val health = healthFraction.coerceIn(0.0, 1.0)
        return if (health <= 0.0) {
            0xFFFF2020.toInt()
        } else if (health < 0.45) {
            lerpColor(0xFFFF2020.toInt(), 0xFFFFD93D.toInt(), health / 0.45)
        } else {
            lerpColor(0xFFFFD93D.toInt(), 0xFF20F060.toInt(), (health - 0.45) / 0.55)
        }
    }

    private fun lerpColor(from: Int, to: Int, t: Double): Int {
        val clamped = t.coerceIn(0.0, 1.0)
        val r = (((from ushr 16) and 0xFF) + ((((to ushr 16) and 0xFF) - ((from ushr 16) and 0xFF)) * clamped)).roundToInt()
        val g = (((from ushr 8) and 0xFF) + ((((to ushr 8) and 0xFF) - ((from ushr 8) and 0xFF)) * clamped)).roundToInt()
        val b = ((from and 0xFF) + (((to and 0xFF) - (from and 0xFF)) * clamped)).roundToInt()
        return 0xFF000000.toInt() or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    private fun lightenColor(color: Int): Int {
        val r = (((color ushr 16) and 0xFF) + 78).coerceAtMost(255)
        val g = (((color ushr 8) and 0xFF) + 78).coerceAtMost(255)
        val b = ((color and 0xFF) + 78).coerceAtMost(255)
        return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    private fun renderMeters(
        guiGraphics: GuiGraphics,
        screenWidth: Int,
        screenHeight: Int,
        snapshot: VehicleHudSnapshot,
        dt: Double,
        criticalFailure: Boolean
    ) {
        val y = screenHeight - METER_BASE.height
        var currentIndex = 1
        meters.forEach { meter ->
            if (meter.isVisible(snapshot)) {
                val shake = criticalTremble(criticalFailure, 4.0 + currentIndex, horizontal = EdgeShake.OUT_POSITIVE, vertical = EdgeShake.OUT_POSITIVE)
                meter.render(guiGraphics, screenWidth + (meter.anchorOffsetX * currentIndex) + shake.x, y + shake.y, snapshot, dt)
                currentIndex++
            }
        }
    }

    private fun renderRacePlacement(guiGraphics: GuiGraphics, screenWidth: Int, snapshot: VehicleHudSnapshot) {
        val placement = RaceHudClientState.placementFor(snapshot.bodyId) ?: return
        drawTopRightText(guiGraphics, screenWidth, 8, "${ordinal(placement.place)} / ${placement.total}", 0xFFFFF6A8.toInt(), scale = 2.0f)
        drawTopRightText(guiGraphics, screenWidth, 30, "Lap ${placement.lap}/${placement.totalLaps}", 0xFFFFFFFF.toInt())
        drawTopRightText(guiGraphics, screenWidth, 42, formatLapTime(placement.lapElapsedTicks), 0xFFE0F7FF.toInt())
    }

    private fun drawTopRightText(guiGraphics: GuiGraphics, screenWidth: Int, y: Int, text: String, color: Int, scale: Float = 1.0f) {
        val font = Minecraft.getInstance().font
        val x = screenWidth - (font.width(text) * scale).roundToInt() - 8
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(x.toDouble(), y.toDouble(), 0.0)
        pose.scale(scale, scale, 1.0f)
        guiGraphics.drawString(font, text, 0, 0, color, true)
        pose.popPose()
    }

    private fun consumeRenderDt(): Double {
        val now = System.currentTimeMillis()
        val previous = lastRenderMillis
        lastRenderMillis = now
        if (previous <= 0L) return 1.0 / 60.0
        return ((now - previous) / 1000.0).coerceIn(0.0, 0.1)
    }

    private fun resetVisualState() {
        lastRenderMillis = 0L
        visualSteeringDegrees = 0.0
        visualSteeringVelocityDegrees = 0.0
        visualFuelTiltPixels = 0.0
        visualFuelTiltVelocity = 0.0
        previousFuelForwardSpeed = null
        previousCriticalBodyId = null
        previousCriticalFailure = false
        lastWarningBeepCycle = Long.MIN_VALUE
        visualMeterValues.clear()
    }

    private fun updateVisualSteeringDegrees(steer: Double, dt: Double): Double {
        val target = -steer.coerceIn(-1.0, 1.0) * STEERING_VISUAL_MAX_DEGREES
        val safeDt = max(dt, 0.0)
        val acceleration = (target - visualSteeringDegrees) * STEERING_VISUAL_STIFFNESS -
            visualSteeringVelocityDegrees * STEERING_VISUAL_DAMPING
        visualSteeringVelocityDegrees += acceleration * safeDt
        visualSteeringDegrees += visualSteeringVelocityDegrees * safeDt
        if (kotlin.math.abs(target - visualSteeringDegrees) < 0.01 && kotlin.math.abs(visualSteeringVelocityDegrees) < 0.01) {
            visualSteeringDegrees = target
            visualSteeringVelocityDegrees = 0.0
        }
        return visualSteeringDegrees
    }

    private fun updateFuelTankSlosh(snapshot: VehicleHudSnapshot, dt: Double): FuelSloshState {
        val motion = readVehicleMotion(snapshot.bodyId)
        val safeDt = dt.coerceIn(0.0, 0.1)
        val previousSpeed = previousFuelForwardSpeed
        val acceleration = if (previousSpeed != null && safeDt > 1.0e-4) {
            ((snapshot.forwardSpeed - previousSpeed) / safeDt).coerceIn(-10.0, 10.0)
        } else {
            0.0
        }
        previousFuelForwardSpeed = snapshot.forwardSpeed

        val turnSlosh = (motion.lateralShove * 1.35).coerceIn(-10.0, 10.0)
        val accelerationSlosh = -acceleration * 0.35
        val targetTilt = (motion.rollRad * 20.0 + turnSlosh + accelerationSlosh)
            .coerceIn(-FUEL_SLOSH_MAX_TILT_PIXELS, FUEL_SLOSH_MAX_TILT_PIXELS)

        val tilt = updateFuelSpring(visualFuelTiltPixels, visualFuelTiltVelocity, targetTilt, safeDt)
        visualFuelTiltPixels = tilt.value
        visualFuelTiltVelocity = tilt.velocity
        return FuelSloshState(visualFuelTiltPixels)
    }

    private fun updateFuelSpring(current: Double, velocity: Double, target: Double, dt: Double): SpringValue {
        val acceleration = (target - current) * FUEL_SLOSH_STIFFNESS - velocity * FUEL_SLOSH_DAMPING
        val nextVelocity = velocity + acceleration * dt
        val next = current + nextVelocity * dt
        return SpringValue(next, nextVelocity)
    }

    private fun readVehicleMotion(bodyId: Long): VehicleMotion {
        val level = Minecraft.getInstance().level ?: return VehicleMotion.ZERO
        val vehicle = VehicleManager.getVehicle(level, bodyId) ?: return VehicleMotion.ZERO
        val rotation = try {
            vehicle.getRenderTransform()?.rotation ?: return VehicleMotion.ZERO
        } catch (_: IllegalStateException) {
            return VehicleMotion.ZERO
        }
        val right = Vector3d(1.0, 0.0, 0.0).rotate(rotation)
        val roll = asin((-right.y).coerceIn(-1.0, 1.0))
        val kinematics = level.shipWorld?.allBodies?.getById(bodyId)?.kinematics
        val lateralShove = if (kinematics != null) {
            val velocity = Vector3d(kinematics.velocity)
            val angularVelocity = Vector3d(kinematics.angularVelocity)
            if (velocity.isFinite() && angularVelocity.isFinite()) {
                Vector3d(velocity).cross(angularVelocity).dot(right).coerceIn(-12.0, 12.0)
            } else {
                0.0
            }
        } else {
            0.0
        }
        return VehicleMotion(roll, lateralShove)
    }

    private fun drawFuelTank(
        guiGraphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        fuel: Double,
        slosh: FuelSloshState
    ) {
        val filledHeight = (height * fuel.coerceIn(0.0, 1.0)).roundToInt().coerceIn(0, height)
        if (filledHeight <= 0) return
        val filledY = y + height - filledHeight
        val bottom = y + height
        val time = System.currentTimeMillis() / 1000.0
        val widthDenominator = (width - 1).coerceAtLeast(1).toDouble()
        for (column in 0 until width) {
            val horizontalT = column / widthDenominator - 0.5
            val lazySlosh = sin(column * 0.16 + time * 1.05) * 1.15
            val roundSwell = sin(column * 0.31 - time * 1.85 + 1.4) * 0.75
            val smallRipple = sin(column * 0.67 + time * 2.65 + 0.8) * 0.32
            val wave = lazySlosh + roundSwell + smallRipple + horizontalT * slosh.tiltPixels
            val surfaceY = (filledY + wave.roundToInt()).coerceIn(y, bottom)
            guiGraphics.fill(x + column, surfaceY, x + column + 1, bottom, 0xF7F4B626.toInt())
            if (surfaceY < bottom) {
                guiGraphics.fill(x + column, surfaceY, x + column + 1, (surfaceY + 1).coerceAtMost(bottom), 0xFFFFE46A.toInt())
            }
        }
        val glowTop = (filledY + 5).coerceAtMost(bottom)
        if (glowTop < bottom) {
            guiGraphics.fill(x + 2, glowTop, x + width - 2, bottom, 0x22FF7A1A)
        }
        drawFuelBubbles(guiGraphics, x, filledY, width, filledHeight, time)
    }

    private fun drawFuelBubbles(guiGraphics: GuiGraphics, x: Int, filledY: Int, width: Int, filledHeight: Int, time: Double) {
        if (filledHeight < 5) return
        val bubbleSeeds = listOf(
            FuelBubble(7, 0.0, 1),
            FuelBubble(16, 0.33, 2),
            FuelBubble(27, 0.62, 1),
            FuelBubble(38, 0.18, 1)
        )
        bubbleSeeds.forEachIndexed { index, bubble ->
            val cycle = positiveModulo(time * (0.24 + index * 0.035) + bubble.phase, 1.0)
            val bubbleX = x + bubble.x.coerceIn(1, width - 3) + (sin(time * 2.1 + index) * 1.6).roundToInt()
            val bubbleY = filledY + ((1.0 - cycle) * filledHeight).roundToInt()
            if (bubbleY < filledY + 1 || bubbleY > filledY + filledHeight - 2) return@forEachIndexed
            guiGraphics.fill(bubbleX, bubbleY, bubbleX + bubble.size, bubbleY + bubble.size, 0xCFFFF2A4.toInt())
            if (bubble.size > 1) {
                guiGraphics.fill(bubbleX + bubble.size, bubbleY + 1, bubbleX + bubble.size + 1, bubbleY + 2, 0x80FFFBE0.toInt())
            }
        }
        val sparkleCycle = positiveModulo(time * 0.9, 1.0)
        if (sparkleCycle < 0.45) {
            val sparkleX = x + (width * (0.66 + sin(time * 0.73) * 0.08)).roundToInt()
            val sparkleY = filledY + (filledHeight * (0.36 + sin(time * 1.11 + 1.7) * 0.12)).roundToInt()
            guiGraphics.fill(sparkleX, sparkleY, sparkleX + 1, sparkleY + 1, 0xFFFFFFFF.toInt())
            guiGraphics.fill(sparkleX - 1, sparkleY + 1, sparkleX + 2, sparkleY + 2, 0x99FFF2A4.toInt())
        }
    }

    private fun positiveModulo(value: Double, modulus: Double): Double {
        val result = value % modulus
        return if (result < 0.0) result + modulus else result
    }

    private fun criticalTremble(criticalFailure: Boolean, salt: Double, horizontal: EdgeShake, vertical: EdgeShake): HudShakeOffset {
        if (!criticalFailure) return HudShakeOffset.ZERO
        val time = System.currentTimeMillis() / 1000.0
        val rawX = trembleSample(time, salt)
        val rawY = trembleSample(time, salt + 9.35)
        return HudShakeOffset(horizontal.apply(rawX), vertical.apply(rawY))
    }

    private fun trembleSample(time: Double, salt: Double): Int {
        val value = sin(time * 58.0 + salt) + sin(time * 113.0 + salt * 1.7) * 0.58
        return (value * CRITICAL_TREMBLE_PIXELS).roundToInt().coerceIn(-CRITICAL_TREMBLE_PIXELS, CRITICAL_TREMBLE_PIXELS)
    }

    private fun updateCriticalHudSounds(minecraft: Minecraft, bodyId: Long, criticalFailure: Boolean, now: Long) {
        val bodyChanged = previousCriticalBodyId != bodyId
        if (bodyChanged) {
            previousCriticalBodyId = bodyId
            previousCriticalFailure = false
            lastWarningBeepCycle = Long.MIN_VALUE
        }

        if (criticalFailure && !previousCriticalFailure) {
            playHudSound(minecraft, SkyridersSounds.CRT_CRITICAL_START_SOUND.get(), volume = 0.9f, pitch = 1.0f)
        }

        if (criticalFailure && isCriticalWarningFlashVisible(now)) {
            val cycle = criticalWarningFlashCycle(now)
            if (cycle != lastWarningBeepCycle) {
                playHudSound(minecraft, SkyridersSounds.CRT_WARNING_BEEP_SOUND.get(), volume = 0.75f, pitch = 1.0f)
                lastWarningBeepCycle = cycle
            }
        }

        if (!criticalFailure) {
            lastWarningBeepCycle = Long.MIN_VALUE
        }
        previousCriticalFailure = criticalFailure
    }

    private fun criticalWarningFlashCycle(now: Long): Long {
        return now / CRITICAL_WARNING_FLASH_MILLIS
    }

    private fun isCriticalWarningFlashVisible(now: Long): Boolean {
        return criticalWarningFlashCycle(now) % 2L == 0L
    }

    private fun playHudSound(minecraft: Minecraft, sound: SoundEvent, volume: Float, pitch: Float) {
        minecraft.soundManager.play(
            SimpleSoundInstance(
                sound.location,
                SoundSource.MASTER,
                volume,
                pitch,
                SoundInstance.createUnseededRandom(),
                false,
                0,
                SoundInstance.Attenuation.NONE,
                0.0,
                0.0,
                0.0,
                true
            )
        )
    }

    private fun Vector3d.isFinite(): Boolean {
        return x.isFinite() && y.isFinite() && z.isFinite()
    }

    private fun updateVisualMeterValue(key: String, target: Double, dt: Double): Double {
        val clampedTarget = target.coerceIn(0.0, 1.0)
        val current = visualMeterValues[key] ?: clampedTarget
        val delta = clampedTarget - current
        val next = if (kotlin.math.abs(delta) >= METER_SMOOTH_THRESHOLD) {
            val alpha = 1.0 - exp(-dt.coerceIn(0.0, 0.1) * METER_SMOOTH_RESPONSE)
            current + delta * alpha
        } else {
            clampedTarget
        }
        val clampedNext = next.coerceIn(0.0, 1.0)
        visualMeterValues[key] = clampedNext
        return clampedNext
    }

    private fun drawRotatedRegion(
        guiGraphics: GuiGraphics,
        texture: ResourceLocation,
        textureWidth: Int,
        textureHeight: Int,
        region: HudTextureRegion,
        x: Int,
        y: Int,
        pivotX: Double,
        pivotY: Double,
        degrees: Float
    ) {
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(pivotX, pivotY, 0.0)
        pose.mulPose(Axis.ZP.rotationDegrees(degrees))
        pose.translate(-pivotX, -pivotY, 0.0)
        guiGraphics.blitRegion(texture, region, x, y, textureWidth, textureHeight)
        pose.popPose()
    }

    private fun formatGear(gear: Int): String {
        return when {
            gear < 0 -> "R"
            gear == 0 -> "N"
            else -> gear.coerceIn(0, 9).toString()
        }
    }

    private fun ordinal(value: Int): String {
        val mod100 = value % 100
        val suffix = if (mod100 in 11..13) {
            "th"
        } else {
            when (value % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$value$suffix"
    }

    private fun formatLapTime(ticks: Long): String {
        val totalCentiseconds = (ticks.coerceAtLeast(0L) * 5L)
        val minutes = totalCentiseconds / 6000L
        val seconds = (totalCentiseconds / 100L) % 60L
        val centiseconds = totalCentiseconds % 100L
        return "%d:%02d.%02d".format(minutes, seconds, centiseconds)
    }

    private data class VehicleHudSnapshot(
        val bodyId: Long,
        val maxSpeed: Double,
        val speed: Double,
        val forwardSpeed: Double,
        val steer: Double,
        val engineOn: Boolean,
        val transmissionGear: Int,
        val parkingBrakeEngaged: Boolean,
        val engineRpm: Double,
        val jumpCharge: Double = 0.0,
        val engineStalled: Boolean,
        val fuel: Double,
        val hasRevs: Boolean = true,
        val hasJump: Boolean = false,
        val receivedAtMillis: Long
    )

    private data class DamageHudPart(
        val id: String,
        val type: ResourceLocation,
        val centerX: Double,
        val centerZ: Double,
        val sizeX: Double,
        val sizeZ: Double,
        val healthFraction: Double
    ) {
        val drawOrder: Int = when (type) {
            VehiclePartTypes.BODY -> 0
            VehiclePartTypes.ENGINE -> 1
            VehiclePartTypes.WHEEL -> 2
            else -> 3
        }
    }

    private data class BooleanTextureWidget(
        val x: Int,
        val y: Int,
        val offRegion: HudTextureRegion?,
        val onRegion: HudTextureRegion,
        val value: (VehicleHudSnapshot) -> Boolean
    ) {
        fun render(
            guiGraphics: GuiGraphics,
            texture: ResourceLocation,
            textureWidth: Int,
            textureHeight: Int,
            parentX: Int,
            parentY: Int,
            snapshot: VehicleHudSnapshot
        ) {
            val region = if (value(snapshot)) onRegion else offRegion
            if (region != null) guiGraphics.blitRegion(texture, region, parentX + x, parentY + y, textureWidth, textureHeight)
        }
    }

    private data class ThreeStateTextureWidget(
        val x: Int,
        val y: Int,
        val offRegion: HudTextureRegion,
        val onRegion: HudTextureRegion,
        val state: (VehicleHudSnapshot) -> VehicleHudKeyState
    ) {
        fun render(
            guiGraphics: GuiGraphics,
            texture: ResourceLocation,
            textureWidth: Int,
            textureHeight: Int,
            parentX: Int,
            parentY: Int,
            snapshot: VehicleHudSnapshot
        ) {
            val region = when (state(snapshot)) {
                VehicleHudKeyState.HIDDEN -> return
                VehicleHudKeyState.PRESENT_ENGINE_OFF -> offRegion
                VehicleHudKeyState.PRESENT_ENGINE_ON -> onRegion
            }
            guiGraphics.blitRegion(texture, region, parentX + x, parentY + y, textureWidth, textureHeight)
        }
    }

    private enum class VehicleHudKeyState {
        HIDDEN,
        PRESENT_ENGINE_OFF,
        PRESENT_ENGINE_ON
    }

    private data class FuelBubble(
        val x: Int,
        val phase: Double,
        val size: Int
    )

    private data class FuelSloshState(
        val tiltPixels: Double
    )

    private data class HudShakeOffset(
        val x: Int,
        val y: Int
    ) {
        companion object {
            val ZERO = HudShakeOffset(0, 0)
        }
    }

    private enum class EdgeShake {
        NONE,
        FREE,
        OUT_NEGATIVE,
        OUT_POSITIVE;

        fun apply(value: Int): Int {
            return when (this) {
                NONE -> 0
                FREE -> value
                OUT_NEGATIVE -> -kotlin.math.abs(value)
                OUT_POSITIVE -> kotlin.math.abs(value)
            }
        }
    }

    private data class VehicleMotion(
        val rollRad: Double,
        val lateralShove: Double
    ) {
        companion object {
            val ZERO = VehicleMotion(0.0, 0.0)
        }
    }

    private data class SpringValue(
        val value: Double,
        val velocity: Double
    )

    private data class RoundMeterWidget(
        val title: String = "",
        val anchorOffsetX: Int,
        val value: (VehicleHudSnapshot) -> Double,
        val counter: (VehicleHudSnapshot) -> String,
        val minDegrees: Float,
        val maxDegrees: Float
    ) {
        fun isVisible(snapshot: VehicleHudSnapshot): Boolean {
            return when (title) {
                "5u5" -> snapshot.hasJump
                "rev" -> snapshot.hasRevs
                else -> true
            }
        }

        fun render(guiGraphics: GuiGraphics, x: Int, y: Int, snapshot: VehicleHudSnapshot, dt: Double) {
            guiGraphics.blitRegion(METER_TEXTURE, METER_BASE, x, y, METER_TEXTURE_WIDTH, METER_TEXTURE_HEIGHT)
            val t = updateVisualMeterValue(title.ifBlank { anchorOffsetX.toString() }, value(snapshot), dt)
            val degrees = minDegrees + ((maxDegrees - minDegrees) * t).toFloat()
            val pivotScreenX = x + METER_WIDGET_PIVOT_X
            val pivotScreenY = y + METER_WIDGET_PIVOT_Y
            val dialX = (pivotScreenX - METER_DIAL.width / 2.0).roundToInt()
            val dialY = (pivotScreenY - METER_DIAL.height).roundToInt()
            val pivotCapX = (pivotScreenX - METER_DIAL_PIVOT.width / 2.0).roundToInt()
            val pivotCapY = (pivotScreenY - METER_DIAL_PIVOT.height / 2.0).roundToInt()
            drawRotatedRegion(
                guiGraphics = guiGraphics,
                texture = METER_TEXTURE,
                textureWidth = METER_TEXTURE_WIDTH,
                textureHeight = METER_TEXTURE_HEIGHT,
                region = METER_DIAL,
                x = dialX,
                y = dialY,
                pivotX = pivotScreenX,
                pivotY = pivotScreenY,
                degrees = degrees
            )
            guiGraphics.blitRegion(METER_TEXTURE, METER_DIAL_PIVOT, pivotCapX, pivotCapY, METER_TEXTURE_WIDTH, METER_TEXTURE_HEIGHT)

            //tag
            if (title.isNotBlank()) {
                guiGraphics.blitRegion(METER_TEXTURE, METER_COUNTER_BACKGROUND, x - 6, y + 68, METER_TEXTURE_WIDTH, METER_TEXTURE_HEIGHT)
                METER_ATLAS.renderText(guiGraphics, x - 3, y + 71, title, maxLength = 3, characterSpacing = 1)
            }

            //counter
            guiGraphics.blitRegion(METER_TEXTURE, METER_COUNTER_BACKGROUND, x + 82, y + 68, METER_TEXTURE_WIDTH, METER_TEXTURE_HEIGHT)
            METER_ATLAS.renderText(guiGraphics, x + 85, y + 71, counter(snapshot), maxLength = 3, characterSpacing = 1)
        }
    }
}
