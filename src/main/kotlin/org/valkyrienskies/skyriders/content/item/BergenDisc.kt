package org.valkyrienskies.skyriders.content.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Rarity
import net.minecraft.world.item.RecordItem
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import net.minecraftforge.registries.ForgeRegistries
import org.valkyrienskies.skyriders.SkyridersMod

class BergenDisc : RecordItem {
    constructor(properties: Properties) : super(
        7,
        ForgeRegistries.SOUND_EVENTS.getValue(BURGEN_TRUCK),
        properties,
        32
    )

    override fun appendHoverText(
        stack: ItemStack,
        level: Level?,
        tooltipComponents: MutableList<Component>,
        isAdvanced: TooltipFlag
    ) {
        tooltipComponents.add(this.displayName.withStyle(ChatFormatting.GRAY))
        tooltipComponents.add(this.secondDisplay.withStyle(ChatFormatting.DARK_GRAY))
    }

    override fun getDescriptionId(): String {
        return "item.${SkyridersMod.MOD_ID}.bergen_disc"
    }

    private val secondDisplay = Component.translatable(this.descriptionId + ".desc.second")

    companion object {
        val BURGEN_TRUCK = ResourceLocation(SkyridersMod.MOD_ID, "bergen_disc")
    }
}
