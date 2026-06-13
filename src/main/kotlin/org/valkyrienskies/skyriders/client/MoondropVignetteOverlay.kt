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
        if (intensity <= MIN_VISIBLE_INTENSITY) return

        val maxInset = (minOf(screenWidth, screenHeight) * 0.18).roundToInt().coerceAtLeast(1)
        val layers = maxInset.coerceAtMost(MAX_LAYERS).coerceAtLeast(minOf(MIN_LAYERS, maxInset))
        val phase = System.currentTimeMillis() / 2600.0
        for (layer in 0 until layers) {
            val inset = (layer.toDouble() * maxInset.toDouble() / layers.toDouble()).roundToInt()
            val nextInset = ((layer + 1).toDouble() * maxInset.toDouble() / layers.toDouble()).roundToInt()
                .coerceAtLeast(inset + 1)
                .coerceAtMost(maxInset)
            val edgeFactor = 1.0 - layer.toDouble() / layers.toDouble()
            val smoothFactor = edgeFactor * edgeFactor * (3.0 - 2.0 * edgeFactor)
            val alpha = (smoothFactor * MAX_ALPHA * intensity).roundToInt().coerceIn(0, 255)
            if (alpha <= 0) continue

            val colorOffset = layer * 0.009
            val topColor = colorInt(pastelRainbow(phase + colorOffset), alpha)
            val rightColor = colorInt(pastelRainbow(phase + 0.18 + colorOffset), alpha)
            val bottomColor = colorInt(pastelRainbow(phase + 0.36 + colorOffset), alpha)
            val leftColor = colorInt(pastelRainbow(phase + 0.54 + colorOffset), alpha)

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
    private const val MIN_VISIBLE_INTENSITY = 0.012
    private const val MIN_LAYERS = 32
    private const val MAX_LAYERS = 96
}
