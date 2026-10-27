package org.valkyrienskies.skyriders.content

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.skyriders.network.SkyridersNetwork

object VehicleInteractionHandler {
    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        BikeInteractionHandler.releaseOnExit(event.entity.uuid)
    }

    @SubscribeEvent
    fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        BikeInteractionHandler.releaseOnExit(event.entity.uuid)
        (event.entity as? ServerPlayer)?.let { SkyridersNetwork.sendBikeHoistState(it, false) }
    }

    @SubscribeEvent
    fun onPlayerTick(event: TickEvent.PlayerTickEvent) {
        BikeInteractionHandler.onPlayerTick(event)
    }

    fun handleUse(player: ServerPlayer) {
        BikeInteractionHandler.handleUse(player)
    }

    fun mountVehicle(
        player: ServerPlayer,
        vehicle: IVehicle,
        notifyPlayer: Boolean,
        interactionZoneId: String = VehicleInteractionDefinition.SEAT
    ): Boolean {
        return BikeInteractionHandler.mountVehicle(player, vehicle, notifyPlayer, interactionZoneId)
    }

    fun findVehicleInLook(player: Player, range: Double = 5.0): BikeInteractionHandler.VehicleInteractionHit? {
        return BikeInteractionHandler.findBikeInLook(player, range)
    }

    fun findVehicleOnRay(
        level: net.minecraft.world.level.Level,
        start: Vec3,
        end: Vec3
    ): BikeInteractionHandler.VehicleInteractionHit? {
        return BikeInteractionHandler.findBikeOnRay(level, start, end)
    }

    fun physTick(physLevel: PhysLevel, dt: Double) {
        BikeInteractionHandler.physTick(physLevel, dt)
    }
}
