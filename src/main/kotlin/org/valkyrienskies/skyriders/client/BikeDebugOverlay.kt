package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import java.util.Locale
import kotlin.math.PI

object BikeDebugOverlay {
    private const val STALE_AFTER_MILLIS = 750L
    private var snapshot: Snapshot? = null

    fun update(packet: SkyridersNetwork.BikeDebugPacket) {
        snapshot = Snapshot(
            bodyId = packet.bodyId,
            bikeId = packet.bikeId,
            bikeName = packet.bikeName,
            speed = packet.speed,
            frontGrounded = packet.frontGrounded,
            rearGrounded = packet.rearGrounded,
            throttle = packet.throttle,
            steeringAngleRad = packet.steeringAngleRad,
            drifting = packet.drifting,
            engineOn = packet.engineOn,
            jumpCharge = packet.jumpCharge,
            receivedAtMillis = System.currentTimeMillis()
        )
    }

    @SubscribeEvent
    fun onRenderOverlay(event: RenderGuiOverlayEvent.Post) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val seat = player.vehicle as? BikeSeatEntity ?: run {
            snapshot = null
            return
        }
        val currentSnapshot = snapshot ?: return
        if (currentSnapshot.bodyId != seat.bodyId) return
        if (System.currentTimeMillis() - currentSnapshot.receivedAtMillis > STALE_AFTER_MILLIS) return

        drawLines(
            event.guiGraphics,
            listOf(
                "Skyriders Bike",
                "Bike Type: ${currentSnapshot.bikeName} (${currentSnapshot.bikeId})",
                "Current Speed: ${formatNumber(currentSnapshot.speed)}",
                "Engine On: ${currentSnapshot.engineOn}",
                "Front Wheel Grounded: ${currentSnapshot.frontGrounded}",
                "Rear Wheel Grounded: ${currentSnapshot.rearGrounded}",
                "Throttle: ${formatNumber(currentSnapshot.throttle)}",
                "Steering Angle: ${formatDegrees(currentSnapshot.steeringAngleRad)} deg",
                "Is Drifting: ${currentSnapshot.drifting}",
                "Jump Charge: ${formatPercent(currentSnapshot.jumpCharge)}"
            )
        )
    }

    private fun drawLines(guiGraphics: GuiGraphics, lines: List<String>) {
        val font = Minecraft.getInstance().font
        val x = 4
        var y = 4
        val lineHeight = font.lineHeight + 1
        lines.forEach { line ->
            guiGraphics.fill(x - 2, y - 1, x + font.width(line) + 2, y + lineHeight - 1, 0x90505050.toInt())
            guiGraphics.drawString(font, line, x, y, 0xE0E0E0, false)
            y += lineHeight
        }
    }

    private fun formatNumber(value: Double): String {
        return String.format(Locale.ROOT, "%.2f", if (value.isFinite()) value else 0.0)
    }

    private fun formatDegrees(radians: Double): String {
        val degrees = if (radians.isFinite()) radians * 180.0 / PI else 0.0
        return String.format(Locale.ROOT, "%.1f", degrees)
    }

    private fun formatPercent(value: Double): String {
        val percent = if (value.isFinite()) value.coerceIn(0.0, 1.0) * 100.0 else 0.0
        return String.format(Locale.ROOT, "%.0f%%", percent)
    }

    private data class Snapshot(
        val bodyId: Long,
        val bikeId: String,
        val bikeName: String,
        val speed: Double,
        val frontGrounded: Boolean,
        val rearGrounded: Boolean,
        val throttle: Double,
        val steeringAngleRad: Double,
        val drifting: Boolean,
        val engineOn: Boolean,
        val jumpCharge: Double,
        val receivedAtMillis: Long
    )
}
