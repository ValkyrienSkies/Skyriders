package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.ResourceLocation
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

object RaceResultsClientState {
    private const val DISPLAY_TICKS = 20L * 12L
    private const val MAX_VISIBLE_ROWS = 8
    private const val BOARD_WIDTH = 324
    private const val ROW_HEIGHT = 24
    private const val FACE_SIZE = 16

    private var shownAtTick = Long.MIN_VALUE
    private var results: List<SkyridersNetwork.RaceResultEntry> = emptyList()

    fun show(entries: List<SkyridersNetwork.RaceResultEntry>) {
        results = entries.sortedBy { it.place }
        shownAtTick = Minecraft.getInstance().level?.gameTime ?: 0L
    }

    fun clear() {
        results = emptyList()
        shownAtTick = Long.MIN_VALUE
    }

    fun tick() {
        val level = Minecraft.getInstance().level ?: run {
            clear()
            return
        }
        if (results.isNotEmpty() && level.gameTime - shownAtTick > DISPLAY_TICKS) {
            clear()
        }
    }

    fun render(guiGraphics: GuiGraphics, screenWidth: Int, screenHeight: Int) {
        val entries = results
        if (entries.isEmpty()) return
        tick()
        if (results.isEmpty()) return

        val font = Minecraft.getInstance().font
        val visibleRows = entries.take(MAX_VISIBLE_ROWS)
        val hiddenRows = entries.size - visibleRows.size
        val footerHeight = if (hiddenRows > 0) 18 else 8
        val width = min(BOARD_WIDTH, screenWidth - 16).coerceAtLeast(232)
        val height = 33 + visibleRows.size * ROW_HEIGHT + footerHeight
        val left = (screenWidth - width) / 2
        val top = max(12, (screenHeight - height) / 4)
        val right = left + width
        val bottom = top + height

        guiGraphics.fill(left, top, right, bottom, 0xD8121720.toInt())
        guiGraphics.fill(left, top, right, top + 1, 0xFFFFD76A.toInt())
        guiGraphics.fill(left, bottom - 1, right, bottom, 0xAAFFD76A.toInt())
        guiGraphics.fill(left, top, left + 1, bottom, 0xAAFFD76A.toInt())
        guiGraphics.fill(right - 1, top, right, bottom, 0xAAFFD76A.toInt())

        val title = "Race Results"
        guiGraphics.drawString(font, title, left + (width - font.width(title)) / 2, top + 9, 0xFFFFF6A8.toInt(), true)

        var y = top + 29
        visibleRows.forEach { result ->
            renderRow(guiGraphics, result, left + 8, y, width - 16)
            y += ROW_HEIGHT
        }
        if (hiddenRows > 0) {
            val text = "+$hiddenRows more"
            guiGraphics.drawString(font, text, left + (width - font.width(text)) / 2, y + 4, 0xFFC7D7E8.toInt(), true)
        }
    }

    private fun renderRow(guiGraphics: GuiGraphics, result: SkyridersNetwork.RaceResultEntry, x: Int, y: Int, width: Int) {
        val font = Minecraft.getInstance().font
        val rowRight = x + width
        guiGraphics.fill(x, y, rowRight, y + ROW_HEIGHT - 2, 0x521B2633)

        val placeText = ordinal(result.place)
        guiGraphics.drawString(font, placeText, x + 4, y + 7, placeColor(result.place), true)
        renderFace(guiGraphics, result.playerUuid, x + 42, y + 3)

        val timeText = formatRaceTime(result.elapsedTicks)
        val timeWidth = font.width(timeText)
        guiGraphics.drawString(font, timeText, rowRight - timeWidth - 6, y + 7, 0xFFE0F7FF.toInt(), true)

        val nameX = x + 64
        val nameRight = rowRight - timeWidth - 14
        val playerText = ellipsize(result.playerName, max(24, nameRight - nameX))
        guiGraphics.drawString(font, playerText, nameX, y + 3, 0xFFFFFFFF.toInt(), true)

        val vehicleText = ellipsize(vehicleLabel(result.vehicleType), max(24, nameRight - nameX))
        guiGraphics.drawString(font, vehicleText, nameX, y + 13, 0xFFC7D7E8.toInt(), false)
    }

    private fun renderFace(guiGraphics: GuiGraphics, uuid: UUID, x: Int, y: Int) {
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(x.toDouble(), y.toDouble(), 0.0)
        pose.scale(2.0f, 2.0f, 1.0f)
        val skin = skinFor(uuid)
        guiGraphics.blit(skin, 0, 0, 8.0f, 8.0f, 8, 8, 64, 64)
        guiGraphics.blit(skin, 0, 0, 40.0f, 8.0f, 8, 8, 64, 64)
        pose.popPose()
    }

    private fun skinFor(uuid: UUID): ResourceLocation {
        return Minecraft.getInstance().connection?.getPlayerInfo(uuid)?.skinLocation
            ?: DefaultPlayerSkin.getDefaultSkin(uuid)
    }

    private fun ellipsize(text: String, maxWidth: Int): String {
        val font = Minecraft.getInstance().font
        if (font.width(text) <= maxWidth) return text
        val suffix = "..."
        val suffixWidth = font.width(suffix)
        if (suffixWidth >= maxWidth) return suffix
        var end = text.length
        while (end > 0 && font.width(text.substring(0, end)) + suffixWidth > maxWidth) {
            end--
        }
        return text.substring(0, end) + suffix
    }

    private fun vehicleLabel(id: String): String {
        return id.substringAfter(':')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }
            .ifBlank { id }
    }

    private fun formatRaceTime(ticks: Long): String {
        val totalCentiseconds = ticks.coerceAtLeast(0L) * 5L
        val minutes = totalCentiseconds / 6000L
        val seconds = (totalCentiseconds / 100L) % 60L
        val centiseconds = totalCentiseconds % 100L
        return String.format(Locale.ROOT, "%d:%02d.%02d", minutes, seconds, centiseconds)
    }

    private fun ordinal(value: Int): String {
        val suffix = if (value % 100 in 11..13) {
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

    private fun placeColor(place: Int): Int {
        return when (place) {
            1 -> 0xFFFFD76A.toInt()
            2 -> 0xFFDDE6F4.toInt()
            3 -> 0xFFFFB16A.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
    }
}
