package org.valkyrienskies.skyriders.content.racing

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.world.ForgeChunkManager
import org.joml.Vector3d
import org.joml.Vector3f
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.mod.common.toWorldCoordinates
import org.valkyrienskies.skyriders.SkyridersMod.MOD_ID
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.SkyridersSounds
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleRaceParticipants
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import kotlin.math.abs
import java.util.concurrent.ConcurrentHashMap

object RaceMarkerTypes {
    const val START_FINISH = "start_finish"
    const val CHECKPOINT = "checkpoint"
}

object RaceCheckpointLapModes {
    const val ALL = "all"
    const val EXACT = "exact"
    const val FINAL = "final"
    const val PENULTIMATE = "penultimate"
    const val NOT_FINAL = "not_final"
    const val NOT_PENULTIMATE = "not_penultimate"
}

object RaceManager {
    private const val COUNTDOWN_TICKS = 80
    private const val COUNTDOWN_MESSAGE_INTERVAL = 20
    private const val LINE_PARTICLE_SPACING = 0.55
    private const val START_CAPTURE_RANGE = 128.0
    private const val MIN_LINE_LENGTH = 0.75
    private val racesByKey = HashMap<RaceKey, ActiveRace>()
    private val markerSnapshotsByDimension = ConcurrentHashMap<String, MutableMap<Long, RaceMarkerSnapshot>>()

    fun registerMarker(level: ServerLevel, marker: RaceMarkerBlockEntity) {
        val snapshots = markerSnapshotsByDimension
            .getOrPut(level.dimension().location().toString()) { ConcurrentHashMap() }
        val snapshot = RaceMarkerSnapshot.from(marker)
        snapshots[marker.blockPos.asLong()] = snapshot
        RaceSavedData.get(level).setMarker(snapshot)
    }

    fun unregisterMarker(level: ServerLevel, pos: BlockPos) {
        markerSnapshotsByDimension[level.dimension().location().toString()]?.remove(pos.asLong())
        RaceSavedData.get(level).removeMarker(pos)
    }

    fun loadLevel(level: ServerLevel) {
        val dimension = level.dimension().location().toString()
        val snapshots = markerSnapshotsByDimension
            .getOrPut(dimension) { ConcurrentHashMap() }
        snapshots.clear()
        RaceSavedData.get(level).markers.forEach { (key, snapshot) ->
            snapshots[key] = snapshot
        }
    }

    fun saveLevel(level: ServerLevel) {
        val dimension = level.dimension().location().toString()
        RaceSavedData.get(level).replaceMarkers(markerSnapshotsByDimension[dimension]?.values.orEmpty())
    }

    fun tickLevel(level: ServerLevel) {
        val dimension = level.dimension().location().toString()
        racesByKey.values
            .filter { it.active && it.dimension == dimension }
            .forEach { race ->
                activeMarkersForRace(level, race).forEach { marker ->
                    tickCrossings(level, marker, race)
                }
                if (level.gameTime % 40L == 0L) {
                    ensureRaceMarkerChunksLoaded(level, race)
                }
                if (level.gameTime % 40L == 0L) {
                    race.musicTrack?.let { track -> sendRaceMusicStartToRacers(level, race, track) }
                }
                if (level.gameTime % 5L == 0L) {
                    sendRaceHudPositions(level, race)
                    sendRaceCompassTargets(level, race)
                }
            }
    }

    fun startCountdown(marker: RaceMarkerBlockEntity, level: ServerLevel, triggerPlayer: Player? = null) {
        if (marker.markerType != RaceMarkerTypes.START_FINISH || marker.colorId < 0 || marker.endpointPos == null) {
            triggerPlayer?.displayClientMessage(Component.literal("Race marker needs start/finish type, endpoint, and flag color."), true)
            return
        }
        val key = RaceKey(level.dimension().location().toString(), marker.colorId)
        if (racesByKey[key]?.active == true || marker.countdownTicks > 0) return
        marker.countdownTicks = COUNTDOWN_TICKS
        marker.setChanged()
        broadcastActionBarNear(level, marker.blockPos, "Race starting...")
    }

    fun tickMarker(level: ServerLevel, marker: RaceMarkerBlockEntity) {
        registerMarker(level, marker)
        tickCountdown(level, marker)
        val colorId = marker.colorId
        if (colorId < 0 || marker.endpointPos == null) return
        val race = racesByKey[RaceKey(level.dimension().location().toString(), colorId)] ?: return
        if (!race.active) return
        val markerSnapshot = RaceMarkerSnapshot.from(marker)
        if (level.gameTime % 5L == 0L) {
            spawnLineParticles(level, markerSnapshot, race)
        }
    }

    fun compassTarget(level: ServerLevel, player: ServerPlayer): Vector3d? {
        val seat = player.vehicle as? BikeSeatEntity ?: return null
        if (!seat.isDriverSeat()) return null
        val state = racesByKey.values.firstOrNull { race ->
            race.active && race.dimension == level.dimension().location().toString() && race.racers[seat.bodyId]?.driverId == player.uuid
        }?.racers?.get(seat.bodyId) ?: return null
        return state.nextTarget
    }

