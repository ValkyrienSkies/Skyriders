package org.valkyrienskies.skyriders.content

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
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
    val LOW_FUEL_RESCUE_TAG: TagKey<Item> = TagKey.create(
        Registries.ITEM,
        ResourceLocation(SkyridersMod.MOD_ID, "low_fuel_rescue")
    )
    private val externalSources = CopyOnWriteArrayList<ExternalVehicleRefuelSource>()

    fun canRefuelFromHeldItem(player: Player): Boolean {
        return isJerryCan(player.mainHandItem) || isJerryCan(player.offhandItem)
    }

    fun consumeHeldRefuelSource(player: Player): Boolean {
        val hand = InteractionHand.entries.firstOrNull { hand -> isJerryCan(player.getItemInHand(hand)) } ?: return false
        val stack = player.getItemInHand(hand)
        if (stack.item == SkyridersMod.CREATIVE_JERRY_CAN.get()) return true
        if (player.abilities.instabuild) return true

        if (stack.`is`(Items.HONEY_BOTTLE)) {
            consumeHoneyBottle(player, hand, stack)
            return true
        }

        stack.shrink(1)
        return true
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

    private fun consumeHoneyBottle(player: Player, hand: InteractionHand, stack: ItemStack) {
        val emptyBottle = ItemStack(Items.GLASS_BOTTLE)
        if (stack.count == 1) {
            player.setItemInHand(hand, emptyBottle)
            return
        }

        stack.shrink(1)
        if (!player.inventory.add(emptyBottle)) {
            player.drop(emptyBottle, false)
        }
    }

    private fun hasExternalRefuelSource(player: Player, vehicle: IVehicle?, zone: VehicleInteractionZone?): Boolean {
        return externalSources.any { it.canRefuel(player, vehicle, zone) }
    }
}
