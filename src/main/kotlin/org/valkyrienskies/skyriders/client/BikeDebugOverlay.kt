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
    private var vehicleSnapshot: VehicleSnapshot? = null
    private var bikeSnapshot: BikeSnapshot? = null

    fun update(packet: SkyridersNetwork.BikeDebugPacket) {
        bikeSnapshot = BikeSnapshot(
            bodyId = packet.bodyId,
            frontGrounded = packet.frontGrounded,
            rearGrounded = packet.rearGrounded,
            steeringAngleRad = packet.steeringAngleRad,
            drifting = packet.drifting,
            jumpCharge = packet.jumpCharge,
            receivedAtMillis = System.currentTimeMillis()
        )
    }

    fun updateVehicle(packet: SkyridersNetwork.VehicleDebugPacket) {
        vehicleSnapshot = VehicleSnapshot(
            bodyId = packet.bodyId,
            vehicleId = packet.vehicleId,
            vehicleName = packet.vehicleName,
            speed = packet.speed,
            engineOn = packet.engineOn,
            throttle = packet.throttle,
            steer = packet.steer,
            groundedCount = packet.groundedCount,
            drifting = packet.drifting,
            driftBoostCharge = packet.driftBoostCharge,
            driftBoostLevel = packet.driftBoostLevel,
            lateralSlip = packet.lateralSlip,
            receivedAtMillis = System.currentTimeMillis()
        )
    }

    @SubscribeEvent
    fun onRenderOverlay(event: RenderGuiOverlayEvent.Post) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val seat = player.vehicle as? BikeSeatEntity ?: run {
            vehicleSnapshot = null
            bikeSnapshot = null
            return
        }
        val currentSnapshot = vehicleSnapshot ?: return
        if (currentSnapshot.bodyId != seat.bodyId) return
        if (System.currentTimeMillis() - currentSnapshot.receivedAtMillis > STALE_AFTER_MILLIS) return
        val bikeDetails = bikeSnapshot
            ?.takeIf { it.bodyId == currentSnapshot.bodyId }
            ?.takeIf { System.currentTimeMillis() - it.receivedAtMillis <= STALE_AFTER_MILLIS }

        val lines = mutableListOf(
            "Skyriders Vehicle",
            "Vehicle Type: ${currentSnapshot.vehicleName} (${currentSnapshot.vehicleId})",
            "Current Speed: ${formatNumber(currentSnapshot.speed)}",
            "Engine On: ${currentSnapshot.engineOn}",
            "Throttle: ${formatNumber(currentSnapshot.throttle)}",
            "Steer Input: ${formatNumber(currentSnapshot.steer)}"
        )
        if (currentSnapshot.groundedCount >= 0) {
            lines.add("Grounded Wheels: ${currentSnapshot.groundedCount}")
        }
        if (bikeDetails != null) {
            lines.add("Front Wheel Grounded: ${bikeDetails.frontGrounded}")
            lines.add("Rear Wheel Grounded: ${bikeDetails.rearGrounded}")
            lines.add("Steering Angle: ${formatDegrees(bikeDetails.steeringAngleRad)} deg")
            lines.add("Is Drifting: ${bikeDetails.drifting}")
            lines.add("Jump Charge: ${formatPercent(bikeDetails.jumpCharge)}")
        } else {
            lines.add("Is Drifting: ${currentSnapshot.drifting}")
            lines.add("Drift Boost: ${formatNumber(currentSnapshot.driftBoostCharge)}s L${currentSnapshot.driftBoostLevel}")
            lines.add("Lateral Slip: ${formatNumber(currentSnapshot.lateralSlip)}")
        }

        drawLines(event.guiGraphics, lines)
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

    private data class VehicleSnapshot(
        val bodyId: Long,
        val vehicleId: String,
        val vehicleName: String,
        val speed: Double,
        val engineOn: Boolean,
        val throttle: Double,
        val steer: Double,
        val groundedCount: Int,
        val drifting: Boolean,
        val driftBoostCharge: Double,
        val driftBoostLevel: Int,
        val lateralSlip: Double,
        val receivedAtMillis: Long
    )

    private data class BikeSnapshot(
        val bodyId: Long,
        val frontGrounded: Boolean,
        val rearGrounded: Boolean,
        val steeringAngleRad: Double,
        val drifting: Boolean,
        val jumpCharge: Double,
        val receivedAtMillis: Long
    )
}
