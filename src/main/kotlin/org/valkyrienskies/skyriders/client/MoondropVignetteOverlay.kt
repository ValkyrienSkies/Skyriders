package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import org.joml.Vector3f
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import kotlin.math.roundToInt

object MoondropVignetteOverlay {
    fun render(guiGraphics: GuiGraphics, screenWidth: Int, screenHeight: Int) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val seat = player.vehicle as? BikeSeatEntity ?: return
        val vehicle = VehicleManager.getVehicle(player.level().dimensionId, seat.bodyId) ?: return
        val intensity = VehicleStatusEffects.moondropVisualIntensity(vehicle).coerceIn(0.0, 1.0)
        if (intensity <= 0.0) return

        val layers = 9
        val maxInset = (minOf(screenWidth, screenHeight) * 0.16).roundToInt().coerceAtLeast(layers)
        val layerSize = (maxInset / layers).coerceAtLeast(1)
        val phase = System.currentTimeMillis() / 2600.0
        for (layer in 0 until layers) {
            val inset = layer * layerSize
            val nextInset = ((layer + 1) * layerSize).coerceAtMost(maxInset)
            val alpha = ((1.0 - layer.toDouble() / layers.toDouble()) * MAX_ALPHA * intensity).roundToInt().coerceIn(0, 255)
            if (alpha <= 0) continue

            val topColor = colorInt(pastelRainbow(phase + layer * 0.035), alpha)
            val rightColor = colorInt(pastelRainbow(phase + 0.18 + layer * 0.035), alpha)
            val bottomColor = colorInt(pastelRainbow(phase + 0.36 + layer * 0.035), alpha)
            val leftColor = colorInt(pastelRainbow(phase + 0.54 + layer * 0.035), alpha)

            guiGraphics.fill(inset, inset, screenWidth - inset, nextInset, topColor)
            guiGraphics.fill(screenWidth - nextInset, inset, screenWidth - inset, screenHeight - inset, rightColor)
            guiGraphics.fill(inset, screenHeight - nextInset, screenWidth - inset, screenHeight - inset, bottomColor)
            guiGraphics.fill(inset, inset, nextInset, screenHeight - inset, leftColor)
        }
    }

    private fun colorInt(color: Vector3f, alpha: Int): Int {
        return (alpha.coerceIn(0, 255) shl 24) or
            ((color.x * 255.0f).roundToInt().coerceIn(0, 255) shl 16) or
            ((color.y * 255.0f).roundToInt().coerceIn(0, 255) shl 8) or
            (color.z * 255.0f).roundToInt().coerceIn(0, 255)
    }

    private fun pastelRainbow(phase: Double): Vector3f {
        val hue = phase - kotlin.math.floor(phase)
        val x = 1.0 - kotlin.math.abs((hue * 6.0) % 2.0 - 1.0)
        val (rawR, rawG, rawB) = when ((hue * 6.0).toInt()) {
            0 -> Triple(1.0, x, 0.0)
            1 -> Triple(x, 1.0, 0.0)
            2 -> Triple(0.0, 1.0, x)
            3 -> Triple(0.0, x, 1.0)
            4 -> Triple(x, 0.0, 1.0)
            else -> Triple(1.0, 0.0, x)
        }
        val saturation = 0.34
        return Vector3f(
            (1.0 - saturation + rawR * saturation).toFloat(),
            (1.0 - saturation + rawG * saturation).toFloat(),
            (1.0 - saturation + rawB * saturation).toFloat()
        )
    }

    private const val MAX_ALPHA = 58.0
}
