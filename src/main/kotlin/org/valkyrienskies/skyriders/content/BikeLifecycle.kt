package org.valkyrienskies.skyriders.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
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
        if (level in pendingRestoreLevels) return
        BikeSavedData.get(level).replaceFromManager(level)
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || pendingRestoreLevels.isEmpty()) return

        val iterator = pendingRestoreLevels.iterator()
        while (iterator.hasNext()) {
            val level = iterator.next()
            val records = BikeSavedData.get(level).records
            if (records.isNotEmpty() && !canRestoreBikes(level, records)) continue

            val restoredCount = BikeManager.restoreBikes(level, records)
            if (restoredCount < records.size) continue

            syncLevel(level)
            iterator.remove()
        }
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
        val records = BikeManager.getBikes(level.dimensionId).map(IBike::toSaveRecord)
        level.players().forEach { player -> SkyridersNetwork.sendBikeSync(player, records) }
    }

    fun syncPlayer(player: ServerPlayer) {
        val records = BikeManager.getBikes(player.level().dimensionId).map(IBike::toSaveRecord)
        SkyridersNetwork.sendBikeSync(player, records)
    }

    private fun canRestoreBikes(level: ServerLevel, records: Iterable<BikeSaveRecord>): Boolean {
        val bodies = level.shipWorld?.allBodies ?: return false
        return records.all { record -> bodies.getById(record.bodyId) != null }
    }
}
