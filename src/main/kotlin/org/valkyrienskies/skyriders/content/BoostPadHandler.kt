package org.valkyrienskies.skyriders.content

import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundSource
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.joml.primitives.AABBd
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.VsBody
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.mod.common.getShipsIntersecting
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.util.VehiclePhysicsMath
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

object BoostPadHandler {
    private const val BOOST_ACCELERATION = 32.0
    private const val BOOST_TARGET_SPEED = 30.0
    private const val BOOST_FADE_RANGE = 6.0
    private const val MIN_TRIGGER_SPEED = 0.35
    private const val PROBE_STEP = 0.25
    private const val SHIP_SAMPLE_RADIUS = 0.35
    private const val SOUND_COOLDOWN_TICKS = 20L
    private val boostPadsByDimension = ConcurrentHashMap<DimensionId, MutableSet<Long>>()
    private val nextSoundTickByBody = ConcurrentHashMap<Long, Long>()

    fun physTick(physLevel: PhysLevel, vehicles: Iterable<IVehicle>, dt: Double) {
        vehicles.forEach { vehicle ->
            if (vehicle.level.isClientSide || vehicle.level.dimensionId != physLevel.dimension) return@forEach
            val body = physLevel.getBodyById(vehicle.bodyId) ?: return@forEach
            applyBoostIfOnPad(vehicle, body, dt)
        }
    }

    fun gameTick(level: ServerLevel, vehicles: Iterable<IVehicle>) {
        vehicles.forEach { vehicle ->
            if (vehicle.level.dimensionId != level.dimensionId) return@forEach
            val body = level.shipWorld?.allBodies?.getById(vehicle.bodyId) ?: return@forEach
            refreshCacheNearVehicle(level, vehicle, body)
            playBoostSoundIfNeeded(level, vehicle, body)
        }
    }

    fun cacheBoostPad(level: Level, pos: BlockPos) {
        if (level.isClientSide) return
        boostPadsByDimension.getOrPut(level.dimensionId) { ConcurrentHashMap.newKeySet() }.add(pos.asLong())
    }

    fun uncacheBoostPad(level: Level, pos: BlockPos) {
        if (level.isClientSide) return
        boostPadsByDimension[level.dimensionId]?.remove(pos.asLong())
    }

    fun clear(level: Level) {
        if (level.isClientSide) return
        boostPadsByDimension.remove(level.dimensionId)
    }

    private fun applyBoostIfOnPad(vehicle: IVehicle, body: PhysVsBody, dt: Double) {
        if (dt <= 0.0 || !isOnCachedBoostPad(vehicle, body)) return

        val velocity = body.kinematics.velocity
        val planarVelocity = Vector3d(velocity.x(), 0.0, velocity.z())
        if (!VehiclePhysicsMath.isFinite(planarVelocity) || planarVelocity.lengthSquared() < MIN_TRIGGER_SPEED * MIN_TRIGGER_SPEED) {
            return
        }

        val direction = planarVelocity.normalize()
        val speed = max(0.0, VehiclePhysicsMath.safeDot(velocity, direction))
        val speedScale = 1.0 - smoothstep(BOOST_TARGET_SPEED, BOOST_TARGET_SPEED + BOOST_FADE_RANGE, speed)
        if (speedScale <= 0.0) return

        val force = direction.mul(vehicle.vehicleDefinition.body.mass * BOOST_ACCELERATION * speedScale)
        VehiclePhysicsMath.safeApplyWorldForce(body, force, body.kinematics.position)
    }

    private fun playBoostSoundIfNeeded(level: ServerLevel, vehicle: IVehicle, body: VsBody) {
        if (!isOnCachedBoostPad(vehicle, body) || !isMovingFastEnoughForBoost(body)) return

        val now = level.gameTime
        val nextSoundTick = nextSoundTickByBody[vehicle.bodyId] ?: 0L
        if (now < nextSoundTick) return

        val position = body.kinematics.position
        level.playSound(
            null,
            position.x(),
            position.y(),
            position.z(),
            SkyridersMod.BOOST_SOUND.get(),
            SoundSource.BLOCKS,
            0.75f,
            1.0f
        )
        nextSoundTickByBody[vehicle.bodyId] = now + SOUND_COOLDOWN_TICKS
    }

    private fun isMovingFastEnoughForBoost(body: VsBody): Boolean {
        val velocity = body.kinematics.velocity
        val planarVelocity = Vector3d(velocity.x(), 0.0, velocity.z())
        return VehiclePhysicsMath.isFinite(planarVelocity) && planarVelocity.lengthSquared() >= MIN_TRIGGER_SPEED * MIN_TRIGGER_SPEED
    }

