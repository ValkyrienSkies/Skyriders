package org.valkyrienskies.skyriders

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.Entity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.common.util.FakePlayerFactory
import com.mojang.authlib.GameProfile
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.block.Blocks
import org.valkyrienskies.mod.api.vsApi
import net.minecraft.nbt.CompoundTag
import org.joml.Vector3d
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.*
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle
import org.valkyrienskies.skyriders.content.vehicles.wheeledRuntimeStateFromTag

@GameTestHolder("skyriders")
@PrefixGameTestTemplate(false)
class AuditGameTests {
    companion object {
        @JvmStatic
        @GameTest(template = "empty")
        fun fastEntityCrossingStationaryCarTakesImpactDamage(helper: GameTestHelper) {
            val origin = helper.absolutePos(BlockPos(5, 5, 5))
            val car = VehicleManager.createVehicle(WheeledVehicleDefinitions.CAR.id, helper.level,
                Vector3d(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble()))
            val target = net.minecraft.world.entity.EntityType.PIG.create(helper.level)!!
            try {
                target.setNoGravity(true)
                target.isNoAi = true
                target.moveTo(origin.x.toDouble(), origin.y.toDouble(), origin.z + 6.0)
                helper.level.addFreshEntity(target)
                target.xo = origin.x.toDouble()
                target.yo = origin.y.toDouble()
                target.zo = origin.z - 6.0
                val health = target.health
                VehicleImpactDamageHandler.tick(helper.level, listOf(car))
                helper.assertTrue(target.health < health, "Fast target crossed a stationary car without impact damage")
                helper.succeed()
            } finally {
                target.discard()
                VehicleManager.removeVehicle(helper.level, car.bodyId)
            }
        }

        @JvmStatic
        @GameTest(template = "empty", timeoutTicks = 1200)
        fun carryRecoveryAndTeleportPreserveStaticState(helper: GameTestHelper) {
            val origin = helper.absolutePos(BlockPos(3, 5, 3))
            val vehicles = listOf(false, true).mapIndexed { index, _ ->
                VehicleManager.createVehicle(WheeledVehicleDefinitions.CAR.id, helper.level,
                    Vector3d(origin.x + index * 4.0, origin.y.toDouble(), origin.z.toDouble()))
            }
            val phase = AtomicInteger(0)
            val observed = AtomicReference<List<Pair<Boolean, Vector3d>>>()
            val listener = vsApi.physTickEvent.on { event ->
                if (event.world.dimension != helper.level.dimensionId) return@on
                val bodies = vehicles.map { event.world.getBodyById(it.bodyId) ?: return@on }
                if (phase.compareAndSet(0, 1)) bodies.forEach { it.isStatic = true }
                observed.set(bodies.map { it.isStatic to Vector3d(it.kinematics.position) })
            }
            var finished = false
            fun cleanup() {
                if (finished) return
                finished = true
                listener.unregister()
                vehicles.forEach { VehicleManager.removeVehicle(helper.level, it.bodyId) }
            }
            helper.runAtTickTime(1190) { cleanup() }
            helper.onEachTick {
                if (!finished) {
                    Thread.sleep(5)
                    val sample = observed.get()
                    when (phase.get()) {
                        1 -> if (sample?.all { it.first } == true) {
                            vehicles.forEachIndexed { index, vehicle ->
                                val saved = vehicle.toVehicleSaveRecord().let { record ->
                                    record.copy(behaviorTag = record.behaviorTag.copy().apply { putBoolean("carry_original_static", index == 1) })
                                }
                                BikeInteractionHandler.restoreCarryState(helper.level, saved)
                            }
                            phase.set(2)
                        }
                        2 -> if (sample != null && !sample[0].first && sample[1].first) {
                            vehicles.forEachIndexed { index, vehicle ->
                                val target = Vector3d(origin.x + index * 4.0, origin.y.toDouble(), origin.z + 3.0)
                                VehicleManager.teleportVehicle(helper.level, vehicle.bodyId, target)
                                target.zero()
                            }
                            phase.set(3)
                        }
                        3 -> if (sample?.all { kotlin.math.abs(it.second.z - (origin.z + 3.0)) < 0.01 } == true) {
                            helper.assertFalse(sample[0].first, "Teleport made a dynamic body static")
                            helper.assertTrue(sample[1].first, "Teleport made a static body dynamic")
                            cleanup()
                            helper.succeed()
                        }
                    }
                }
            }
        }

        @JvmStatic
        @GameTest(template = "empty", timeoutTicks = 600)
        fun brokenBikeEngineStopsOnPhysicsTick(helper: GameTestHelper) {
            val origin = helper.absolutePos(BlockPos(5, 5, 5))
            val bike = VehicleManager.createVehicle(BikeDefinitions.DEBUG_BIKE.id, helper.level,
                Vector3d(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())) as IBike
            withVehicleState(bike) {
                bike.state.engineOn = true
                bike.state.partStates.getValue(VehicleDamage.ENGINE_PART_ID).data.putDouble("health", 0.0)
            }
            helper.succeedWhen {
                helper.assertFalse(withVehicleState(bike) { bike.state.engineOn }, "Destroyed bike engine continued running")
                helper.assertFalse(VehicleDamage.canEngineStart(bike), "Destroyed bike engine can restart")
                VehicleManager.removeVehicle(helper.level, bike.bodyId)
            }
        }

        @JvmStatic
        @GameTest(template = "empty")
        fun projectileChoosesFirstVehicleOrWall(helper: GameTestHelper) {
            val origin = helper.absolutePos(BlockPos(5, 3, 5))
            val car = VehicleManager.createVehicle(WheeledVehicleDefinitions.CAR.id, helper.level,
                Vector3d(origin.x + 0.5, origin.y + 0.0, origin.z + 0.5))
            val rocket = SkyridersMod.SUGAR_ROCKET_ENTITY.get().create(helper.level)!!
            val start = Vec3(origin.x + 0.5, origin.y + 0.3, origin.z - 4.0)
            val end = Vec3(origin.x + 0.5, origin.y + 0.3, origin.z + 5.0)
            helper.level.setBlockAndUpdate(origin.offset(0, 0, 3), Blocks.STONE.defaultBlockState())
            val vehicleHit = VehicleProjectileHits.firstHit(helper.level, rocket, start, end, 0.15, -1L)
            helper.assertTrue(vehicleHit?.vehicle == car, "Farther wall won over nearer swept vehicle")
            helper.assertTrue(vehicleHit!!.fraction < 0.5, "Vehicle hit used projectile endpoint")
            helper.level.setBlockAndUpdate(origin.offset(0, 0, -2), Blocks.STONE.defaultBlockState())
            val wallHit = VehicleProjectileHits.firstHit(helper.level, rocket, start, end, 0.15, -1L)
            helper.assertTrue(wallHit != null && wallHit.vehicle == null, "Projectile passed through nearer wall")
            VehicleManager.removeVehicle(helper.level, car.bodyId)
            helper.succeed()
        }

        @JvmStatic
        @GameTest(template = "empty")
        fun simultaneousFuelUpdatesAndSaveSnapshots(helper: GameTestHelper) {
            val definition = WheeledVehicleDefinitions.CAR.copy(fuel = VehicleFuelDefinition(capacity = 1000.0))
            val vehicle = WheeledVehicle(-1L, helper.level, definition,
                WheeledVehicleRuntimeState(engineOn = true, fuelAmount = 100.0))
            val iterations = 10000
            val pool = Executors.newFixedThreadPool(2)
            try {
                val additions = pool.submit<Double> {
                    var added = 0.0
                    repeat(iterations) { added += VehicleFuel.refillAmount(vehicle, 0.001) }
                    added
                }
                val consumption = pool.submit {
                    repeat(iterations) { VehicleFuel.consume(vehicle, VehicleInput(throttle = 1.0), 0.001) }
                }
                val added = additions.get(5, TimeUnit.SECONDS)
                consumption.get(5, TimeUnit.SECONDS)
                val expected = 100.0 + added - (definition.fuel.idleUsePerSecond + definition.fuel.throttleUsePerSecond) * iterations * 0.001
                helper.assertTrue(kotlin.math.abs(VehicleFuel.amount(vehicle) - expected) < 1.0e-7,
                    "Concurrent refill and consumption lost fuel updates")
                vehicle.wheeledState.partStates["test"] = VehiclePartState("test", CompoundTag().apply { putInt("value", 1) })
                val snapshot = vehicle.toVehicleSaveRecord()
                vehicle.wheeledState.partStates.getValue("test").data.putInt("value", 2)
                helper.assertTrue(snapshot.partStates.getValue("test").data.getInt("value") == 1,
                    "Saved part data aliases live runtime state")
                helper.succeed()
            } finally {
                pool.shutdownNow()
            }
        }

        @JvmStatic
        @GameTest(template = "empty")
        fun interactionRayStopsAtSolidBlocks(helper: GameTestHelper) {
            val origin = helper.absolutePos(BlockPos(5, 3, 5))
            val car = VehicleManager.createVehicle(WheeledVehicleDefinitions.CAR.id, helper.level,
                Vector3d(origin.x + 0.5, origin.y + 0.0, origin.z + 0.5))
            val start = Vec3(origin.x + 0.5, origin.y + 0.3, origin.z - 4.0)
            val end = Vec3(origin.x + 0.5, origin.y + 0.3, origin.z + 2.0)
            helper.assertTrue(VehicleInteractionPicker.findVehicleOnRay(helper.level, start, end)?.vehicle == car,
                "Unobstructed interaction fixture missed the car")
            helper.level.setBlockAndUpdate(origin.offset(0, 0, -2), Blocks.STONE.defaultBlockState())
            helper.assertTrue(VehicleInteractionPicker.findVehicleOnRay(helper.level, start, end) == null,
                "Interaction selected a car behind a solid block")
            VehicleManager.removeVehicle(helper.level, car.bodyId)
            helper.succeed()
        }

        @JvmStatic
        @GameTest(template = "empty", timeoutTicks = 120)
        fun interruptedCarryRestoresDynamics(helper: GameTestHelper) {
            val level = helper.level
            val origin = helper.absolutePos(BlockPos(5, 6, 5))
            val vehicle = VehicleManager.createVehicle(WheeledVehicleDefinitions.CAR.id, level,
                Vector3d(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble()))
            val player = FakePlayerFactory.get(level, GameProfile(UUID.randomUUID(), "CarryTest"))
            val dimension = level.dimensionId
            val bodyId = vehicle.bodyId
            val observedStatic = AtomicReference<Boolean>()
            val listener = vsApi.physTickEvent.on { event ->
                if (event.world.dimension == dimension) {
                    event.world.getBodyById(bodyId)?.let { observedStatic.set(it.isStatic) }
                }
            }
            player.moveTo(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())
            helper.runAfterDelay(10) {
                val start = BikeInteractionHandler::class.java.getDeclaredMethod("startHoisting",
                    net.minecraft.server.level.ServerPlayer::class.java, IVehicle::class.java,
                    VehicleInteractionZone::class.java)
                start.isAccessible = true
                start.invoke(BikeInteractionHandler, player, vehicle, null)
                helper.runAfterDelay(10) {
                    helper.assertTrue(observedStatic.get() == true,
                        "Carry did not pin the body through physics")
                    BikeInteractionHandler.releaseOnExit(player.uuid)
                    helper.runAfterDelay(10) {
                        listener.unregister()
                        helper.assertTrue(observedStatic.get() == false,
                            "Interrupted carry stranded the body as static")
                        VehicleManager.removeVehicle(level, vehicle.bodyId)
                        helper.succeed()
                    }
                }
            }
        }

        @JvmStatic
        @GameTest(template = "empty")
        fun invalidSavedNumbersAreSanitized(helper: GameTestHelper) {
            val definition = WheeledVehicleDefinitions.CAR
            val tag = CompoundTag().apply {
                putInt("transmission_gear", Int.MAX_VALUE)
                putDouble("engine_rpm", Double.POSITIVE_INFINITY)
                putDouble("front_wheel_spin", Double.NaN)
                putDouble("rear_wheel_angular_velocity", Double.NEGATIVE_INFINITY)
                put("wheel_spin_by_id", CompoundTag().apply { putDouble("front_left", Double.NaN) })
            }
            val loaded = wheeledRuntimeStateFromTag(definition, tag, false, 10.0, partStates = emptyMap())
            val physics = (definition.behavior as WheeledVehicleBehaviorDefinition).physics
            helper.assertTrue(loaded.transmissionGear == physics.transmission.forwardGears.size, "Gear outside transmission range")
            helper.assertTrue(loaded.engineRpm == physics.engine.idleRpm, "Invalid saved RPM survived restore")
            helper.assertTrue(loaded.frontWheelSpin == 0.0 && loaded.rearWheelAngularVelocity == 0.0,
                "Nonfinite wheel state survived restore")
            helper.assertTrue(loaded.wheelSpinById.values.all(Double::isFinite), "Nonfinite wheel map survived restore")
            helper.succeed()
        }

        @JvmStatic
        @GameTest(template = "empty", timeoutTicks = 120)
        fun passengerCleanupPreservesDriverInput(helper: GameTestHelper) {
            val level = helper.level
            val origin = helper.absolutePos(BlockPos(5, 3, 5))
            val truck = VehicleManager.createVehicle(WheeledVehicleDefinitions.PICKUP_TRUCK.id, level,
                Vector3d(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble()))
            val held = VehicleInput(throttle = 0.8, brake = 0.3, riderPresent = true)
            VehicleManager.updateInput(level.dimensionId, truck.bodyId) { held }
            val passengerSeat = BikeSeatEntity(SkyridersMod.BIKE_SEAT_ENTITY.get(), level)
            passengerSeat.bodyId = truck.bodyId
            passengerSeat.seatId = "passenger"
            helper.assertFalse(passengerSeat.isDriverSeat(), "Fixture must use a passenger seat")
            passengerSeat.remove(Entity.RemovalReason.DISCARDED)
            helper.assertTrue(VehicleManager.getInput(level.dimensionId, truck.bodyId) == held,
                "Passenger removal erased held driver input")
            val driverSeat = BikeSeatEntity(SkyridersMod.BIKE_SEAT_ENTITY.get(), level)
            driverSeat.bodyId = truck.bodyId
            driverSeat.seatId = "driver"
            driverSeat.remove(Entity.RemovalReason.DISCARDED)
            helper.assertTrue(VehicleManager.getInput(level.dimensionId, truck.bodyId) == VehicleInput.EMPTY,
                "Driver removal did not clear input")
            VehicleManager.removeVehicle(level, truck.bodyId)
            helper.succeed()
        }

        @JvmStatic
        @GameTest(template = "empty", timeoutTicks = 120)
        fun neutralPersistenceAndNativePhysicsTick(helper: GameTestHelper) {
            val level = helper.level
            val origin = helper.absolutePos(BlockPos(5, 6, 5))
            val car = VehicleManager.createVehicle(WheeledVehicleDefinitions.CAR.id, level,
                Vector3d(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())) as WheeledVehicle
            car.wheeledState.transmissionGear = 0
            val saved = car.toVehicleSaveRecord()
            val loaded = wheeledRuntimeStateFromTag(car.vehicleDefinition, saved.behaviorTag, saved.engineOn,
                saved.fuelAmount, saved.raceParticipant, saved.raceColorId, saved.partStates)
            helper.assertTrue(loaded.transmissionGear == 0, "Neutral gear did not survive save/restore")
            val before = VehicleFuel.amount(car)
            helper.runAfterDelay(40) {
                val body = level.shipWorld?.allBodies?.getById(car.bodyId)
                helper.assertTrue(body != null, "VS body disappeared before physics test")
                helper.assertTrue(body!!.kinematics.position.y() < origin.y - 0.1, "VS physics did not advance the spawned car")
                helper.assertTrue(VehicleFuel.amount(car) == before, "Engine-off car consumed fuel")
                VehicleManager.removeVehicle(level, car.bodyId)
                helper.succeed()
            }
        }
    }
}