    fun nextMarkerOptions(level: ServerLevel, player: ServerPlayer): RaceNextMarkerOptions? {
        val seat = player.vehicle as? BikeSeatEntity ?: return null
        if (!seat.isDriverSeat()) return null
        val dimension = level.dimension().location().toString()
        val race = racesByKey.values.firstOrNull { activeRace ->
            activeRace.active && activeRace.dimension == dimension && activeRace.racers[seat.bodyId]?.driverId == player.uuid
        } ?: return null
        val racer = race.racers[seat.bodyId] ?: return null
        refreshRacerPosition(level, racer, race)
        val maxCheckpoint = race.maxCheckpointIndex(racer.currentLap)
        val markerOptions = if (racer.nextCheckpointIndex > maxCheckpoint) {
            finishMarkersForRace(level, race)
        } else {
            race.activeCheckpointMarkers(racer.currentLap).filter { it.checkpointIndex == racer.nextCheckpointIndex }
        }
        val markers = markerOptions.mapNotNull { marker ->
            val line = marker.line(level) ?: return@mapNotNull null
            RaceNextMarkerOption(
                markerType = marker.markerType,
                checkpointIndex = marker.checkpointIndex,
                markerPos = marker.blockPos,
                endpointPos = marker.endpointPos,
                center = line.center
            )
        }
        if (markers.isEmpty()) return null
        return RaceNextMarkerOptions(
            colorId = race.colorId and 0xFFFFFF,
            currentLap = racer.currentLap,
            totalLaps = race.totalLaps,
            nextCheckpointIndex = racer.nextCheckpointIndex,
            markerType = if (racer.nextCheckpointIndex > maxCheckpoint) RaceMarkerTypes.START_FINISH else RaceMarkerTypes.CHECKPOINT,
            options = markers
        )
    }

    fun placementFor(level: ServerLevel, bodyId: Long): RacePlacement? {
        val race = racesByKey.values.firstOrNull { race ->
            race.active && race.dimension == level.dimension().location().toString() && race.racers.containsKey(bodyId)
        } ?: return null
        val standings = activeStandings(level, race)
        val standingIndex = standings.indexOfFirst { it.bodyId == bodyId }
        if (standingIndex < 0) return null
        return RacePlacement(
            place = race.finishOrder.size + standingIndex + 1,
            total = race.totalParticipants
        )
    }

    fun activeRaceColorSuggestions(level: ServerLevel): List<String> {
        val dimension = level.dimension().location().toString()
        return racesByKey.values
            .asSequence()
            .filter { it.active && it.dimension == dimension }
            .map { "%06X".format(it.colorId and 0xFFFFFF) }
            .distinct()
            .sorted()
            .toList()
    }

    fun endRace(level: ServerLevel, colorId: Int): Boolean {
        val dimension = level.dimension().location().toString()
        val key = RaceKey(dimension, colorId and 0xFFFFFF)
        val race = racesByKey[key]?.takeIf { it.active } ?: return false
        stopRace(level, race)
        return true
    }

    private fun tickCountdown(level: ServerLevel, marker: RaceMarkerBlockEntity) {
        if (marker.countdownTicks <= 0) return
        marker.countdownTicks--
        val remaining = marker.countdownTicks
        if (remaining > 0 && remaining % COUNTDOWN_MESSAGE_INTERVAL == 0) {
            val number = remaining / COUNTDOWN_MESSAGE_INTERVAL
            if (number == 3) {
                playRaceStartSound(level, marker.blockPos)
            }
            broadcastTitleNear(level, marker.blockPos, number.toString(), fadeIn = 0, stay = 18, fadeOut = 2)
        }
        if (remaining == 0) {
            startRace(level, marker)
            marker.setChanged()
        }
    }

