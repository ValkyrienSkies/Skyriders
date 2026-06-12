package org.valkyrienskies.skyriders.content.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.RecordItem
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import net.minecraftforge.registries.ForgeRegistries
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.SkyridersSounds

class FasterThanValkyrieDisc : RecordItem {
    constructor(properties: Properties) : super(
        7,
        SkyridersSounds.RACE_MUSIC_FTAV,
        properties,
        174
    )

    override fun appendHoverText(
        stack: ItemStack,
        level: Level?,
        tooltipComponents: MutableList<Component>,
        isAdvanced: TooltipFlag
    ) {
        tooltipComponents.add(this.displayName.withStyle(ChatFormatting.GRAY))
    }

    override fun getDescriptionId(): String {
        return "item.${SkyridersMod.MOD_ID}.fasterthanavalkyrie_disc"
    }
}
