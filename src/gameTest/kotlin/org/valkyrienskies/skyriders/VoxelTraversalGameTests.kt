package org.valkyrienskies.skyriders

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.joml.Vector3d
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.skyriders.content.*
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@GameTestHolder("skyriders")
@PrefixGameTestTemplate(false)
class VoxelTraversalGameTests {
    private data class Lane(val type: ResourceLocation, val speed: Double, val stairs: Boolean)
    private data class Sample(val position: Vector3d, val velocity: Vector3d, val elapsed: Double, val peak: Double)

    companion object {
        @JvmStatic
        @GameTest(template = "traversal_course", timeoutTicks = 3000, batch = "traversal")
        fun vehiclesTraverseMinecraftBlocksAndSlabs(helper: GameTestHelper) {
            val types = listOf(BikeDefinitions.DEBUG_BIKE.id, KartDefinitions.DEBUG_KART.id,
                WheeledVehicleDefinitions.ATV.id, WheeledVehicleDefinitions.CAR.id, WheeledVehicleDefinitions.PICKUP_TRUCK.id)
            val lanes = types.map { Lane(it, 24.0, false) } + types.map { Lane(it, 24.0, true) } +
                listOf(Lane(WheeledVehicleDefinitions.CAR.id, 4.0, false), Lane(WheeledVehicleDefinitions.CAR.id, 12.0, false))
            val origin = helper.absolutePos(BlockPos(0, 2, 0))
            val level = helper.level
            val forcedChunks = mutableSetOf<Pair<Int, Int>>()
            for (x in 0..87) for (z in 0..95) {
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2)
                level.setBlock(origin.offset(x, 19, z), Blocks.STONE.defaultBlockState(), 2)
            }
            for (y in 0..18) {
                for (x in 0..87) for (z in listOf(0, 95)) level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2)
                for (z in 0..95) for (x in listOf(0, 87)) level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2)
            }
            for (x in 1..86) for (z in 1..94) for (y in 0..18) {
                level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2)
            }
            lanes.forEachIndexed { index, lane ->
                for (x in index * 7 + 1..index * 7 + 5) for (z in 1..90) {
                    val halfBlocks = if (z < 24) 0 else if (lane.stairs) ((z - 24) + 1).coerceAtMost(11) else 2
                    for (y in 0..halfBlocks / 2) level.setBlockAndUpdate(origin.offset(x, y, z), Blocks.STONE.defaultBlockState())
                    if (halfBlocks % 2 != 0) level.setBlockAndUpdate(origin.offset(x, halfBlocks / 2 + 1, z), Blocks.STONE_SLAB.defaultBlockState())
                    forcedChunks.add((origin.x + x shr 4) to (origin.z + z shr 4))
                }
            }
            forcedChunks.forEach { (x, z) -> level.setChunkForced(x, z, true) }
            run {
                val vehicles = lanes.mapIndexed { index, lane ->
                    VehicleManager.createVehicle(lane.type, level, Vector3d(origin.x + index * 7 + 3.5,
                        origin.y + 2.5, origin.z + 10.0))
                }
                val samples = ConcurrentHashMap<Long, Sample>()
                val error = AtomicReference<Throwable>()
                val dimension = level.dimensionId
                val listener = vsApi.physTickEvent.on { event ->
                    if (event.world.dimension != dimension || error.get() != null) return@on
                    try {
                        vehicles.forEachIndexed { index, vehicle ->
                            val body = event.world.getBodyById(vehicle.bodyId) ?: return@forEachIndexed
                            val previous = samples[vehicle.bodyId]
                            val elapsed = (previous?.elapsed ?: 0.0) + event.delta
                            val lane = lanes[index]
                            if (previous != null && previous.elapsed < 2.0 && elapsed >= 2.0) {
                                initializeDrive(vehicle, lane.speed)
                                VehicleManager.updateInput(dimension, vehicle.bodyId) { VehicleInput(throttle = 0.5, riderPresent = true) }
                                body.applyWorldForce(Vector3d(0.0, 0.0, lane.speed).sub(body.kinematics.velocity)
                                    .mul(vehicle.vehicleDefinition.body.mass / event.delta), body.kinematics.position)
                            }
                            samples[vehicle.bodyId] = Sample(Vector3d(body.kinematics.position), Vector3d(body.kinematics.velocity),
                                elapsed, maxOf(previous?.peak ?: Double.NEGATIVE_INFINITY, body.kinematics.position.y()))
                            if (System.getenv("SKYRIDERS_NATIVE_TRACE") == "1" && previous != null && elapsed.toInt() != previous.elapsed.toInt()) {
                                println("Voxel trace type=${lane.type} stairs=${lane.stairs} entry=${lane.speed} sample=${samples[vehicle.bodyId]} pivot=${body.kinematics.positionInModel} engine=${vehicle.vehicleState.engineOn}")
                            }
                        }
                    } catch (failure: Throwable) { error.set(failure) }
                }
                val completed = mutableSetOf<Long>()
                var cleaned = false
                fun cleanup() {
                    if (cleaned) return
                    cleaned = true
                    listener.unregister()
                    vehicles.forEach { VehicleManager.removeVehicle(level, it.bodyId) }
                    forcedChunks.forEach { (x, z) -> level.setChunkForced(x, z, false) }
                }
                helper.runAtTickTime(2990) { cleanup() }
                helper.onEachTick {
                    if (!cleaned) {
                        Thread.sleep(10)
                        try {
                            error.get()?.let { throw AssertionError("Physics callback failed", it) }
                            vehicles.forEachIndexed { index, vehicle ->
                                if (vehicle.bodyId in completed) return@forEachIndexed
                                val sample = samples[vehicle.bodyId] ?: return@forEachIndexed
                                val lane = lanes[index]
                                val fluid = level.getFluidState(BlockPos.containing(sample.position.x, sample.position.y, sample.position.z))
                                helper.assertTrue(fluid.isEmpty, "Voxel course flooded at ${sample.position}: $fluid")
                                helper.assertTrue(sample.position.isFinite, "Nonfinite ${lane.type} pose")
                                val height = if (lane.stairs) 5.5 else 1.0
                                helper.assertTrue(sample.peak < origin.y + height + 2.5 + if (lane.stairs) 8.0 else 0.0,
                                    "Excessive ${lane.type} launch: $sample")
                                if (sample.position.z > origin.z + 48.0) {
                                    helper.assertTrue(sample.position.y > origin.y + height + 1.0,
                                        "${lane.type} passed below the voxel course: $sample")
                                    helper.assertTrue(sample.velocity.z > lane.speed * 0.35,
                                        "${lane.type} lost speed on stairs=${lane.stairs}: $sample")
                                    println("Voxel traversal PASS type=${lane.type} stairs=${lane.stairs} entry=${lane.speed} sample=$sample")
                                    completed.add(vehicle.bodyId)
                                    VehicleManager.removeVehicle(level, vehicle.bodyId)
                                } else helper.assertTrue(sample.elapsed < 2.0 + 60.0 / lane.speed + 3.0,
                                    "${lane.type} failed stairs=${lane.stairs} entry=${lane.speed}: $sample")
                            }
                            if (completed.size == vehicles.size) { cleanup(); helper.succeed() }
                        } catch (failure: Throwable) { cleanup(); throw failure }
                    }
                }
            }
        }

        private fun initializeDrive(vehicle: IVehicle, speed: Double) = withVehicleState(vehicle) {
            when (vehicle) {
                is IBike -> {
                    vehicle.state.engineOn = true
                    vehicle.state.frontWheelAngularVelocity = speed / vehicle.config.wheelRadius
                    vehicle.state.rearWheelAngularVelocity = speed / vehicle.config.wheelRadius
                }
                is KartVehicle -> {
                    val config = (vehicle.vehicleDefinition.behavior as KartVehicleBehaviorDefinition).physics
                    vehicle.kartState.engineOn = true
                    vehicle.kartState.frontWheelAngularVelocity = speed / config.wheelRadius
                    vehicle.kartState.rearWheelAngularVelocity = speed / config.wheelRadius
                }
                is WheeledVehicle -> {
                    val config = (vehicle.vehicleDefinition.behavior as WheeledVehicleBehaviorDefinition).physics
                    val state = vehicle.wheeledState
                    state.engineOn = true
                    state.transmissionGear = config.transmission.forwardGears.indexOfFirst { it.maxSpeed >= speed }
                        .let { if (it < 0) config.transmission.forwardGears.size else it + 1 }
                    config.axles.forEach { axle ->
                        for (side in listOf("left", "right")) {
                            state.wheelAngularVelocityById["${axle.id}_$side"] = speed / axle.wheelRadius
                            state.visualWheelAngularVelocityById["${axle.id}_$side"] = speed / axle.wheelRadius
                        }
                    }
                    val gear = config.transmission.forwardGears[state.transmissionGear - 1]
                    state.engineRpm = gear.gearRatio?.let { speed / config.axles.first().wheelRadius * 60.0 / (2.0 * Math.PI) * it * config.transmission.finalDriveRatio }
                        ?: (speed / gear.maxSpeed * config.engine.redlineRpm)
                }
            }
        }
    }
}