    private fun startRace(level: ServerLevel, startMarker: RaceMarkerBlockEntity) {
        val color = startMarker.colorId and 0xFFFFFF
        val startSnapshot = RaceMarkerSnapshot.from(startMarker)
        val line = startSnapshot.line(level) ?: return
        val knownMarkers = collectMarkers(level, startMarker.colorId)
            .filter { it.endpointPos != null }
        val checkpoints = knownMarkers
            .filter { it.markerType == RaceMarkerTypes.CHECKPOINT && it.endpointPos != null }
            .sortedBy { it.checkpointIndex }
        val startFinishMarkers = knownMarkers
            .filter { it.markerType == RaceMarkerTypes.START_FINISH }
            .plus(startSnapshot)
            .distinctBy { it.blockPos }
            .sortedBy { it.blockPos.asLong() }
        val participants = VehicleManager.getVehicles(level)
            .filter { VehicleRaceParticipants.matchesColor(it, color) }
            .mapNotNull { vehicle -> createRacerIfNearStart(level, vehicle, line) }
            .associateByTo(LinkedHashMap()) { it.bodyId }
        if (participants.isEmpty()) {
            broadcastActionBarNear(level, startMarker.blockPos, "No matching racers near start line.")
            return
        }
        val race = ActiveRace(
            dimension = level.dimension().location().toString(),
            colorId = startMarker.colorId,
            startMarker = startSnapshot,
            startFinishMarkers = startFinishMarkers,
            checkpointMarkers = checkpoints,
            racers = participants,
            active = true,
            musicTrack = selectRaceMusicTrack(level),
            totalLaps = startMarker.lapCount,
            totalParticipants = participants.size
        )
        ensureRaceMarkerChunksLoaded(level, race)
        participants.values.forEach { racer ->
            racer.raceStartedAtGameTime = level.gameTime
            racer.lapStartedAtGameTime = level.gameTime
            racer.nextCheckpointIndex = race.firstCheckpointIndex(racer.currentLap)
            racer.nextTarget = nextTarget(level, racer, race)
            activeMarkersForRace(level, race).forEach { marker ->
                marker.line(level)?.let { markerLine ->
                    racer.previousDistances[marker.blockPos.asLong()] = markerLine.signedDistance(racer.position)
                }
            }
        }
        racesByKey[RaceKey(race.dimension, race.colorId)] = race
        race.musicTrack?.let { track ->
            sendRaceMusicStartToRacers(level, race, track)
        }
        broadcastTitleNear(level, startMarker.blockPos, "GO!", fadeIn = 0, stay = 24, fadeOut = 6)
    }

    private fun createRacerIfNearStart(level: ServerLevel, vehicle: IVehicle, line: RaceLine): RacerState? {
        val body = level.shipWorld?.allBodies?.getById(vehicle.bodyId) ?: return null
        val position = Vector3d(body.kinematics.position)
        if (line.distanceToSegment(position) > START_CAPTURE_RANGE) return null
        val driver = driverForVehicle(level, vehicle.bodyId)
        return RacerState(
            bodyId = vehicle.bodyId,
            driverId = driver?.uuid,
            driverName = driver?.gameProfile?.name ?: vehicle.vehicleDefinition.displayName,
            vehicleType = vehicle.vehicleDefinition.id.toString(),
            position = position
        )
    }

    private fun tickCrossings(level: ServerLevel, marker: RaceMarkerSnapshot, race: ActiveRace) {
        val line = marker.line(level) ?: return
        race.racers.values.toList().forEach { racer ->
            val vehicle = VehicleManager.getVehicle(level.dimensionId, racer.bodyId) ?: return@forEach
            val body = level.shipWorld?.allBodies?.getById(vehicle.bodyId) ?: return@forEach
            val currentPosition = Vector3d(body.kinematics.position)
            racer.position = currentPosition
            racer.nextTarget = nextTarget(level, racer, race)
            val markerKey = marker.blockPos.asLong()
            val previous = racer.previousDistances.put(markerKey, line.signedDistance(currentPosition)) ?: return@forEach
            val current = racer.previousDistances[markerKey] ?: return@forEach
            if (!crossed(previous, current) || !line.containsCrossing(currentPosition)) return@forEach
            handleCrossing(level, marker, race, racer, currentPosition)
        }
    }

    private fun handleCrossing(
        level: ServerLevel,
        marker: RaceMarkerSnapshot,
        race: ActiveRace,
        racer: RacerState,
        position: Vector3d
    ) {
        if (marker.markerType == RaceMarkerTypes.CHECKPOINT) {
            if (!marker.isActiveForLap(racer.currentLap, race.totalLaps)) return
            if (marker.checkpointIndex != racer.nextCheckpointIndex) return
            racer.crossedCheckpoints.add(marker.checkpointIndex)
            racer.nextCheckpointIndex = race.nextCheckpointIndexAfter(marker.checkpointIndex, racer.currentLap)
            racer.nextTarget = nextTarget(level, racer, race)
            successEffect(level, position)
            pulseEndpoint(level, marker)
            driverForBody(level, racer.bodyId)?.sendActionBar("Checkpoint ${marker.checkpointIndex + 1}")
            return
        }

        if (marker.markerType == RaceMarkerTypes.START_FINISH) {
            val maxCheckpoint = race.maxCheckpointIndex(racer.currentLap)
            if (racer.nextCheckpointIndex <= maxCheckpoint) return
            if (race.finishOrder.contains(racer.bodyId)) return
            if (racer.currentLap < race.totalLaps) {
                racer.currentLap++
                racer.lapStartedAtGameTime = level.gameTime
                racer.crossedCheckpoints.clear()
                racer.nextCheckpointIndex = race.firstCheckpointIndex(racer.currentLap)
                racer.nextTarget = nextTarget(level, racer, race)
                successEffect(level, position)
                pulseEndpoint(level, marker)
                driverForBody(level, racer.bodyId)?.sendTitle(
                    if (racer.currentLap == race.totalLaps) "FINAL LAP" else "Lap ${racer.currentLap}/${race.totalLaps}",
                    fadeIn = 2,
                    stay = 30,
                    fadeOut = 8
                )
                return
            }
            race.finishOrder.add(racer.bodyId)
            successEffect(level, position)
            finishSound(level, position)
            pulseEndpoint(level, marker)
            val place = race.finishOrder.size
            val total = race.totalParticipants
            driverForBody(level, racer.bodyId)?.let { driver ->
                saveLeaderboardEntry(level, race, racer, marker, driver)
                driver.sendTitle("Finished $place/$total", fadeIn = 4, stay = 42, fadeOut = 12)
                SkyridersNetwork.sendRaceCompassTarget(driver, null)
                SkyridersNetwork.sendRaceHudClear(driver)
                SkyridersNetwork.sendRaceMusicStop(driver)
                notifyOtherRacersFinished(level, race, racer.bodyId, driver.gameProfile.name, place)
            }
            race.racers.remove(racer.bodyId)
            if (race.racers.isEmpty()) {
                stopRace(level, race)
            }
        }
    }

