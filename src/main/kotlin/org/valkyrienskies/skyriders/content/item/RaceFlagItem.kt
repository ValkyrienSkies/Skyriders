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
        const val DEFAULT_COLOR_RGB = 0xB02E26
        private const val COLOR_RGB_KEY = "RaceFlagColorRgb"
        private const val LEGACY_COLOR_KEY = "RaceFlagColor"
        val DYE_RGB = intArrayOf(
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

        fun getColor(stack: ItemStack): Int {
            val tag = stack.tag
            if (tag?.contains(COLOR_RGB_KEY) == true) {
                return tag.getInt(COLOR_RGB_KEY) and 0xFFFFFF
            }
            if (tag?.contains(LEGACY_COLOR_KEY) == true) {
                return getDyeColorRgb(DyeColor.byId(tag.getInt(LEGACY_COLOR_KEY)))
            }
            return DEFAULT_COLOR_RGB
        }

        fun setColor(stack: ItemStack, color: DyeColor) {
            setColor(stack, getDyeColorRgb(color))
        }

        fun setColor(stack: ItemStack, colorRgb: Int) {
            stack.orCreateTag.putInt(COLOR_RGB_KEY, colorRgb and 0xFFFFFF)
            stack.orCreateTag.remove(LEGACY_COLOR_KEY)
        }

        fun getColorRgb(stack: ItemStack): Int {
            return getColor(stack)
        }

        fun getDyeColorRgb(color: DyeColor): Int {
            return DYE_RGB.getOrElse(color.id) { DYE_RGB[DyeColor.RED.id] }
        }

        fun describeColor(colorRgb: Int): String {
            return "#%06X".format(colorRgb and 0xFFFFFF)
        }
    }
}
