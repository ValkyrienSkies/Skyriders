package org.valkyrienskies.skyriders.content.racing

import net.minecraft.core.RegistryAccess
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.CraftingContainer
import net.minecraft.world.item.DyeItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingBookCategory
import net.minecraft.world.item.crafting.CustomRecipe
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.level.Level
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.item.RaceFlagItem

class RaceFlagColoringRecipe(id: ResourceLocation, category: CraftingBookCategory) : CustomRecipe(id, category) {
    override fun matches(container: CraftingContainer, level: Level): Boolean {
        val parsed = parse(container)
        return parsed.canDye || parsed.canDuplicate
    }

    override fun assemble(container: CraftingContainer, registryAccess: RegistryAccess): ItemStack {
        val parsed = parse(container)
        return when {
            parsed.canDye -> ItemStack(SkyridersMod.RACE_FLAG.get()).also {
                RaceFlagItem.setColor(it, mixColors(parsed.flags.first(), parsed.dyes))
            }
            parsed.canDuplicate -> ItemStack(SkyridersMod.RACE_FLAG.get(), 2).also {
                RaceFlagItem.setColor(it, RaceFlagItem.getColor(parsed.flags.first()))
            }
            else -> ItemStack.EMPTY
        }
    }

    override fun canCraftInDimensions(width: Int, height: Int): Boolean = width * height >= 2

    override fun getSerializer(): RecipeSerializer<*> = SkyridersMod.RACE_FLAG_COLORING_RECIPE_SERIALIZER.get()

    private fun parse(container: CraftingContainer): ParsedRecipe {
        val flags = ArrayList<ItemStack>()
        val dyes = ArrayList<DyeItem>()
        for (slot in 0 until container.containerSize) {
            val stack = container.getItem(slot)
            if (stack.isEmpty) continue
            val item = stack.item
            when {
                item == SkyridersMod.RACE_FLAG.get() -> flags.add(stack)
                item is DyeItem -> dyes.add(item)
                else -> return ParsedRecipe.invalid()
            }
        }
        return ParsedRecipe(flags, dyes)
    }

    private fun mixColors(flag: ItemStack, dyes: List<DyeItem>): Int {
        var redTotal = 0
        var greenTotal = 0
        var blueTotal = 0
        var brightnessTotal = 0
        var colorCount = 0

        fun addColor(colorRgb: Int) {
            val red = colorRgb shr 16 and 0xFF
            val green = colorRgb shr 8 and 0xFF
            val blue = colorRgb and 0xFF
            redTotal += red
            greenTotal += green
            blueTotal += blue
            brightnessTotal += maxOf(red, green, blue)
            colorCount++
        }

        addColor(RaceFlagItem.getColor(flag))
        dyes.forEach { dye -> addColor(RaceFlagItem.getDyeColorRgb(dye.dyeColor)) }

        if (colorCount <= 0) return RaceFlagItem.DEFAULT_COLOR_RGB
        var red = redTotal / colorCount
        var green = greenTotal / colorCount
        var blue = blueTotal / colorCount
        val averageBrightness = brightnessTotal.toFloat() / colorCount.toFloat()
        val maxChannel = maxOf(red, green, blue).toFloat()
        if (maxChannel > 0.0f) {
            red = (red * averageBrightness / maxChannel).toInt()
            green = (green * averageBrightness / maxChannel).toInt()
            blue = (blue * averageBrightness / maxChannel).toInt()
        }
        return (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
    }

    private data class ParsedRecipe(
        val flags: List<ItemStack>,
        val dyes: List<DyeItem>,
        val invalid: Boolean = false
    ) {
        val canDye: Boolean = !invalid && flags.size == 1 && dyes.isNotEmpty()
        val canDuplicate: Boolean = !invalid && flags.size == 2 && dyes.isEmpty()

        companion object {
            fun invalid(): ParsedRecipe = ParsedRecipe(emptyList(), emptyList(), invalid = true)
        }
    }
}
