package org.valkyrienskies.skyriders.content.racing

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.network.chat.Component
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
    private const val TICK_SOUND_INTERVAL = 20
    private const val LINE_PARTICLE_SPACING = 0.55
    private const val START_CAPTURE_RANGE = 8.0
    private const val MIN_LINE_LENGTH = 0.75
    private val racesByKey = HashMap<RaceKey, ActiveRace>()
    private val markerPositionsByDimension = ConcurrentHashMap<String, MutableSet<Long>>()

    fun registerMarker(level: ServerLevel, pos: BlockPos) {
        markerPositionsByDimension
            .getOrPut(level.dimension().location().toString()) { ConcurrentHashMap.newKeySet() }
            .add(pos.asLong())
    }

    fun unregisterMarker(level: ServerLevel, pos: BlockPos) {
        markerPositionsByDimension[level.dimension().location().toString()]?.remove(pos.asLong())
    }

    fun startCountdown(marker: RaceMarkerBlockEntity, level: ServerLevel, triggerPlayer: Player? = null) {
        if (marker.markerType != RaceMarkerTypes.START_FINISH || marker.colorId < 0 || marker.endpointPos == null) {
            triggerPlayer?.sendSystemMessage(Component.literal("Race marker needs start/finish type, endpoint, and flag color."))
            return
        }
        val key = RaceKey(level.dimension().location().toString(), marker.colorId)
        if (racesByKey[key]?.active == true || marker.countdownTicks > 0) return
        marker.countdownTicks = COUNTDOWN_TICKS
        marker.setChanged()
        broadcastNear(level, marker.blockPos, "Race starting...")
    }

    fun tickMarker(level: ServerLevel, marker: RaceMarkerBlockEntity) {
        tickCountdown(level, marker)
        val colorId = marker.colorId
        if (colorId < 0 || marker.endpointPos == null) return
        val race = racesByKey[RaceKey(level.dimension().location().toString(), colorId)] ?: return
        if (!race.active) return
        tickCrossings(level, marker, race)
        if (level.gameTime % 5L == 0L) {
            spawnLineParticles(level, marker, race)
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

    private fun tickCountdown(level: ServerLevel, marker: RaceMarkerBlockEntity) {
        if (marker.countdownTicks <= 0) return
        marker.countdownTicks--
        val remaining = marker.countdownTicks
        if (remaining > 0 && remaining % TICK_SOUND_INTERVAL == 0) {
            val number = remaining / TICK_SOUND_INTERVAL
            playCountdownSound(level, marker.blockPos, 0.75f + (3 - number) * 0.1f)
            broadcastNear(level, marker.blockPos, number.toString())
        }
        if (remaining == 0) {
            startRace(level, marker)
            marker.setChanged()
        }
    }

    private fun startRace(level: ServerLevel, startMarker: RaceMarkerBlockEntity) {
        val color = startMarker.colorId and 0xFFFFFF
        val line = startMarker.line(level) ?: return
        val checkpoints = collectMarkers(level, startMarker.colorId)
            .filter { it.markerType == RaceMarkerTypes.CHECKPOINT && it.endpointPos != null }
            .sortedBy { it.checkpointIndex }
        val participants = VehicleManager.getVehicles(level)
            .filter { VehicleRaceParticipants.matchesColor(it, color) }
            .mapNotNull { vehicle -> createRacerIfNearStart(level, vehicle, line) }
            .associateByTo(LinkedHashMap()) { it.bodyId }
        if (participants.isEmpty()) {
            broadcastNear(level, startMarker.blockPos, "No matching racers near start line.")
            return
        }
        val race = ActiveRace(
            dimension = level.dimension().location().toString(),
            colorId = startMarker.colorId,
            startMarkerPos = startMarker.blockPos,
            checkpointMarkerPositions = checkpoints.map { it.blockPos },
            checkpointIndices = checkpoints.mapTo(HashSet()) { it.checkpointIndex },
            racers = participants,
            active = true,
            musicTrack = selectRaceMusicTrack(level),
            totalParticipants = participants.size
        )
        participants.values.forEach { racer ->
            racer.nextCheckpointIndex = race.firstCheckpointIndex()
            racer.nextTarget = nextTarget(level, racer, race)
            racer.previousDistances[startMarker.blockPos.asLong()] = line.signedDistance(racer.position)
        }
        racesByKey[RaceKey(race.dimension, race.colorId)] = race
        race.musicTrack?.let { track ->
            sendRaceMusicStartToRacers(level, race, track)
        }
        playGoSound(level, startMarker.blockPos)
        broadcastNear(level, startMarker.blockPos, "GO!")
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

    private fun tickCrossings(level: ServerLevel, marker: RaceMarkerBlockEntity, race: ActiveRace) {
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
        marker: RaceMarkerBlockEntity,
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
            driverForBody(level, racer.bodyId)?.sendSystemMessage(Component.literal("Checkpoint ${marker.checkpointIndex + 1}"))
            return
        }

        if (marker.markerType == RaceMarkerTypes.START_FINISH) {
            val maxCheckpoint = race.maxCheckpointIndex()
            if (racer.nextCheckpointIndex <= maxCheckpoint) return
            if (race.finishOrder.contains(racer.bodyId)) return
            race.finishOrder.add(racer.bodyId)
            successEffect(level, position)
            val place = race.finishOrder.size
            val total = race.totalParticipants
            driverForBody(level, racer.bodyId)?.let { driver ->
                driver.sendSystemMessage(Component.literal("Finished $place/$total"))
                SkyridersNetwork.sendRaceCompassTarget(driver, null)
                SkyridersNetwork.sendRaceMusicStop(driver)
            }
            race.racers.remove(racer.bodyId)
            if (race.racers.isEmpty()) {
                endRace(level, race)
            }
        }
    }

    private fun endRace(level: ServerLevel, race: ActiveRace) {
        race.active = false
        racesByKey.remove(RaceKey(race.dimension, race.colorId))
        val dimension = level.dimension().location().toString()
        if (racesByKey.values.none { it.active && it.dimension == dimension }) {
            sendRaceMusicStopToRacers(level, race)
        }
    }

    private fun sendRaceMusicStartToRacers(level: ServerLevel, race: ActiveRace, track: ResourceLocation) {
        race.racers.values
            .mapNotNull { racer -> driverForBody(level, racer.bodyId) }
            .forEach { driver -> SkyridersNetwork.sendRaceMusicStart(driver, track) }
    }

    private fun sendRaceMusicStopToRacers(level: ServerLevel, race: ActiveRace) {
        race.racers.values
            .mapNotNull { racer -> driverForBody(level, racer.bodyId) }
            .forEach { driver -> SkyridersNetwork.sendRaceMusicStop(driver) }
    }

    private fun nextTarget(level: ServerLevel, racer: RacerState, race: ActiveRace): Vector3d? {
        val nextCheckpoint = race.checkpointMarkerPositions
            .mapNotNull { level.getBlockEntity(it) as? RaceMarkerBlockEntity }
            .filter { it.checkpointIndex == racer.nextCheckpointIndex }
            .minByOrNull { it.blockPos.distSqr(BlockPos.containing(racer.position.x, racer.position.y, racer.position.z)) }
        return nextCheckpoint?.line(level)?.center ?: (level.getBlockEntity(race.startMarkerPos) as? RaceMarkerBlockEntity)?.line(level)?.center
    }

    private fun spawnLineParticles(level: ServerLevel, marker: RaceMarkerBlockEntity, race: ActiveRace) {
        val line = marker.line(level) ?: return
        val length = line.length()
        if (length < MIN_LINE_LENGTH) return
        val count = (length / LINE_PARTICLE_SPACING).toInt().coerceAtLeast(2)
        level.players().forEach { player ->
            val seat = player.vehicle as? BikeSeatEntity ?: return@forEach
            val racer = race.racers[seat.bodyId] ?: return@forEach
            SkyridersNetwork.sendRaceCompassTarget(player, racer.nextTarget?.let { Vec3(it.x, it.y, it.z) })
            val particle = lineParticle(markerStateForRacer(marker, racer, race))
            for (i in 0..count) {
                val t = i.toDouble() / count.toDouble()
                val p = line.point(t)
                level.sendParticles(player, particle, true, p.x, p.y + 0.1, p.z, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    private fun markerStateForRacer(marker: RaceMarkerBlockEntity, racer: RacerState, race: ActiveRace): LineParticleState {
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

    private fun playCountdownSound(level: ServerLevel, pos: BlockPos, pitch: Float) {
        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 0.9f, pitch)
    }

    private fun playGoSound(level: ServerLevel, pos: BlockPos) {
        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 1.15f, 1.65f)
    }

    private fun broadcastNear(level: ServerLevel, pos: BlockPos, message: String) {
        val center = Vec3.atCenterOf(pos)
        level.players().filter { it.position().distanceToSqr(center) < 80.0 * 80.0 }.forEach {
            it.displayClientMessage(Component.literal(message), true)
        }
    }

    private fun collectMarkers(level: ServerLevel, colorId: Int): List<RaceMarkerBlockEntity> {
        val positions = markerPositionsByDimension[level.dimension().location().toString()] ?: return emptyList()
        return positions.mapNotNull { packed ->
            val be = level.getBlockEntity(BlockPos.of(packed)) as? RaceMarkerBlockEntity ?: return@mapNotNull null
            be.takeIf { it.colorId == colorId && !it.isRemoved }
        }
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

    private data class RaceKey(val dimension: String, val colorId: Int)

    private data class ActiveRace(
        val dimension: String,
        val colorId: Int,
        val startMarkerPos: BlockPos,
        val checkpointMarkerPositions: List<BlockPos>,
        val checkpointIndices: Set<Int>,
        val racers: LinkedHashMap<Long, RacerState>,
        val finishOrder: MutableList<Long> = mutableListOf(),
        val musicTrack: ResourceLocation?,
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
        var nextCheckpointIndex: Int = 0,
        val crossedCheckpoints: MutableSet<Int> = HashSet(),
        val previousDistances: MutableMap<Long, Double> = HashMap(),
        var nextTarget: Vector3d? = null
    )

    private enum class LineParticleState { BLOCKED, NEXT, CROSSED }
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

fun RaceMarkerBlockEntity.line(level: ServerLevel): RaceLine? {
    val endpoint = endpointPos ?: return null
    val a = level.toWorldCoordinates(Vec3.atCenterOf(blockPos)).let { Vector3d(it.x, it.y, it.z) }
    val b = level.toWorldCoordinates(Vec3.atCenterOf(endpoint)).let { Vector3d(it.x, it.y, it.z) }
    if (!a.isFinite() || !b.isFinite() || a.distanceSquared(b) < 0.75 * 0.75) return null
    return RaceLine(a, b)
}

private fun Vector3d.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
