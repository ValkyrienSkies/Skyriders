package org.valkyrienskies.skyriders.content.item

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.entity.player.Player
import org.valkyrienskies.skyriders.content.BikeLifecycle
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.VehicleRaceParticipants

class RaceFlagItem(properties: Properties) : RacingVehicleItem(properties) {
    override fun useOnVehicle(level: ServerLevel, player: Player, vehicle: IVehicle, stack: ItemStack): Boolean {
        applyToVehicle(level, player, vehicle, stack)
        return true
    }

    fun applyToVehicle(level: ServerLevel, player: Player, vehicle: IVehicle, stack: ItemStack) {
        VehicleRaceParticipants.mark(vehicle, getColor(stack))
        BikeLifecycle.saveLevel(level)
        BikeLifecycle.syncLevel(level)
        playItemUseSound(level, player)
    }

    companion object {
        const val FLAG_CLOTH_TINT_INDEX = 0
        private const val COLOR_KEY = "RaceFlagColor"
        private val DYE_RGB = intArrayOf(
            0xF9FFFE,
            0xF9801D,
            0xC74EBD,
            0x3AB3DA,
            0xFED83D,
            0x80C71F,
            0xF38BAA,
            0x474F52,
            0x9D9D97,
            0x169C9C,
            0x8932B8,
            0x3C44AA,
            0x835432,
            0x5E7C16,
            0xB02E26,
            0x1D1D21
        )

        fun getColor(stack: ItemStack): DyeColor {
            val id = stack.tag?.getInt(COLOR_KEY) ?: DyeColor.RED.id
            return DyeColor.byId(id)
        }

        fun setColor(stack: ItemStack, color: DyeColor) {
            stack.orCreateTag.putInt(COLOR_KEY, color.id)
        }

        fun getColorRgb(stack: ItemStack): Int {
            return getColorRgb(getColor(stack))
        }

        fun getColorRgb(color: DyeColor): Int {
            return DYE_RGB.getOrElse(color.id) { DYE_RGB[DyeColor.RED.id] }
        }
    }
}
