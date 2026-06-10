package org.valkyrienskies.skyriders.content.racing

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.skyriders.SkyridersMod

class RaceEndpointBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(SkyridersMod.RACE_ENDPOINT_BLOCK_ENTITY.get(), pos, state) {
    var markerPos: BlockPos? = null
        private set

    fun setMarker(pos: BlockPos?) {
        markerPos = pos
        markDirtyAndSync()
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        markerPos = if (tag.contains(MARKER_KEY)) BlockPos.of(tag.getLong(MARKER_KEY)) else null
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        markerPos?.let { tag.putLong(MARKER_KEY, it.asLong()) }
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    override fun setRemoved() {
        val serverLevel = level as? ServerLevel
        val marker = markerPos?.let { serverLevel?.getBlockEntity(it) as? RaceMarkerBlockEntity }
        if (marker?.endpointPos == blockPos) {
            marker.setEndpoint(null)
        }
        super.setRemoved()
    }

    private fun markDirtyAndSync() {
        setChanged()
        val level = level ?: return
        level.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    companion object {
        private const val MARKER_KEY = "MarkerPos"
    }
}