    private fun stopRace(level: ServerLevel, race: ActiveRace) {
        releaseRaceMarkerChunks(level, race)
        race.active = false
        racesByKey.remove(RaceKey(race.dimension, race.colorId))
        sendRaceMusicStopToRacers(level, race)
    }

    fun unloadLevel(level: ServerLevel) {
        val dimension = level.dimension().location().toString()
        racesByKey.values
            .filter { it.dimension == dimension }
            .forEach { releaseRaceMarkerChunks(level, it) }
        racesByKey.entries.removeIf { it.key.dimension == dimension }
    }

    private fun sendRaceMusicStartToRacers(level: ServerLevel, race: ActiveRace, track: ResourceLocation) {
        race.racers.values
            .mapNotNull { racer -> driverForBody(level, racer.bodyId) }
            .forEach { driver -> SkyridersNetwork.sendRaceMusicStart(driver, track) }
    }

    private fun sendRaceMusicStopToRacers(level: ServerLevel, race: ActiveRace) {
        race.racers.values
            .mapNotNull { racer -> driverForBody(level, racer.bodyId) }
            .forEach { driver ->
                SkyridersNetwork.sendRaceHudClear(driver)
                SkyridersNetwork.sendRaceMusicStop(driver)
            }
    }

    private fun sendRaceHudPositions(level: ServerLevel, race: ActiveRace) {
        activeStandings(level, race).forEachIndexed { index, racer ->
            val place = race.finishOrder.size + index + 1
            driverForBody(level, racer.bodyId)?.let { driver ->
                SkyridersNetwork.sendRaceHudPosition(
                    player = driver,
                    bodyId = racer.bodyId,
                    place = place,
                    total = race.totalParticipants,
                    lap = racer.currentLap,
                    totalLaps = race.totalLaps,
                    lapElapsedTicks = (level.gameTime - racer.lapStartedAtGameTime).coerceAtLeast(0L)
                )
            }
        }
    }

    private fun sendRaceCompassTargets(level: ServerLevel, race: ActiveRace) {
        race.racers.values.forEach { racer ->
            driverForBody(level, racer.bodyId)?.let { driver ->
                SkyridersNetwork.sendRaceCompassTarget(driver, racer.nextTarget?.let { Vec3(it.x, it.y, it.z) })
            }
        }
    }

    private fun activeStandings(level: ServerLevel, race: ActiveRace): List<RacerState> {
        race.racers.values.forEach { racer ->
            refreshRacerPosition(level, racer, race)
        }
        return race.racers.values.sortedWith(
            compareByDescending<RacerState> { it.currentLap }
                .thenByDescending { it.crossedCheckpoints.size }
                .thenBy { distanceToNextTarget(it) }
        )
    }

    private fun refreshRacerPosition(level: ServerLevel, racer: RacerState, race: ActiveRace) {
        val vehicle = VehicleManager.getVehicle(level.dimensionId, racer.bodyId) ?: return
        val body = level.shipWorld?.allBodies?.getById(vehicle.bodyId) ?: return
        racer.position = Vector3d(body.kinematics.position)
        racer.nextTarget = nextTarget(level, racer, race)
    }

    private fun distanceToNextTarget(racer: RacerState): Double {
        return racer.nextTarget?.distance(racer.position) ?: Double.MAX_VALUE
    }

    private fun notifyOtherRacersFinished(
        level: ServerLevel,
        race: ActiveRace,
        finishedBodyId: Long,
        playerName: String,
        place: Int
    ) {
        val message = "$playerName finished in ${ordinal(place)} place!"
        race.racers.values
            .filter { it.bodyId != finishedBodyId }
            .mapNotNull { racer -> driverForBody(level, racer.bodyId) }
            .forEach { driver -> driver.sendActionBar(message) }
    }

