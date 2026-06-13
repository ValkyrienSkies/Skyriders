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
    var checkpointLapMode: String = RaceCheckpointLapModes.ALL
        private set
    var checkpointLap: Int = 1
        private set
    var lapCount: Int = DEFAULT_LAP_COUNT
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

    fun decrementCheckpointIndex(): Int {
        checkpointIndex = (checkpointIndex - 1).coerceAtLeast(0)
        markDirtyAndSync()
        return checkpointIndex
    }

    fun cycleCheckpointLapRequirement(): String {
        when (checkpointLapMode) {
            RaceCheckpointLapModes.ALL -> {
                checkpointLapMode = RaceCheckpointLapModes.EXACT
                checkpointLap = 1
            }
            RaceCheckpointLapModes.EXACT -> {
                if (checkpointLap < MAX_CONFIGURABLE_LAP_COUNT) {
                    checkpointLap++
                } else {
                    checkpointLapMode = RaceCheckpointLapModes.FINAL
                }
            }
            RaceCheckpointLapModes.FINAL -> {
                checkpointLapMode = RaceCheckpointLapModes.PENULTIMATE
            }
            RaceCheckpointLapModes.PENULTIMATE -> {
                checkpointLapMode = RaceCheckpointLapModes.NOT_FINAL
            }
            RaceCheckpointLapModes.NOT_FINAL -> {
                checkpointLapMode = RaceCheckpointLapModes.NOT_PENULTIMATE
            }
            else -> {
                checkpointLapMode = RaceCheckpointLapModes.ALL
                checkpointLap = 1
            }
        }
        markDirtyAndSync()
        return checkpointLapRequirementLabel()
    }

    fun cycleLapCount(): Int {
        lapCount = if (lapCount >= MAX_CONFIGURABLE_LAP_COUNT) 1 else lapCount + 1
        markDirtyAndSync()
        return lapCount
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
        (level as? ServerLevel)?.let { RaceManager.registerMarker(it, this) }
    }

    override fun setRemoved() {
        // Chunk unloads also call setRemoved; actual block deletion unregisters from RaceMarkerBlock.onRemove.
        super.setRemoved()
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        endpointPos = if (tag.contains(ENDPOINT_KEY)) BlockPos.of(tag.getLong(ENDPOINT_KEY)) else null
        colorId = if (tag.contains(COLOR_KEY)) RaceFlagItem.normalizeSavedRaceColor(tag.getInt(COLOR_KEY)) else -1
        markerType = tag.getString(TYPE_KEY).takeIf { it == RaceMarkerTypes.START_FINISH || it == RaceMarkerTypes.CHECKPOINT }
            ?: RaceMarkerTypes.START_FINISH
        checkpointIndex = tag.getInt(CHECKPOINT_KEY).coerceIn(0, 99)
        checkpointLapMode = tag.getString(CHECKPOINT_LAP_MODE_KEY)
            .takeIf {
                    it == RaceCheckpointLapModes.ALL ||
                    it == RaceCheckpointLapModes.EXACT ||
                    it == RaceCheckpointLapModes.FINAL ||
                    it == RaceCheckpointLapModes.PENULTIMATE ||
                    it == RaceCheckpointLapModes.NOT_FINAL ||
                    it == RaceCheckpointLapModes.NOT_PENULTIMATE
            }
            ?: RaceCheckpointLapModes.ALL
        checkpointLap = if (tag.contains(CHECKPOINT_LAP_KEY)) tag.getInt(CHECKPOINT_LAP_KEY).coerceIn(1, MAX_LAP_COUNT) else 1
        lapCount = if (tag.contains(LAP_COUNT_KEY)) tag.getInt(LAP_COUNT_KEY).coerceIn(1, MAX_LAP_COUNT) else DEFAULT_LAP_COUNT
        powered = tag.getBoolean(POWERED_KEY)
        countdownTicks = tag.getInt(COUNTDOWN_KEY).coerceAtLeast(0)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        endpointPos?.let { tag.putLong(ENDPOINT_KEY, it.asLong()) }
        tag.putInt(COLOR_KEY, colorId)
        tag.putString(TYPE_KEY, markerType)
        tag.putInt(CHECKPOINT_KEY, checkpointIndex)
        tag.putString(CHECKPOINT_LAP_MODE_KEY, checkpointLapMode)
        tag.putInt(CHECKPOINT_LAP_KEY, checkpointLap)
        tag.putInt(LAP_COUNT_KEY, lapCount)
        tag.putBoolean(POWERED_KEY, powered)
        tag.putInt(COUNTDOWN_KEY, countdownTicks)
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): ClientboundBlockEntityDataPacket = ClientboundBlockEntityDataPacket.create(this)

    fun describe(): Component {
        val color = if (colorId >= 0) RaceFlagItem.describeColor(colorId) else "none"
        val endpoint = endpointPos?.let { "${it.x}, ${it.y}, ${it.z}" } ?: "unlinked"
        return Component.literal("Race marker: $markerType checkpoint=$checkpointIndex checkpoint_lap=${checkpointLapRequirementLabel()} laps=$lapCount color=$color endpoint=$endpoint")
    }

    fun checkpointLapRequirementLabel(): String {
        return when (checkpointLapMode) {
            RaceCheckpointLapModes.EXACT -> "lap $checkpointLap"
            RaceCheckpointLapModes.FINAL -> "final lap"
            RaceCheckpointLapModes.PENULTIMATE -> "second-to-last lap"
            RaceCheckpointLapModes.NOT_FINAL -> "not final lap"
            RaceCheckpointLapModes.NOT_PENULTIMATE -> "not second-to-last lap"
            else -> "all laps"
        }
    }

    private fun markDirtyAndSync() {
        setChanged()
        (level as? ServerLevel)?.let { RaceManager.registerMarker(it, this) }
        val level = level ?: return
        level.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    companion object {
        private const val ENDPOINT_KEY = "EndpointPos"
        private const val COLOR_KEY = "ColorId"
        private const val TYPE_KEY = "MarkerType"
        private const val CHECKPOINT_KEY = "CheckpointIndex"
        private const val CHECKPOINT_LAP_MODE_KEY = "CheckpointLapMode"
        private const val CHECKPOINT_LAP_KEY = "CheckpointLap"
        private const val LAP_COUNT_KEY = "LapCount"
        private const val POWERED_KEY = "Powered"
        private const val COUNTDOWN_KEY = "CountdownTicks"
        private const val DEFAULT_LAP_COUNT = 3
        private const val MAX_CONFIGURABLE_LAP_COUNT = 6
        private const val MAX_LAP_COUNT = 99

        fun serverTick(level: Level, pos: BlockPos, state: BlockState, blockEntity: RaceMarkerBlockEntity) {
            val serverLevel = level as? ServerLevel ?: return
            RaceManager.tickMarker(serverLevel, blockEntity)
        }
    }
}