    private fun isOnCachedBoostPad(vehicle: IVehicle, body: VsBody): Boolean {
        val probes = wheelProbeLocalPositions(vehicle)
        val maxDrop = boostProbeDrop(vehicle)
        return probes.any { local ->
            val world = body.kinematics.transform.toWorld.transformPosition(Vector3d(local))
            hasCachedBoostPadBelow(vehicle.level.dimensionId, world, maxDrop)
        }
    }

    private fun wheelProbeLocalPositions(vehicle: IVehicle): List<Vector3d> {
        return when (val behavior = vehicle.vehicleDefinition.behavior) {
            is BikeVehicleBehaviorDefinition -> listOf(
                Vector3d(behavior.physics.frontWheelLocalPos),
                Vector3d(behavior.physics.rearWheelLocalPos)
            )
            is KartVehicleBehaviorDefinition -> behavior.physics.wheelLocalPositions.map(::Vector3d)
            is WheeledVehicleBehaviorDefinition -> behavior.physics.axles.flatMap { axle ->
                listOf(
                    Vector3d(-axle.halfTrackWidth, axle.localY, axle.localZ),
                    Vector3d(axle.halfTrackWidth, axle.localY, axle.localZ)
                )
            }
        }
    }

    private fun boostProbeDrop(vehicle: IVehicle): Double {
        return when (val behavior = vehicle.vehicleDefinition.behavior) {
            is BikeVehicleBehaviorDefinition ->
                behavior.physics.suspensionRestLength + behavior.physics.suspensionTravel + behavior.physics.wheelRadius + 0.3
            is KartVehicleBehaviorDefinition ->
                behavior.physics.suspensionRestLength + behavior.physics.suspensionTravel + behavior.physics.wheelRadius + 0.3
            is WheeledVehicleBehaviorDefinition -> {
                val maxReach = behavior.physics.axles.maxOf { axle ->
                    axle.suspensionRestLength + axle.suspensionTravel + axle.wheelRadius
                }
                maxReach + 0.3
            }
        }.coerceAtLeast(0.5)
    }

    private fun refreshCacheNearVehicle(level: ServerLevel, vehicle: IVehicle, body: VsBody) {
        val maxDrop = boostProbeDrop(vehicle)
        wheelProbeLocalPositions(vehicle).forEach { local ->
            val world = body.kinematics.transform.toWorld.transformPosition(Vector3d(local))
            refreshCacheBelow(level, world, maxDrop)
        }
    }

    private fun refreshCacheBelow(level: ServerLevel, worldPos: Vector3d, maxDrop: Double) {
        var drop = 0.0
        while (drop <= maxDrop) {
            val sample = Vector3d(worldPos.x, worldPos.y - drop, worldPos.z)
            val blockPos = BlockPos.containing(sample.x, sample.y, sample.z)
            if (hasBoostPadAtSample(level, sample, blockPos)) {
                cacheBoostPad(level, blockPos)
            } else {
                uncacheBoostPad(level, blockPos)
            }
            drop += PROBE_STEP
        }
    }

    private fun hasBoostPadAtSample(level: ServerLevel, worldSample: Vector3d, worldBlockPos: BlockPos): Boolean {
        if (isBoostPad(level.getBlockState(worldBlockPos).block)) {
            return true
        }

        val probe = AABBd(
            worldSample.x - SHIP_SAMPLE_RADIUS,
            worldSample.y - SHIP_SAMPLE_RADIUS,
            worldSample.z - SHIP_SAMPLE_RADIUS,
            worldSample.x + SHIP_SAMPLE_RADIUS,
            worldSample.y + SHIP_SAMPLE_RADIUS,
            worldSample.z + SHIP_SAMPLE_RADIUS
        )
        for (ship in level.getShipsIntersecting(probe)) {
            val local = ship.transform.worldToShip.transformPosition(worldSample, Vector3d())
            val shipyardPos = BlockPos.containing(local.x, local.y, local.z)
            if (isBoostPad(level.getBlockState(shipyardPos).block)) {
                return true
            }
        }
        return false
    }

    private fun hasCachedBoostPadBelow(dimensionId: DimensionId, worldPos: Vector3d, maxDrop: Double): Boolean {
        val pads = boostPadsByDimension[dimensionId] ?: return false
        var drop = 0.0
        while (drop <= maxDrop) {
            val blockPos = BlockPos.containing(worldPos.x, worldPos.y - drop, worldPos.z)
            if (pads.contains(blockPos.asLong())) {
                return true
            }
            drop += PROBE_STEP
        }
        return false
    }

    private fun isBoostPad(block: net.minecraft.world.level.block.Block): Boolean {
        return block == SkyridersMod.BOOST_PAD_BLOCK.get() || block == SkyridersMod.BOOST_PAD_FLOOR_BLOCK.get()
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        if (edge0 == edge1) return if (value < edge0) 0.0 else 1.0
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
