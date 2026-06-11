package org.valkyrienskies.skyriders.content.racing

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class RaceSavedData : SavedData() {
    val markers: MutableMap<Long, RaceMarkerSnapshot> = LinkedHashMap()
    val leaderboard: MutableList<RaceLeaderboardEntry> = mutableListOf()

    override fun save(tag: CompoundTag): CompoundTag {
        val markerList = ListTag()
        markers.values.map(RaceMarkerSnapshot::save).forEach(markerList::add)
        tag.put(MARKERS_KEY, markerList)

        val leaderboardList = ListTag()
        leaderboard.map(RaceLeaderboardEntry::save).forEach(leaderboardList::add)
        tag.put(LEADERBOARD_KEY, leaderboardList)
        return tag
    }

    fun setMarker(marker: RaceMarkerSnapshot) {
        if (markers[marker.blockPos.asLong()] == marker) return
        markers[marker.blockPos.asLong()] = marker
        setDirty()
    }

    fun removeMarker(pos: BlockPos) {
        if (markers.remove(pos.asLong()) != null) {
            setDirty()
        }
    }

    fun replaceMarkers(newMarkers: Collection<RaceMarkerSnapshot>) {
        val replacement = newMarkers.associateBy { it.blockPos.asLong() }
        if (markers == replacement) return
        markers.clear()
        markers.putAll(replacement)
        setDirty()
    }

    fun addLeaderboardEntry(entry: RaceLeaderboardEntry) {
        leaderboard.add(entry)
        setDirty()
    }

    companion object {
        const val SAVED_DATA_ID = "skyriders_races"
        private const val MARKERS_KEY = "markers"
        private const val LEADERBOARD_KEY = "leaderboard"

        fun createEmpty(): RaceSavedData = RaceSavedData()

        fun get(level: ServerLevel): RaceSavedData {
            return level.dataStorage.computeIfAbsent(::load, ::createEmpty, SAVED_DATA_ID)
        }

        @JvmStatic
        fun load(tag: CompoundTag): RaceSavedData {
            val data = RaceSavedData()
            tag.getList(MARKERS_KEY, Tag.TAG_COMPOUND.toInt()).forEach { element ->
                RaceMarkerSnapshot.load(element as CompoundTag)?.let { snapshot ->
                    data.markers[snapshot.blockPos.asLong()] = snapshot
                }
            }
            tag.getList(LEADERBOARD_KEY, Tag.TAG_COMPOUND.toInt()).forEach { element ->
                RaceLeaderboardEntry.load(element as CompoundTag)?.let(data.leaderboard::add)
            }
            return data
        }
    }
}

data class RaceLeaderboardEntry(
    val playerUuid: UUID,
    val playerName: String,
    val vehicleType: String,
    val dimension: String,
    val colorId: Int,
    val totalLaps: Int,
    val startMarkerPos: BlockPos,
    val finishMarkerPos: BlockPos,
    val elapsedTicks: Long,
    val finishedAtGameTime: Long
) {
    fun save(): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID(PLAYER_UUID_KEY, playerUuid)
        tag.putString(PLAYER_NAME_KEY, playerName)
        tag.putString(VEHICLE_TYPE_KEY, vehicleType)
        tag.putString(DIMENSION_KEY, dimension)
        tag.putInt(COLOR_KEY, colorId and 0xFFFFFF)
        tag.putInt(TOTAL_LAPS_KEY, totalLaps)
        tag.putLong(START_MARKER_KEY, startMarkerPos.asLong())
        tag.putLong(FINISH_MARKER_KEY, finishMarkerPos.asLong())
        tag.putLong(ELAPSED_TICKS_KEY, elapsedTicks)
        tag.putLong(FINISHED_AT_KEY, finishedAtGameTime)
        return tag
    }

    companion object {
        private const val PLAYER_UUID_KEY = "PlayerUuid"
        private const val PLAYER_NAME_KEY = "PlayerName"
        private const val VEHICLE_TYPE_KEY = "VehicleType"
        private const val DIMENSION_KEY = "Dimension"
        private const val COLOR_KEY = "ColorId"
        private const val TOTAL_LAPS_KEY = "TotalLaps"
        private const val START_MARKER_KEY = "StartMarker"
        private const val FINISH_MARKER_KEY = "FinishMarker"
        private const val ELAPSED_TICKS_KEY = "ElapsedTicks"
        private const val FINISHED_AT_KEY = "FinishedAtGameTime"

        fun load(tag: CompoundTag): RaceLeaderboardEntry? {
            if (!tag.hasUUID(PLAYER_UUID_KEY)) return null
            return RaceLeaderboardEntry(
                playerUuid = tag.getUUID(PLAYER_UUID_KEY),
                playerName = tag.getString(PLAYER_NAME_KEY),
                vehicleType = tag.getString(VEHICLE_TYPE_KEY),
                dimension = tag.getString(DIMENSION_KEY),
                colorId = tag.getInt(COLOR_KEY) and 0xFFFFFF,
                totalLaps = tag.getInt(TOTAL_LAPS_KEY).coerceAtLeast(1),
                startMarkerPos = BlockPos.of(tag.getLong(START_MARKER_KEY)),
                finishMarkerPos = BlockPos.of(tag.getLong(FINISH_MARKER_KEY)),
                elapsedTicks = tag.getLong(ELAPSED_TICKS_KEY).coerceAtLeast(0L),
                finishedAtGameTime = tag.getLong(FINISHED_AT_KEY)
            )
        }
    }
}
