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
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3f
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.mod.common.toWorldCoordinates
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
        snapshots[marker.blockPos.asLong()] = RaceMarkerSnapshot.from(marker)
    }

    fun unregisterMarker(level: ServerLevel, pos: BlockPos) {
        markerSnapshotsByDimension[level.dimension().location().toString()]?.remove(pos.asLong())
    }

    fun tickLevel(level: ServerLevel) {
        val dimension = level.dimension().location().toString()
        racesByKey.values
            .filter { it.active && it.dimension == dimension }
            .forEach { race ->
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
        tickCrossings(level, markerSnapshot, race)
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
        val checkpoints = collectMarkers(level, startMarker.colorId)
            .filter { it.markerType == RaceMarkerTypes.CHECKPOINT && it.endpointPos != null }
            .sortedBy { it.checkpointIndex }
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
            checkpointMarkers = checkpoints,
            checkpointIndices = checkpoints.mapTo(HashSet()) { it.checkpointIndex },
            racers = participants,
            active = true,
            musicTrack = selectRaceMusicTrack(level),
            totalLaps = startMarker.lapCount,
            totalParticipants = participants.size
        )
        participants.values.forEach { racer ->
            racer.lapStartedAtGameTime = level.gameTime
            racer.nextCheckpointIndex = race.firstCheckpointIndex()
            racer.nextTarget = nextTarget(level, racer, race)
            racer.previousDistances[startSnapshot.blockPos.asLong()] = line.signedDistance(racer.position)
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
            if (marker.checkpointIndex != racer.nextCheckpointIndex) return
            racer.crossedCheckpoints.add(marker.checkpointIndex)
            racer.nextCheckpointIndex = race.nextCheckpointIndexAfter(marker.checkpointIndex)
            racer.nextTarget = nextTarget(level, racer, race)
            successEffect(level, position)
            pulseEndpoint(level, marker)
            driverForBody(level, racer.bodyId)?.sendActionBar("Checkpoint ${marker.checkpointIndex + 1}")
            return
        }

        if (marker.markerType == RaceMarkerTypes.START_FINISH) {
            val maxCheckpoint = race.maxCheckpointIndex()
            if (racer.nextCheckpointIndex <= maxCheckpoint) return
            if (race.finishOrder.contains(racer.bodyId)) return
            if (racer.currentLap < race.totalLaps) {
                racer.currentLap++
                racer.lapStartedAtGameTime = level.gameTime
                racer.crossedCheckpoints.clear()
                racer.nextCheckpointIndex = race.firstCheckpointIndex()
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
            pulseEndpoint(level, marker)
            val place = race.finishOrder.size
            val total = race.totalParticipants
            driverForBody(level, racer.bodyId)?.let { driver ->
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
        race.active = false
        racesByKey.remove(RaceKey(race.dimension, race.colorId))
        sendRaceMusicStopToRacers(level, race)
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
            .filter { it.checkpointIndex == racer.nextCheckpointIndex }
            .minByOrNull { it.blockPos.distSqr(BlockPos.containing(racer.position.x, racer.position.y, racer.position.z)) }
        return nextCheckpoint?.line(level)?.center ?: race.startMarker.line(level)?.center
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
            if (marker.checkpointIndex in racer.crossedCheckpoints) return LineParticleState.CROSSED
            return if (marker.checkpointIndex == racer.nextCheckpointIndex) LineParticleState.NEXT else LineParticleState.BLOCKED
        }
        val maxCheckpoint = race.maxCheckpointIndex()
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
        val checkpointMarkers: List<RaceMarkerSnapshot>,
        val checkpointIndices: Set<Int>,
        val racers: LinkedHashMap<Long, RacerState>,
        val finishOrder: MutableList<Long> = mutableListOf(),
        val musicTrack: ResourceLocation?,
        val totalLaps: Int,
        val totalParticipants: Int,
        var active: Boolean
    ) {
        fun maxCheckpointIndex(): Int = checkpointIndices.maxOrNull() ?: -1
        fun firstCheckpointIndex(): Int = checkpointIndices.minOrNull() ?: 0
        fun nextCheckpointIndexAfter(current: Int): Int {
            return checkpointIndices.filter { it > current }.minOrNull() ?: (maxCheckpointIndex() + 1)
        }
    }

    private data class RacerState(
        val bodyId: Long,
        val driverId: java.util.UUID?,
        val driverName: String,
        var position: Vector3d,
        var currentLap: Int = 1,
        var lapStartedAtGameTime: Long = 0L,
        var nextCheckpointIndex: Int = 0,
        val crossedCheckpoints: MutableSet<Int> = HashSet(),
        val previousDistances: MutableMap<Long, Double> = HashMap(),
        var nextTarget: Vector3d? = null
    )

    private enum class LineParticleState { BLOCKED, NEXT, CROSSED }

    data class RacePlacement(val place: Int, val total: Int)
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
    val lapCount: Int
) {
    companion object {
        fun from(marker: RaceMarkerBlockEntity): RaceMarkerSnapshot {
            return RaceMarkerSnapshot(
                blockPos = marker.blockPos,
                endpointPos = marker.endpointPos,
                colorId = marker.colorId,
                markerType = marker.markerType,
                checkpointIndex = marker.checkpointIndex,
                lapCount = marker.lapCount
            )
        }
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
