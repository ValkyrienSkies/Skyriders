package org.valkyrienskies.skyriders.content.racing

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.item.RaceFlagItem

class RaceDangerBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(SkyridersMod.RACE_DANGER_BLOCK_ENTITY.get(), pos, state) {

    var colorId: Int = -1
        private set
    var radius: Double = DEFAULT_RADIUS
        private set

    fun setColor(colorRgb: Int) {
        colorId = colorRgb and 0xFFFFFF
        markDirtyAndSync()
    }

    fun adjustRadius(delta: Double): Double {
        radius = (radius + delta).coerceIn(MIN_RADIUS, MAX_RADIUS)
        markDirtyAndSync()
        return radius
    }

    override fun onLoad() {
        super.onLoad()
        (level as? ServerLevel)?.let { RaceManager.registerDanger(it, this) }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        colorId = if (tag.contains(COLOR_KEY)) RaceFlagItem.normalizeSavedRaceColor(tag.getInt(COLOR_KEY)) else -1
        radius = if (tag.contains(RADIUS_KEY)) tag.getDouble(RADIUS_KEY).coerceIn(MIN_RADIUS, MAX_RADIUS) else DEFAULT_RADIUS
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putInt(COLOR_KEY, colorId)
        tag.putDouble(RADIUS_KEY, radius)
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    fun describe(): Component {
        val color = if (colorId >= 0) RaceFlagItem.describeColor(colorId) else "none"
        return Component.literal("Race danger: radius=${radius.toInt()} color=$color")
    }

    private fun markDirtyAndSync() {
        setChanged()
        (level as? ServerLevel)?.let { RaceManager.registerDanger(it, this) }
        val level = level ?: return
        level.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    companion object {
        const val DEFAULT_RADIUS = 6.0
        const val MIN_RADIUS = 1.0
        const val MAX_RADIUS = 64.0
        private const val COLOR_KEY = "ColorId"
        private const val RADIUS_KEY = "Radius"

    }
}
