package org.valkyrienskies.skyriders.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation

data class HudTextureRegion(
    val u: Int,
    val v: Int,
    val width: Int,
    val height: Int
)

data class HudAtlasCell(
    val row: Int,
    val column: Int
)

data class HudCharacterAtlas(
    val texture: ResourceLocation,
    val textureWidth: Int,
    val textureHeight: Int,
    val originX: Int,
    val originY: Int,
    val charWidth: Int,
    val charHeight: Int,
    val columnSpacing: Int,
    val rowSpacing: Int,
    val cells: Map<Char, HudAtlasCell>
) {
    fun renderText(guiGraphics: GuiGraphics, x: Int, y: Int, text: String, maxLength: Int = Int.MAX_VALUE, characterSpacing: Int = 1) {
        var drawX = x
        text.take(maxLength).forEach { character ->
            if (character == ' ') {
                drawX += charWidth + characterSpacing
                return@forEach
            }
            val cell = cells[character] ?: cells[character.lowercaseChar()] ?: return@forEach
            val region = HudTextureRegion(
                u = originX + cell.column * (charWidth + columnSpacing),
                v = originY + cell.row * (charHeight + rowSpacing),
                width = charWidth,
                height = charHeight
            )
            guiGraphics.blitRegion(texture, region, drawX, y, textureWidth, textureHeight)
            drawX += charWidth + characterSpacing
        }
    }

    companion object {
        fun fromRows(
            texture: ResourceLocation,
            textureWidth: Int,
            textureHeight: Int,
            originX: Int,
            originY: Int,
            charWidth: Int,
            charHeight: Int,
            columnSpacing: Int,
            rowSpacing: Int,
            rows: List<String>
        ): HudCharacterAtlas {
            val cells = rows.flatMapIndexed { row, characters ->
                characters.mapIndexed { column, character -> character to HudAtlasCell(row, column) }
            }.toMap()
            return HudCharacterAtlas(texture, textureWidth, textureHeight, originX, originY, charWidth, charHeight, columnSpacing, rowSpacing, cells)
        }
    }
}

data class HudAtlasCounterWidget(
    val x: Int,
    val y: Int,
    val maxLength: Int,
    val atlas: HudCharacterAtlas
) {
    fun render(guiGraphics: GuiGraphics, parentX: Int, parentY: Int, value: String) {
        atlas.renderText(guiGraphics, parentX + x, parentY + y, value.take(maxLength), maxLength = maxLength, characterSpacing = 1)
    }
}

fun GuiGraphics.blitRegion(
    texture: ResourceLocation,
    region: HudTextureRegion,
    x: Int,
    y: Int,
    textureWidth: Int,
    textureHeight: Int
) {
    blit(texture, x, y, region.u.toFloat(), region.v.toFloat(), region.width, region.height, textureWidth, textureHeight)
}
