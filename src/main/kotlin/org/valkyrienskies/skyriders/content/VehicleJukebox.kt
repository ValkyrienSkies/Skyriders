package org.valkyrienskies.skyriders.content

import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.RecordItem

object VehicleJukebox {
    const val PART_ID = "portable_jukebox"
    private const val DISC_KEY = "disc"

    fun partDefinition(): VehiclePartDefinition = VehiclePartDefinition(
        id = PART_ID,
        type = VehiclePartTypes.JUKEBOX
    )

    fun hasJukebox(vehicle: IVehicle): Boolean {
        return vehicle.vehicleDefinition.parts.any { part -> part.id == PART_ID && part.type == VehiclePartTypes.JUKEBOX }
    }

    fun discStack(vehicle: IVehicle): ItemStack {
        val tag = vehicle.vehicleState.partStates[PART_ID]?.data ?: return ItemStack.EMPTY
        if (!tag.contains(DISC_KEY)) return ItemStack.EMPTY
        return ItemStack.of(tag.getCompound(DISC_KEY))
    }

    fun discSound(stack: ItemStack): SoundEvent? {
        return (stack.item as? RecordItem)?.sound
    }

    fun discIdentity(stack: ItemStack): String {
        if (stack.isEmpty) return ""
        return stack.save(CompoundTag()).toString()
    }

    fun tryInsertHeldDisc(player: ServerPlayer, vehicle: IVehicle): Boolean {
        if (!hasJukebox(vehicle)) return false
        val hand = InteractionHand.entries.firstOrNull { hand -> player.getItemInHand(hand).item is RecordItem }
            ?: return false
        val heldStack = player.getItemInHand(hand)
        if (!discStack(vehicle).isEmpty) {
            player.displayClientMessage(Component.literal("The truck jukebox already has a disc."), true)
            return true
        }

        val level = player.level() as? ServerLevel ?: return false
        val storedDisc = heldStack.copyWithCount(1)
        val changed = VehicleManager.mutatePartState(level, vehicle.bodyId, PART_ID) { state ->
            state.put(DISC_KEY, storedDisc.save(CompoundTag()))
        }
        if (!changed) return false

        if (!player.abilities.instabuild) {
            heldStack.shrink(1)
        }
        player.displayClientMessage(
            Component.literal("Playing ${storedDisc.hoverName.string} from the truck.").withStyle(ChatFormatting.AQUA),
            true
        )
        return true
    }

    fun tryRemoveDisc(player: ServerPlayer, vehicle: IVehicle): Boolean {
        if (!hasJukebox(vehicle)) return false
        val disc = discStack(vehicle)
        if (disc.isEmpty) {
            player.displayClientMessage(Component.literal("The truck jukebox is empty."), true)
            return true
        }

        val level = player.level() as? ServerLevel ?: return false
        val removed = disc.copyWithCount(1)
        val removedName = removed.hoverName.string
        val changed = VehicleManager.mutatePartState(level, vehicle.bodyId, PART_ID) { state ->
            state.remove(DISC_KEY)
        }
        if (!changed) return false

        if (!player.inventory.add(removed)) {
            player.drop(removed, false)
        }
        player.displayClientMessage(
            Component.literal("Removed $removedName from the truck.").withStyle(ChatFormatting.YELLOW),
            true
        )
        return true
    }
}
