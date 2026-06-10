package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft

object RaceHudClientState {
    private var bodyId: Long = -1L
    private var place: Int = 0
    private var total: Int = 0
    private var lastUpdateTick: Long = -1000L

    fun update(active: Boolean, bodyId: Long, place: Int, total: Int) {
        val level = Minecraft.getInstance().level ?: return
        if (active && bodyId >= 0L && place > 0 && total > 0) {
            this.bodyId = bodyId
            this.place = place
            this.total = total
        } else {
            clear()
        }
        lastUpdateTick = level.gameTime
    }

    fun placementFor(vehicleBodyId: Long): RacePlacement? {
        val level = Minecraft.getInstance().level ?: return null
        if (vehicleBodyId != bodyId || level.gameTime - lastUpdateTick > 40L) return null
        return RacePlacement(place, total)
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
    }
}

data class RacePlacement(
    val place: Int,
    val total: Int
)
