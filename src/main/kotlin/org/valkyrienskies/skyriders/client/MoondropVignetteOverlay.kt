package org.valkyrienskies.skyriders.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderType
import org.joml.Vector3f
import org.joml.Matrix4f
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
        val consumer = guiGraphics.bufferSource().getBuffer(RenderType.gui())
        val matrix = guiGraphics.pose().last().pose()
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
            val outerLeft = inset
            val outerTop = inset
            val outerRight = screenWidth - inset
            val outerBottom = screenHeight - inset
            val innerLeft = nextInset
            val innerTop = nextInset
            val innerRight = screenWidth - nextInset
            val innerBottom = screenHeight - nextInset
            val outerWidth = (outerRight - outerLeft).coerceAtLeast(1)
            val outerHeight = (outerBottom - outerTop).coerceAtLeast(1)
            val perimeter = (outerWidth * 2 + outerHeight * 2).coerceAtLeast(1).toDouble()
            val topLeft = colorInt(pastelRainbow(phase + colorOffset), alpha)
            val topRight = colorInt(pastelRainbow(phase + colorOffset + outerWidth / perimeter), alpha)
            val bottomRight = colorInt(pastelRainbow(phase + colorOffset + (outerWidth + outerHeight) / perimeter), alpha)
            val bottomLeft = colorInt(pastelRainbow(phase + colorOffset + (outerWidth * 2 + outerHeight) / perimeter), alpha)

            drawQuad(consumer, matrix, outerLeft, outerTop, outerLeft, innerTop, outerRight, innerTop, outerRight, outerTop, topLeft, topLeft, topRight, topRight)
            drawQuad(consumer, matrix, innerRight, innerTop, innerRight, outerBottom, outerRight, outerBottom, outerRight, outerTop, topRight, bottomRight, bottomRight, topRight)
            drawQuad(consumer, matrix, outerLeft, innerBottom, outerLeft, outerBottom, outerRight, outerBottom, outerRight, innerBottom, bottomLeft, bottomLeft, bottomRight, bottomRight)
            drawQuad(consumer, matrix, outerLeft, outerTop, outerLeft, outerBottom, innerLeft, innerBottom, innerLeft, innerTop, topLeft, bottomLeft, bottomLeft, topLeft)
        }
        guiGraphics.flush()
    }

    private fun drawQuad(
        consumer: VertexConsumer,
        matrix: Matrix4f,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        x3: Int,
        y3: Int,
        x4: Int,
        y4: Int,
        color1: Int,
        color2: Int,
        color3: Int,
        color4: Int
    ) {
        vertex(consumer, matrix, x1, y1, color1)
        vertex(consumer, matrix, x2, y2, color2)
        vertex(consumer, matrix, x3, y3, color3)
        vertex(consumer, matrix, x4, y4, color4)
    }

    private fun vertex(consumer: VertexConsumer, matrix: Matrix4f, x: Int, y: Int, color: Int) {
        consumer.vertex(matrix, x.toFloat(), y.toFloat(), 0.0f)
            .color(
                ((color shr 16) and 255) / 255.0f,
                ((color shr 8) and 255) / 255.0f,
                (color and 255) / 255.0f,
                ((color ushr 24) and 255) / 255.0f
            )
            .endVertex()
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

    private const val MAX_ALPHA = 78.0
    private const val MIN_VISIBLE_INTENSITY = 0.012
    private const val MIN_LAYERS = 32
    private const val MAX_LAYERS = 96
}
