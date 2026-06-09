package org.valkyrienskies.skyriders.client

import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import kotlin.math.max
import kotlin.math.roundToInt

object VehicleHudOverlay {
    private const val STALE_AFTER_MILLIS = 750L
    private const val DASHBOARD_TEXTURE_WIDTH = 256
    private const val DASHBOARD_TEXTURE_HEIGHT = 256
    private const val METER_TEXTURE_WIDTH = 128
    private const val METER_TEXTURE_HEIGHT = 128

    private val DASHBOARD_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/gui/dashboard.png")
    private val METER_TEXTURE = ResourceLocation(SkyridersMod.MOD_ID, "textures/gui/meter_round.png")

    private val DASHBOARD_BASE = HudTextureRegion(0, 0, 176, 64)
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
            title = "spd",
            anchorOffsetX = 4,
            value = { snapshot -> snapshot.speed.coerceIn(0.0, 60.0) / 60.0 },
            counter = { snapshot -> snapshot.speed.roundToInt().coerceIn(0, 999).toString() },
            minDegrees = -128.0f,
            maxDegrees = 128.0f
        ),
        RoundMeterWidget(
            title = "rev",
            anchorOffsetX = 104,
            value = { snapshot -> snapshot.engineRpm.coerceIn(0.0, 7000.0) / 7000.0 },
            counter = { snapshot -> (snapshot.engineRpm / 1000.0).roundToInt().coerceIn(0, 99).toString() },
            minDegrees = -128.0f,
            maxDegrees = 128.0f
        )
    )

    private var snapshot: VehicleHudSnapshot? = null
    private var lastRenderMillis = 0L
    private var visualSteeringDegrees = 0.0
    private var visualSteeringVelocityDegrees = 0.0

    fun updateVehicle(packet: SkyridersNetwork.VehicleDebugPacket) {
        snapshot = VehicleHudSnapshot(
            bodyId = packet.bodyId,
            speed = packet.speed,
            forwardSpeed = packet.forwardSpeed,
            steer = packet.steer,
            steerAngleRad = packet.steerAngleRad,
            engineOn = packet.engineOn,
            transmissionGear = packet.transmissionGear,
            parkingBrakeEngaged = packet.parkingBrakeEngaged,
            engineRpm = packet.engineRpm,
            clutchEngagement = packet.clutchEngagement,
            engineStalled = packet.engineStalled,
            fuel = 1.0,
            receivedAtMillis = System.currentTimeMillis()
        )
    }

    @SubscribeEvent
    fun onRenderOverlay(event: RenderGuiOverlayEvent.Post) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val seat = player.vehicle as? BikeSeatEntity ?: run {
            snapshot = null
            resetVisualState()
            return
        }
        val current = snapshot ?: return
        if (current.bodyId != seat.bodyId) return
        if (System.currentTimeMillis() - current.receivedAtMillis > STALE_AFTER_MILLIS) {
            resetVisualState()
            return
        }
        val dt = consumeRenderDt()

        renderDashboard(event.guiGraphics, event.window.guiScaledWidth, event.window.guiScaledHeight, current, dt)
        renderMeters(event.guiGraphics, event.window.guiScaledWidth, event.window.guiScaledHeight, current)
    }

    private fun renderDashboard(guiGraphics: GuiGraphics, screenWidth: Int, screenHeight: Int, snapshot: VehicleHudSnapshot, dt: Double) {
        val x = 0
        val y = screenHeight - DASHBOARD_BASE.height
        val steeringDegrees = updateVisualSteeringDegrees(snapshot.steer, dt)
        drawFuelTank(guiGraphics, x + 117, y + 8, 44, 22, snapshot.fuel)
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

    private fun renderMeters(guiGraphics: GuiGraphics, screenWidth: Int, screenHeight: Int, snapshot: VehicleHudSnapshot) {
        val anchorX = screenWidth / 2
        val y = screenHeight - METER_BASE.height
        meters.forEach { meter ->
            meter.render(guiGraphics, anchorX + meter.anchorOffsetX, y, snapshot)
        }
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

    private fun drawFuelTank(guiGraphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int, fuel: Double) {
        val filledHeight = (height * fuel.coerceIn(0.0, 1.0)).roundToInt().coerceIn(0, height)
        if (filledHeight <= 0) return
        val filledY = y + height - filledHeight
        guiGraphics.fill(x, filledY, x + width, y + height, 0xF7F4B626.toInt())
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

    private data class VehicleHudSnapshot(
        val bodyId: Long,
        val speed: Double,
        val forwardSpeed: Double,
        val steer: Double,
        val steerAngleRad: Double,
        val engineOn: Boolean,
        val transmissionGear: Int,
        val parkingBrakeEngaged: Boolean,
        val engineRpm: Double,
        val clutchEngagement: Double,
        val engineStalled: Boolean,
        val fuel: Double,
        val receivedAtMillis: Long
    )

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

    private data class RoundMeterWidget(
        val title: String = "",
        val anchorOffsetX: Int,
        val value: (VehicleHudSnapshot) -> Double,
        val counter: (VehicleHudSnapshot) -> String,
        val minDegrees: Float,
        val maxDegrees: Float
    ) {
        fun render(guiGraphics: GuiGraphics, x: Int, y: Int, snapshot: VehicleHudSnapshot) {
            guiGraphics.blitRegion(METER_TEXTURE, METER_BASE, x, y, METER_TEXTURE_WIDTH, METER_TEXTURE_HEIGHT)
            val t = value(snapshot).coerceIn(0.0, 1.0)
            val degrees = minDegrees + ((maxDegrees - minDegrees) * t).toFloat()
            val pivotSourceX = METER_DIAL_PIVOT.u + METER_DIAL_PIVOT.width / 2.0
            val pivotSourceY = METER_DIAL_PIVOT.v + METER_DIAL_PIVOT.height / 2.0
            val pivotScreenX = x + METER_WIDGET_PIVOT_X
            val pivotScreenY = y + METER_WIDGET_PIVOT_Y
            val dialX = (pivotScreenX + METER_DIAL.u - pivotSourceX).roundToInt()
            val dialY = (pivotScreenY + METER_DIAL.v - pivotSourceY).roundToInt()
            val pivotCapX = (pivotScreenX + METER_DIAL_PIVOT.u - pivotSourceX).roundToInt()
            val pivotCapY = (pivotScreenY + METER_DIAL_PIVOT.v - pivotSourceY).roundToInt()
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
