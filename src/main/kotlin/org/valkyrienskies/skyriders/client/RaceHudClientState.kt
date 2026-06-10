package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft

object RaceHudClientState {
    private var bodyId: Long = -1L
    private var place: Int = 0
    private var total: Int = 0
    private var lap: Int = 0
    private var totalLaps: Int = 0
    private var lapElapsedTicks: Long = 0L
    private var lastUpdateTick: Long = -1000L

    fun update(active: Boolean, bodyId: Long, place: Int, total: Int, lap: Int, totalLaps: Int, lapElapsedTicks: Long) {
        val level = Minecraft.getInstance().level ?: return
        if (active && bodyId >= 0L && place > 0 && total > 0 && lap > 0 && totalLaps > 0) {
            this.bodyId = bodyId
            this.place = place
            this.total = total
            this.lap = lap
            this.totalLaps = totalLaps
            this.lapElapsedTicks = lapElapsedTicks.coerceAtLeast(0L)
        } else {
            clear()
        }
        lastUpdateTick = level.gameTime
    }

    fun placementFor(vehicleBodyId: Long): RacePlacement? {
        val level = Minecraft.getInstance().level ?: return null
        if (vehicleBodyId != bodyId || level.gameTime - lastUpdateTick > 40L) return null
        val elapsed = lapElapsedTicks + (level.gameTime - lastUpdateTick).coerceAtLeast(0L)
        return RacePlacement(place, total, lap, totalLaps, elapsed)
    }

    fun tick() {
        val level = Minecraft.getInstance().level ?: return
        if (level.gameTime - lastUpdateTick > 40L) {
            clear()
        }
    }

    fun clear() {
        bodyId = -1L
        place = 0
        total = 0
        lap = 0
        totalLaps = 0
        lapElapsedTicks = 0L
    }
}

data class RacePlacement(
    val place: Int,
    val total: Int,
    val lap: Int,
    val totalLaps: Int,
    val lapElapsedTicks: Long
)
