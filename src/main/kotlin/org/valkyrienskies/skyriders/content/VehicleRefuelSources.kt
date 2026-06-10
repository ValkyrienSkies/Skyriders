package org.valkyrienskies.skyriders.content

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import org.valkyrienskies.skyriders.SkyridersMod
import java.util.concurrent.CopyOnWriteArrayList

fun interface ExternalVehicleRefuelSource {
    fun canRefuel(player: Player, vehicle: IVehicle?, zone: VehicleInteractionZone?): Boolean
}

object VehicleRefuelSources {
    val JERRY_CAN_TAG: TagKey<Item> = TagKey.create(
        Registries.ITEM,
        ResourceLocation(SkyridersMod.MOD_ID, "jerry_can")
    )
    private val externalSources = CopyOnWriteArrayList<ExternalVehicleRefuelSource>()

    fun canRefuelFromHeldItem(player: Player): Boolean {
        return isJerryCan(player.mainHandItem) || isJerryCan(player.offhandItem)
    }

    fun hasActiveRefuelSource(player: Player, vehicle: IVehicle? = null, zone: VehicleInteractionZone? = null): Boolean {
        return canRefuelFromHeldItem(player) || hasExternalRefuelSource(player, vehicle, zone)
    }

    fun registerExternalSource(source: ExternalVehicleRefuelSource) {
        externalSources += source
    }

    fun isJerryCan(stack: ItemStack): Boolean {
        return !stack.isEmpty && stack.`is`(JERRY_CAN_TAG)
    }

    private fun hasExternalRefuelSource(player: Player, vehicle: IVehicle?, zone: VehicleInteractionZone?): Boolean {
        return externalSources.any { it.canRefuel(player, vehicle, zone) }
    }
}
