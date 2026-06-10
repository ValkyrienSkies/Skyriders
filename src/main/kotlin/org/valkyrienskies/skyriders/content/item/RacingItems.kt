package org.valkyrienskies.skyriders.content.item

import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.SkyridersSounds
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity

class HoneyCanisterItem(properties: Properties) : RacingVehicleItem(properties) {
    override fun useOnVehicle(level: ServerLevel, player: Player, vehicle: IVehicle, stack: ItemStack): Boolean {
        playItemUseSound(level, player)
        VehicleStatusEffects.applyBoost(vehicle, duration = 0.95, acceleration = 36.0, targetSpeed = 34.0, fadeRange = 8.0)
        return true
    }
}

class ThunderboltItem(properties: Properties) : RacingVehicleItem(properties) {
    override fun useOnVehicle(level: ServerLevel, player: Player, vehicle: IVehicle, stack: ItemStack): Boolean {
        val sourceBody = level.shipWorld?.allBodies?.getById(vehicle.bodyId) ?: return false
        val sourcePosition = sourceBody.kinematics.position
        playThunderboltSound(level, player)

        VehicleManager.getVehicles(level).forEach { target ->
            if (target.bodyId == vehicle.bodyId) return@forEach
            val targetBody = level.shipWorld?.allBodies?.getById(target.bodyId) ?: return@forEach
            if (targetBody.kinematics.position.distanceSquared(sourcePosition) > RADIUS * RADIUS) return@forEach
            VehicleStatusEffects.applySpinOut(target, duration = 2.15, yawSpeed = 12.5)
        }
        return true
    }

    private fun playThunderboltSound(level: ServerLevel, player: Player) {
        level.playSound(
            null,
            player.x,
            player.y,
            player.z,
            SkyridersSounds.THUNDERBOLT_SOUND.get(),
            SoundSource.PLAYERS,
            0.9f,
            1.0f
        )
    }

    private companion object {
        const val RADIUS = 10.0
    }
}

abstract class RacingVehicleItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true)
        }

        val serverLevel = level as? ServerLevel ?: return InteractionResultHolder.fail(stack)
        val vehicle = playerDriverVehicle(player) ?: return InteractionResultHolder.fail(stack)
        if (!useOnVehicle(serverLevel, player, vehicle, stack)) {
            return InteractionResultHolder.fail(stack)
        }

        if (!player.abilities.instabuild) {
            stack.shrink(1)
        }
        return InteractionResultHolder.success(stack)
    }

    protected abstract fun useOnVehicle(level: ServerLevel, player: Player, vehicle: IVehicle, stack: ItemStack): Boolean

    protected fun playItemUseSound(level: ServerLevel, player: Player) {
        level.playSound(
            null,
            player.x,
            player.y,
            player.z,
            SkyridersSounds.RACING_ITEM_USE_SOUND.get(),
            SoundSource.PLAYERS,
            0.65f,
            1.0f
        )
    }

    private fun playerDriverVehicle(player: Player): IVehicle? {
        val seat = player.vehicle as? BikeSeatEntity ?: return null
        if (!seat.isDriverSeat()) return null
        return VehicleManager.getVehicle(player.level().dimensionId, seat.bodyId)
    }
}