    private fun nextTarget(level: ServerLevel, racer: RacerState, race: ActiveRace): Vector3d? {
        val nextCheckpoint = race.checkpointMarkers
            .filter { it.isActiveForLap(racer.currentLap, race.totalLaps) }
            .filter { it.checkpointIndex == racer.nextCheckpointIndex }
            .minByOrNull { it.blockPos.distSqr(BlockPos.containing(racer.position.x, racer.position.y, racer.position.z)) }
        if (nextCheckpoint != null) return nextCheckpoint.line(level)?.center
        val racerBlockPos = BlockPos.containing(racer.position.x, racer.position.y, racer.position.z)
        return finishMarkersForRace(level, race)
            .minByOrNull { it.blockPos.distSqr(racerBlockPos) }
            ?.line(level)
            ?.center
    }

    private fun activeMarkersForRace(level: ServerLevel, race: ActiveRace): List<RaceMarkerSnapshot> {
        return race.checkpointMarkers
            .plus(finishMarkersForRace(level, race))
            .distinctBy { it.blockPos }
    }

    private fun finishMarkersForRace(level: ServerLevel, race: ActiveRace): List<RaceMarkerSnapshot> {
        return race.startFinishMarkers
            .plus(
                collectMarkers(level, race.colorId)
                    .filter { it.markerType == RaceMarkerTypes.START_FINISH && it.endpointPos != null }
            )
            .distinctBy { it.blockPos }
            .sortedBy { it.blockPos.asLong() }
    }

    private fun ensureRaceMarkerChunksLoaded(level: ServerLevel, race: ActiveRace) {
        activeMarkersForRace(level, race)
            .flatMap { marker -> marker.chunkKeys() }
            .distinct()
            .forEach { chunkKey ->
                if (chunkKey in race.forceLoadedMarkerChunks) return@forEach
                val chunkPos = ChunkPos(chunkKey)
                val loaded = ForgeChunkManager.forceChunk(
                    level,
                    MOD_ID,
                    race.startMarker.blockPos,
                    chunkPos.x,
                    chunkPos.z,
                    true,
                    true
                )
                if (loaded) {
                    race.forceLoadedMarkerChunks.add(chunkKey)
                }
            }
    }

    private fun releaseRaceMarkerChunks(level: ServerLevel, race: ActiveRace) {
        race.forceLoadedMarkerChunks.toList().forEach { chunkKey ->
            val chunkPos = ChunkPos(chunkKey)
            ForgeChunkManager.forceChunk(
                level,
                MOD_ID,
                race.startMarker.blockPos,
                chunkPos.x,
                chunkPos.z,
                false,
                true
            )
        }
        race.forceLoadedMarkerChunks.clear()
    }

    private fun saveLeaderboardEntry(
        level: ServerLevel,
        race: ActiveRace,
        racer: RacerState,
        finishMarker: RaceMarkerSnapshot,
        driver: ServerPlayer
    ) {
        RaceSavedData.get(level).addLeaderboardEntry(
            RaceLeaderboardEntry(
                playerUuid = driver.uuid,
                playerName = driver.gameProfile.name,
                vehicleType = racer.vehicleType,
                dimension = race.dimension,
                colorId = race.colorId,
                totalLaps = race.totalLaps,
                startMarkerPos = race.startMarker.blockPos,
                finishMarkerPos = finishMarker.blockPos,
                elapsedTicks = (level.gameTime - racer.raceStartedAtGameTime).coerceAtLeast(0L),
                finishedAtGameTime = level.gameTime
            )
        )
    }

    private fun pulseEndpoint(level: ServerLevel, marker: RaceMarkerSnapshot) {
        val endpointPos = marker.endpointPos ?: return
        (level.getBlockEntity(endpointPos) as? RaceEndpointBlockEntity)?.pulse()
    }

