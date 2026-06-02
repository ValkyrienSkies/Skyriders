package org.valkyrienskies.skyriders.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.skyriders.network.SkyridersNetwork

object BikeLifecycle {
    private val pendingRestoreLevels = mutableSetOf<ServerLevel>()

    @SubscribeEvent
    fun onLevelLoad(event: LevelEvent.Load) {
        val level = event.level as? ServerLevel ?: return
        pendingRestoreLevels.add(level)
    }

    @SubscribeEvent
    fun onLevelSave(event: LevelEvent.Save) {
        val level = event.level as? ServerLevel ?: return
        BikeSavedData.get(level).replaceFromManager(level)
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        if (pendingRestoreLevels.isNotEmpty()) {
            val iterator = pendingRestoreLevels.iterator()
            while (iterator.hasNext()) {
                val level = iterator.next()
                VehicleManager.restoreVehicles(level, BikeSavedData.get(level).records)
                syncLevel(level)
                iterator.remove()
            }
        }

        event.server.allLevels.forEach(::syncVisualState)
    }

    @SubscribeEvent
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        syncPlayer(player)
    }

    @SubscribeEvent
    fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        val player = event.entity as? ServerPlayer ?: return
        syncPlayer(player)
    }

    fun saveLevel(level: ServerLevel) {
        BikeSavedData.get(level).replaceFromManager(level)
    }

    fun syncLevel(level: ServerLevel) {
        val records = VehicleManager.getSaveRecords(level.dimensionId)
        level.players().forEach { player -> SkyridersNetwork.sendVehicleSync(player, records) }
    }

    fun syncPlayer(player: ServerPlayer) {
        val records = VehicleManager.getSaveRecords(player.level().dimensionId)
        SkyridersNetwork.sendVehicleSync(player, records)
    }

    private fun syncVisualState(level: ServerLevel) {
        val bikes = BikeManager.getBikes(level.dimensionId)
        if (bikes.isEmpty()) return
        level.players().forEach { player -> SkyridersNetwork.sendBikeVisualState(player, bikes) }
    }
}
