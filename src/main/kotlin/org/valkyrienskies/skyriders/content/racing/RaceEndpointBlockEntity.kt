package org.valkyrienskies.skyriders.content.racing

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.skyriders.SkyridersMod

class RaceEndpointBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(SkyridersMod.RACE_ENDPOINT_BLOCK_ENTITY.get(), pos, state) {
    var markerPos: BlockPos? = null
        private set
    var pulseTicks: Int = 0
        private set

    fun setMarker(pos: BlockPos?) {
        markerPos = pos
        markDirtyAndSync()
    }

    fun pulse(ticks: Int = DEFAULT_PULSE_TICKS) {
        pulseTicks = ticks.coerceAtLeast(1)
        markDirtyAndNotifyNeighbors()
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        markerPos = if (tag.contains(MARKER_KEY)) BlockPos.of(tag.getLong(MARKER_KEY)) else null
        pulseTicks = tag.getInt(PULSE_KEY).coerceAtLeast(0)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        markerPos?.let { tag.putLong(MARKER_KEY, it.asLong()) }
        if (pulseTicks > 0) tag.putInt(PULSE_KEY, pulseTicks)
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    private fun markDirtyAndSync() {
        setChanged()
        val level = level ?: return
        level.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    private fun markDirtyAndNotifyNeighbors() {
        markDirtyAndSync()
        val level = level ?: return
        level.updateNeighborsAt(blockPos, blockState.block)
        level.updateNeighbourForOutputSignal(blockPos, blockState.block)
    }

    companion object {
        private const val MARKER_KEY = "MarkerPos"
        private const val PULSE_KEY = "PulseTicks"
        private const val DEFAULT_PULSE_TICKS = 8

        fun serverTick(level: Level, pos: BlockPos, state: BlockState, blockEntity: RaceEndpointBlockEntity) {
            if (blockEntity.pulseTicks <= 0) return
            blockEntity.pulseTicks--
            if (blockEntity.pulseTicks == 0) {
                blockEntity.markDirtyAndNotifyNeighbors()
            } else {
                blockEntity.setChanged()
            }
        }
    }
}
