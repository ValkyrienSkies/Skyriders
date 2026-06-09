package org.valkyrienskies.skyriders.content

import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.nbt.CompoundTag
import java.util.concurrent.ConcurrentHashMap

fun interface VehiclePartInteractionHandler {
    fun handle(player: ServerPlayer, vehicle: IVehicle, zone: VehicleInteractionZone, action: ResourceLocation): Boolean
}

object VehiclePartInteractionHandlers {
    private val handlers = ConcurrentHashMap<ResourceLocation, VehiclePartInteractionHandler>()

    init {
        register(VehicleInteractionActions.TOGGLE) { player, vehicle, zone, _ ->
            setOpenState(player, vehicle, zone, null)
        }
        register(VehicleInteractionActions.OPEN) { player, vehicle, zone, _ ->
            setOpenState(player, vehicle, zone, true)
        }
        register(VehicleInteractionActions.CLOSE) { player, vehicle, zone, _ ->
            setOpenState(player, vehicle, zone, false)
        }
        register(VehicleInteractionActions.OPEN_DOOR) { player, vehicle, zone, _ ->
            setOpenState(player, vehicle, zone, null)
        }
    }

    fun register(action: ResourceLocation, handler: VehiclePartInteractionHandler) {
        handlers[action] = handler
    }

    fun handle(player: ServerPlayer, vehicle: IVehicle, zone: VehicleInteractionZone): Boolean {
        if (zone.partId == null) return false
        return zone.actions.any { action ->
            handlers[action]?.handle(player, vehicle, zone, action) == true
        }
    }

    private fun setOpenState(
        player: ServerPlayer,
        vehicle: IVehicle,
        zone: VehicleInteractionZone,
        forcedOpen: Boolean?
    ): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        val partId = zone.partId ?: return false
        var open = false
        val changed = VehicleManager.mutatePartState(level, vehicle.bodyId, partId) { state: CompoundTag ->
            val nextOpen = forcedOpen ?: !state.getBoolean("open")
            state.putBoolean("open", nextOpen)
            open = nextOpen
        }
        if (changed) {
            player.sendSystemMessage(Component.literal("${vehicle.vehicleDefinition.displayName} $partId ${if (open) "opened" else "closed"}"))
        }
        return changed
    }
}
