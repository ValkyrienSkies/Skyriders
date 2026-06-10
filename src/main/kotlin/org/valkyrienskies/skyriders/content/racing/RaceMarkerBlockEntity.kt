package org.valkyrienskies.skyriders.content.racing

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.item.RaceFlagItem

class RaceMarkerBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(SkyridersMod.RACE_MARKER_BLOCK_ENTITY.get(), pos, state) {
    var endpointPos: BlockPos? = null
        private set
    var colorId: Int = -1
        private set
    var markerType: String = RaceMarkerTypes.START_FINISH
        private set
    var checkpointIndex: Int = 0
        private set
    var powered: Boolean = false
        private set
    var countdownTicks: Int = 0

    fun setEndpoint(pos: BlockPos?) {
        endpointPos = pos
        markDirtyAndSync()
    }

    fun setColor(colorRgb: Int) {
        colorId = colorRgb and 0xFFFFFF
        markDirtyAndSync()
    }

    fun cycleType(): String {
        markerType = if (markerType == RaceMarkerTypes.START_FINISH) RaceMarkerTypes.CHECKPOINT else RaceMarkerTypes.START_FINISH
        markDirtyAndSync()
        return markerType
    }

    fun incrementCheckpointIndex(): Int {
        checkpointIndex = (checkpointIndex + 1).coerceAtMost(99)
        markDirtyAndSync()
        return checkpointIndex
    }

    fun setPoweredState(poweredNow: Boolean, level: ServerLevel) {
        val rising = poweredNow && !powered
        powered = poweredNow
        if (rising) {
            RaceManager.startCountdown(this, level)
        }
        markDirtyAndSync()
    }

    override fun onLoad() {
        super.onLoad()
        (level as? ServerLevel)?.let { RaceManager.registerMarker(it, blockPos) }
    }

    override fun setRemoved() {
        (level as? ServerLevel)?.let { RaceManager.unregisterMarker(it, blockPos) }
        super.setRemoved()
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        endpointPos = if (tag.contains(ENDPOINT_KEY)) BlockPos.of(tag.getLong(ENDPOINT_KEY)) else null
        colorId = if (tag.contains(COLOR_KEY)) tag.getInt(COLOR_KEY) else -1
        markerType = tag.getString(TYPE_KEY).takeIf { it == RaceMarkerTypes.START_FINISH || it == RaceMarkerTypes.CHECKPOINT }
            ?: RaceMarkerTypes.START_FINISH
        checkpointIndex = tag.getInt(CHECKPOINT_KEY).coerceIn(0, 99)
        powered = tag.getBoolean(POWERED_KEY)
        countdownTicks = tag.getInt(COUNTDOWN_KEY).coerceAtLeast(0)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        endpointPos?.let { tag.putLong(ENDPOINT_KEY, it.asLong()) }
        tag.putInt(COLOR_KEY, colorId)
        tag.putString(TYPE_KEY, markerType)
        tag.putInt(CHECKPOINT_KEY, checkpointIndex)
        tag.putBoolean(POWERED_KEY, powered)
        tag.putInt(COUNTDOWN_KEY, countdownTicks)
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    fun describe(): Component {
        val color = if (colorId >= 0) RaceFlagItem.describeColor(colorId) else "none"
        val endpoint = endpointPos?.let { "${it.x}, ${it.y}, ${it.z}" } ?: "unlinked"
        return Component.literal("Race marker: $markerType checkpoint=$checkpointIndex color=$color endpoint=$endpoint")
    }

    private fun markDirtyAndSync() {
        setChanged()
        val level = level ?: return
        level.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    companion object {
        private const val ENDPOINT_KEY = "EndpointPos"
        private const val COLOR_KEY = "ColorId"
        private const val TYPE_KEY = "MarkerType"
        private const val CHECKPOINT_KEY = "CheckpointIndex"
        private const val POWERED_KEY = "Powered"
        private const val COUNTDOWN_KEY = "CountdownTicks"

        fun serverTick(level: Level, pos: BlockPos, state: BlockState, blockEntity: RaceMarkerBlockEntity) {
            val serverLevel = level as? ServerLevel ?: return
            RaceManager.tickMarker(serverLevel, blockEntity)
        }
    }
}