    private fun spawnLineParticles(level: ServerLevel, marker: RaceMarkerSnapshot, race: ActiveRace) {
        val line = marker.line(level) ?: return
        val length = line.length()
        if (length < MIN_LINE_LENGTH) return
        val count = (length / LINE_PARTICLE_SPACING).toInt().coerceAtLeast(2)
        level.players().forEach { player ->
            val seat = player.vehicle as? BikeSeatEntity ?: return@forEach
            val racer = race.racers[seat.bodyId] ?: return@forEach
            val particle = lineParticle(markerStateForRacer(marker, racer, race))
            for (i in 0..count) {
                val t = i.toDouble() / count.toDouble()
                val p = line.point(t)
                level.sendParticles(player, particle, true, p.x, p.y + 0.1, p.z, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    private fun markerStateForRacer(marker: RaceMarkerSnapshot, racer: RacerState, race: ActiveRace): LineParticleState {
        if (marker.markerType == RaceMarkerTypes.CHECKPOINT) {
            if (!marker.isActiveForLap(racer.currentLap, race.totalLaps)) return LineParticleState.BLOCKED
            if (marker.checkpointIndex in racer.crossedCheckpoints) return LineParticleState.CROSSED
            return if (marker.checkpointIndex == racer.nextCheckpointIndex) LineParticleState.NEXT else LineParticleState.BLOCKED
        }
        val maxCheckpoint = race.maxCheckpointIndex(racer.currentLap)
        return if (racer.nextCheckpointIndex > maxCheckpoint) LineParticleState.NEXT else LineParticleState.BLOCKED
    }

    private fun lineParticle(state: LineParticleState): DustParticleOptions {
        val color = when (state) {
            LineParticleState.BLOCKED -> Vector3f(1.0f, 0.05f, 0.03f)
            LineParticleState.NEXT -> Vector3f(1.0f, 1.0f, 1.0f)
            LineParticleState.CROSSED -> Vector3f(0.1f, 1.0f, 0.12f)
        }
        return DustParticleOptions(color, 1.0f)
    }

    private fun successEffect(level: ServerLevel, position: Vector3d) {
        level.sendParticles(
            DustParticleOptions(Vector3f(0.25f, 1.0f, 0.05f), 1.35f),
            position.x,
            position.y + 0.9,
            position.z,
            38,
            0.75,
            0.55,
            0.75,
            0.12
        )
        level.playSound(
            null,
            position.x,
            position.y,
            position.z,
            SoundEvents.FIREWORK_ROCKET_BLAST,
            SoundSource.PLAYERS,
            0.85f,
            1.25f
        )
    }

    private fun finishSound(level: ServerLevel, position: Vector3d) {
        level.playSound(
            null,
            position.x,
            position.y,
            position.z,
            SkyridersSounds.RACE_FINISH_SOUND.get(),
            SoundSource.PLAYERS,
            1.0f,
            1.0f
        )
    }

    private fun playRaceStartSound(level: ServerLevel, pos: BlockPos) {
        level.playSound(null, pos, SkyridersSounds.RACE_START_SOUND.get(), SoundSource.BLOCKS, 1.0f, 1.0f)
    }

    private fun broadcastActionBarNear(level: ServerLevel, pos: BlockPos, message: String) {
        val center = Vec3.atCenterOf(pos)
        level.players().filter { it.position().distanceToSqr(center) < 80.0 * 80.0 }.forEach {
            it.sendActionBar(message)
        }
    }

    private fun broadcastTitleNear(
        level: ServerLevel,
        pos: BlockPos,
        title: String,
        subtitle: String? = null,
        fadeIn: Int = 0,
        stay: Int = 20,
        fadeOut: Int = 5
    ) {
        val center = Vec3.atCenterOf(pos)
        level.players().filter { it.position().distanceToSqr(center) < 80.0 * 80.0 }.forEach {
            it.sendTitle(title, subtitle, fadeIn, stay, fadeOut)
        }
    }

    private fun ServerPlayer.sendActionBar(message: String) {
        displayClientMessage(Component.literal(message), true)
    }

    private fun ServerPlayer.sendTitle(
        title: String,
        subtitle: String? = null,
        fadeIn: Int = 0,
        stay: Int = 20,
        fadeOut: Int = 5
    ) {
        connection.send(ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut))
        if (subtitle != null) {
            connection.send(ClientboundSetSubtitleTextPacket(Component.literal(subtitle)))
        }
        connection.send(ClientboundSetTitleTextPacket(Component.literal(title)))
    }

    private fun collectMarkers(level: ServerLevel, colorId: Int): List<RaceMarkerSnapshot> {
        val snapshots = markerSnapshotsByDimension[level.dimension().location().toString()] ?: return emptyList()
        return snapshots.values.filter { it.colorId == colorId }
    }

    private fun driverForVehicle(level: ServerLevel, bodyId: Long): ServerPlayer? {
        return level.players().firstOrNull { player ->
            val seat = player.vehicle as? BikeSeatEntity ?: return@firstOrNull false
            seat.bodyId == bodyId && seat.isDriverSeat()
        }
    }

    private fun driverForBody(level: ServerLevel, bodyId: Long): ServerPlayer? = driverForVehicle(level, bodyId)

    private fun selectRaceMusicTrack(level: ServerLevel): ResourceLocation? {
        val tracks = SkyridersSounds.RACE_MUSIC_TRACKS
        if (tracks.isEmpty()) return null
        return tracks[level.random.nextInt(tracks.size)].get().location
    }

    private fun crossed(previous: Double, current: Double): Boolean {
        if (!previous.isFinite() || !current.isFinite()) return false
        if (abs(previous) < 1.0E-4 || abs(current) < 1.0E-4) return false
        return previous < 0.0 && current > 0.0 || previous > 0.0 && current < 0.0
    }

    private fun ordinal(value: Int): String {
        val mod100 = value % 100
        val suffix = if (mod100 in 11..13) {
            "th"
        } else {
            when (value % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$value$suffix"
    }

    private data class RaceKey(val dimension: String, val colorId: Int)

    private data class ActiveRace(
        val dimension: String,
        val colorId: Int,
        val startMarker: RaceMarkerSnapshot,
        val startFinishMarkers: List<RaceMarkerSnapshot>,
        val checkpointMarkers: List<RaceMarkerSnapshot>,
        val racers: LinkedHashMap<Long, RacerState>,
        val finishOrder: MutableList<Long> = mutableListOf(),
        val forceLoadedMarkerChunks: MutableSet<Long> = HashSet(),
        val musicTrack: ResourceLocation?,
        val totalLaps: Int,
        val totalParticipants: Int,
        var active: Boolean
    ) {
        fun activeCheckpointMarkers(lap: Int): List<RaceMarkerSnapshot> {
            return checkpointMarkers.filter { it.isActiveForLap(lap, totalLaps) }
        }

        fun maxCheckpointIndex(lap: Int): Int = activeCheckpointMarkers(lap).maxOfOrNull { it.checkpointIndex } ?: -1
        fun firstCheckpointIndex(lap: Int): Int = activeCheckpointMarkers(lap).minOfOrNull { it.checkpointIndex } ?: 0
        fun nextCheckpointIndexAfter(current: Int, lap: Int): Int {
            val indices = activeCheckpointMarkers(lap).mapTo(HashSet()) { it.checkpointIndex }
            return indices.filter { it > current }.minOrNull() ?: (maxCheckpointIndex(lap) + 1)
        }
    }

    private data class RacerState(
        val bodyId: Long,
        val driverId: java.util.UUID?,
        val driverName: String,
        val vehicleType: String,
        var position: Vector3d,
        var raceStartedAtGameTime: Long = 0L,
        var currentLap: Int = 1,
        var lapStartedAtGameTime: Long = 0L,
        var nextCheckpointIndex: Int = 0,
        val crossedCheckpoints: MutableSet<Int> = HashSet(),
        val previousDistances: MutableMap<Long, Double> = HashMap(),
        var nextTarget: Vector3d? = null
    )

    private enum class LineParticleState { BLOCKED, NEXT, CROSSED }

    data class RacePlacement(val place: Int, val total: Int)

    data class RaceNextMarkerOptions(
        val colorId: Int,
        val currentLap: Int,
        val totalLaps: Int,
        val nextCheckpointIndex: Int,
        val markerType: String,
        val options: List<RaceNextMarkerOption>
    )

    data class RaceNextMarkerOption(
        val markerType: String,
        val checkpointIndex: Int,
        val markerPos: BlockPos,
        val endpointPos: BlockPos?,
        val center: Vector3d
    )
}

data class RaceLine(val start: Vector3d, val end: Vector3d) {
    val center: Vector3d = Vector3d(start).add(end).mul(0.5)
    private val segment = Vector3d(end).sub(start)
    private val horizontalSegment = Vector3d(segment.x, 0.0, segment.z)
    private val horizontalLengthSquared = horizontalSegment.lengthSquared()
    private val normal = if (horizontalLengthSquared > 1.0E-6) {
        Vector3d(-horizontalSegment.z, 0.0, horizontalSegment.x).normalize()
    } else {
        Vector3d(1.0, 0.0, 0.0)
    }

    fun length(): Double = segment.length()

    fun signedDistance(position: Vector3d): Double = Vector3d(position).sub(start).dot(normal)

    fun containsCrossing(position: Vector3d): Boolean {
        if (horizontalLengthSquared <= 1.0E-6) return false
        val horizontalOffset = Vector3d(position.x - start.x, 0.0, position.z - start.z)
        val t = horizontalOffset.dot(horizontalSegment) / horizontalLengthSquared
        if (t < 0.0 || t > 1.0) return false
        val lineY = start.y + (end.y - start.y) * t
        return abs(position.y - lineY) <= 3.0
    }

    fun distanceToSegment(position: Vector3d): Double {
        if (horizontalLengthSquared <= 1.0E-6) return Double.MAX_VALUE
        val horizontalOffset = Vector3d(position.x - start.x, 0.0, position.z - start.z)
        val t = (horizontalOffset.dot(horizontalSegment) / horizontalLengthSquared).coerceIn(0.0, 1.0)
        return Vector3d(start.x + horizontalSegment.x * t, position.y, start.z + horizontalSegment.z * t)
            .distance(position)
    }

    fun point(t: Double): Vector3d = Vector3d(start).lerp(end, t)
}

data class RaceMarkerSnapshot(
    val blockPos: BlockPos,
    val endpointPos: BlockPos?,
    val colorId: Int,
    val markerType: String,
    val checkpointIndex: Int,
    val checkpointLapMode: String,
    val checkpointLap: Int,
    val lapCount: Int
) {
    fun save(): net.minecraft.nbt.CompoundTag {
        val tag = net.minecraft.nbt.CompoundTag()
        tag.putLong(BLOCK_POS_KEY, blockPos.asLong())
        endpointPos?.let { tag.putLong(ENDPOINT_POS_KEY, it.asLong()) }
        tag.putInt(COLOR_KEY, colorId and 0xFFFFFF)
        tag.putString(TYPE_KEY, markerType)
        tag.putInt(CHECKPOINT_KEY, checkpointIndex)
        tag.putString(CHECKPOINT_LAP_MODE_KEY, checkpointLapMode)
        tag.putInt(CHECKPOINT_LAP_KEY, checkpointLap)
        tag.putInt(LAP_COUNT_KEY, lapCount)
        return tag
    }

    companion object {
        private const val BLOCK_POS_KEY = "BlockPos"
        private const val ENDPOINT_POS_KEY = "EndpointPos"
        private const val COLOR_KEY = "ColorId"
        private const val TYPE_KEY = "MarkerType"
        private const val CHECKPOINT_KEY = "CheckpointIndex"
        private const val CHECKPOINT_LAP_MODE_KEY = "CheckpointLapMode"
        private const val CHECKPOINT_LAP_KEY = "CheckpointLap"
        private const val LAP_COUNT_KEY = "LapCount"

        fun from(marker: RaceMarkerBlockEntity): RaceMarkerSnapshot {
            return RaceMarkerSnapshot(
                blockPos = marker.blockPos,
                endpointPos = marker.endpointPos,
                colorId = marker.colorId,
                markerType = marker.markerType,
                checkpointIndex = marker.checkpointIndex,
                checkpointLapMode = marker.checkpointLapMode,
                checkpointLap = marker.checkpointLap,
                lapCount = marker.lapCount
            )
        }

        fun load(tag: net.minecraft.nbt.CompoundTag): RaceMarkerSnapshot? {
            if (!tag.contains(BLOCK_POS_KEY)) return null
            val type = tag.getString(TYPE_KEY)
                .takeIf { it == RaceMarkerTypes.START_FINISH || it == RaceMarkerTypes.CHECKPOINT }
                ?: RaceMarkerTypes.START_FINISH
            return RaceMarkerSnapshot(
                blockPos = BlockPos.of(tag.getLong(BLOCK_POS_KEY)),
                endpointPos = if (tag.contains(ENDPOINT_POS_KEY)) BlockPos.of(tag.getLong(ENDPOINT_POS_KEY)) else null,
                colorId = org.valkyrienskies.skyriders.content.item.RaceFlagItem.normalizeSavedRaceColor(tag.getInt(COLOR_KEY)),
                markerType = type,
                checkpointIndex = tag.getInt(CHECKPOINT_KEY).coerceIn(0, 99),
                checkpointLapMode = tag.getString(CHECKPOINT_LAP_MODE_KEY)
                    .takeIf {
                        it == RaceCheckpointLapModes.ALL ||
                            it == RaceCheckpointLapModes.EXACT ||
                            it == RaceCheckpointLapModes.FINAL ||
                            it == RaceCheckpointLapModes.PENULTIMATE ||
                            it == RaceCheckpointLapModes.NOT_FINAL ||
                            it == RaceCheckpointLapModes.NOT_PENULTIMATE
                    }
                    ?: RaceCheckpointLapModes.ALL,
                checkpointLap = if (tag.contains(CHECKPOINT_LAP_KEY)) tag.getInt(CHECKPOINT_LAP_KEY).coerceAtLeast(1) else 1,
                lapCount = if (tag.contains(LAP_COUNT_KEY)) tag.getInt(LAP_COUNT_KEY).coerceAtLeast(1) else 3
            )
        }
    }
}

private fun RaceMarkerSnapshot.chunkKeys(): List<Long> {
    return listOfNotNull(
        ChunkPos(blockPos).toLong(),
        endpointPos?.let { ChunkPos(it).toLong() }
    )
}

private fun RaceMarkerSnapshot.isActiveForLap(currentLap: Int, totalLaps: Int): Boolean {
    return when (checkpointLapMode) {
        RaceCheckpointLapModes.EXACT -> currentLap == checkpointLap
        RaceCheckpointLapModes.FINAL -> currentLap == totalLaps
        RaceCheckpointLapModes.PENULTIMATE -> totalLaps > 1 && currentLap == totalLaps - 1
        RaceCheckpointLapModes.NOT_FINAL -> currentLap != totalLaps
        RaceCheckpointLapModes.NOT_PENULTIMATE -> totalLaps <= 1 || currentLap != totalLaps - 1
        else -> true
    }
}

fun RaceMarkerBlockEntity.line(level: ServerLevel): RaceLine? {
    return RaceMarkerSnapshot.from(this).line(level)
}

fun RaceMarkerSnapshot.line(level: ServerLevel): RaceLine? {
    val endpoint = endpointPos ?: return null
    val a = level.toWorldCoordinates(Vec3.atCenterOf(blockPos)).let { Vector3d(it.x, it.y, it.z) }
    val b = level.toWorldCoordinates(Vec3.atCenterOf(endpoint)).let { Vector3d(it.x, it.y, it.z) }
    if (!a.isFinite() || !b.isFinite() || a.distanceSquared(b) < 0.75 * 0.75) return null
    return RaceLine(a, b)
}

private fun Vector3d.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
