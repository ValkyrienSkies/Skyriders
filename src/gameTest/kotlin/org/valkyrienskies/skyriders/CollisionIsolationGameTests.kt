package org.valkyrienskies.skyriders

import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.MoverType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.joml.Vector3d
import org.joml.Vector3i
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.yRange
import org.valkyrienskies.skyriders.content.VehicleImpactDamageHandler
import org.valkyrienskies.skyriders.content.VehicleDamage
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.WheeledVehicleDefinitions
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

@GameTestHolder("skyriders")
@PrefixGameTestTemplate(false)
class CollisionIsolationGameTests {
    companion object {
        @JvmStatic
        @GameTest(template = "empty", batch = "vehicle_collision")
        fun playerStillCollidesWithVehicleSides(helper: GameTestHelper) {
            val level = helper.level
            val origin = helper.absolutePos(BlockPos(5, 5, 5)).atY(level.maxBuildHeight - 20)
            val car = VehicleManager.createVehicle(WheeledVehicleDefinitions.CAR.id, level,
                Vector3d(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble()))
            val player = FakePlayerFactory.get(level, GameProfile(UUID.randomUUID(), "VehicleCollision"))
            try {
                player.moveTo(origin.x - 4.0, origin.y - 0.1, origin.z.toDouble())
                player.setOnGround(false)
                val movement = Vec3(4.5, -0.08, 0.13)
                player.deltaMovement = movement
                player.move(MoverType.SELF, movement)
                helper.assertTrue(player.x < origin.x - 0.5, "Player passed through a vehicle")
                helper.assertFalse(player.onGround(), "Side collision produced a phantom landing")
                helper.assertFalse(player.verticalCollision, "Side collision altered unblocked vertical movement")
                helper.assertTrue(player.deltaMovement.y == movement.y, "Side collision reset vertical velocity")
                helper.succeed()
            } finally {
                VehicleManager.removeVehicle(level, car.bodyId)
                VehicleImpactDamageHandler.updateCollisionListener()
            }
        }

        @JvmStatic
        @GameTest(template = "empty", batch = "vehicle_crash", timeoutTicks = 1200)
        fun nativeVehicleCollisionsStillApplyCrashDamage(helper: GameTestHelper) {
            val level = helper.level
            val origin = helper.absolutePos(BlockPos(5, 5, 5)).atY(level.maxBuildHeight - 20)
            val cars = listOf(-4.0, 4.0).map { z ->
                VehicleManager.createVehicle(WheeledVehicleDefinitions.CAR.id, level,
                    Vector3d(origin.x.toDouble(), origin.y.toDouble(), origin.z + z))
            }
            val launched = AtomicBoolean(false)
            val listener = vsApi.physTickEvent.on { event ->
                if (event.world.dimension != level.dimensionId) return@on
                val bodies = cars.map { event.world.getBodyById(it.bodyId) ?: return@on }
                if (launched.compareAndSet(false, true)) {
                    bodies.forEachIndexed { index, body ->
                        body.applyWorldForce(Vector3d(0.0, 0.0,
                            (if (index == 0) 20.0 else -20.0) * body.inertiaData.mass / event.delta))
                    }
                }
            }
            var finished = false
            fun cleanup() {
                if (finished) return
                finished = true
                listener.unregister()
                cars.forEach { VehicleManager.removeVehicle(level, it.bodyId) }
                VehicleImpactDamageHandler.updateCollisionListener()
            }
            helper.runAtTickTime(1190) { cleanup() }
            helper.onEachTick {
                if (!finished) {
                    Thread.sleep(5)
                    if (cars.all { VehicleDamage.healthFraction(it, VehicleDamage.BODY_PART_ID) < 1.0 }) {
                        cleanup()
                        helper.succeed()
                    }
                }
            }
        }

        @JvmStatic
        @GameTest(template = "empty", batch = "collision_isolation")
        fun freeFallDoesNotLandWithoutVehiclesOrNearDistantVehicles(helper: GameTestHelper) {
            val level = helper.level
            val origin = helper.absolutePos(BlockPos(5, 5, 5)).atY(level.maxBuildHeight - 20)
            val player = FakePlayerFactory.get(level, GameProfile(UUID.randomUUID(), "CollisionTest"))
            fun checkFall() {
                for (speed in listOf(0.7, 1.1, 3.7, 8.3)) {
                    val movement = Vec3(speed, -0.78, 0.13)
                    player.moveTo(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())
                    player.setOnGround(false)
                    player.deltaMovement = movement
                    helper.assertTrue(level.noCollision(player, player.boundingBox.expandTowards(movement)),
                        "Free-fall fixture intersects existing terrain")
                    player.move(MoverType.SELF, movement)
                    helper.assertFalse(player.onGround(), "Free fall produced a phantom landing at speed $speed: " +
                        "origin=$origin position=${player.position()} box=${player.boundingBox} " +
                        "vertical=${player.verticalCollision} velocity=${player.deltaMovement} " +
                        "worldClear=${level.noCollision(player, player.boundingBox.expandTowards(movement))}")
                    helper.assertFalse(player.verticalCollision, "Free fall reported a vertical collision at speed $speed")
                    helper.assertTrue(player.deltaMovement.y == movement.y, "Free fall reset vertical velocity")
                }
            }
            helper.assertFalse(VehicleManager.hasServerVehicles(), "Isolation batch started with vehicles")
            checkFall()
            val car = VehicleManager.createVehicle(WheeledVehicleDefinitions.CAR.id, level,
                Vector3d(origin.x + 100.0, origin.y.toDouble(), origin.z.toDouble()))
            try {
                checkFall()
                helper.succeed()
            } finally {
                VehicleManager.removeVehicle(level, car.bodyId)
                VehicleImpactDamageHandler.updateCollisionListener()
            }
        }

        @JvmStatic
        @GameTest(template = "empty", batch = "ordinary_ship_isolation", timeoutTicks = 1200)
        fun ordinaryShipBlockEditsReachPhysicsWithoutVehicles(helper: GameTestHelper) {
            val level = helper.level
            helper.assertFalse(VehicleManager.hasServerVehicles(), "Isolation batch started with vehicles")
            val origin = helper.absolutePos(BlockPos(5, 50, 5))
            val world = level.shipObjectWorld
            val ship = world.createNewShipAtBlock(Vector3i(origin.x, origin.y, origin.z), false, 1.0, level.dimensionId)
            val center = ship.chunkClaim.getCenterBlockCoordinates(level.yRange, Vector3i())
            val block = BlockPos(center.x, center.y, center.z)
            level.setBlockAndUpdate(block, Blocks.STONE.defaultBlockState())
            val sample = AtomicReference<Pair<Double, Double>>()
            val listener = vsApi.physTickEvent.on { event ->
                if (event.world.dimension != level.dimensionId) return@on
                val body = event.world.getBodyById(ship.id) ?: return@on
                sample.set(body.inertiaData.mass to body.kinematics.position.y())
            }
            var phase = 0
            var initialMass = 0.0
            var initialY = 0.0
            var finished = false
            fun cleanup() {
                if (finished) return
                finished = true
                listener.unregister()
                level.setBlockAndUpdate(block, Blocks.AIR.defaultBlockState())
                level.setBlockAndUpdate(block.above(), Blocks.AIR.defaultBlockState())
                world.deleteShip(ship)
            }
            helper.runAtTickTime(1190) { cleanup() }
            helper.onEachTick {
                if (!finished) {
                    Thread.sleep(5)
                    val state = sample.get()
                    if (state != null) when (phase) {
                        0 -> if (state.first > 0.0) {
                            initialMass = state.first
                            initialY = state.second
                            level.setBlockAndUpdate(block.above(), Blocks.STONE.defaultBlockState())
                            phase = 1
                        }
                        1 -> if (state.first > initialMass * 1.5) {
                            level.setBlockAndUpdate(block.above(), Blocks.AIR.defaultBlockState())
                            phase = 2
                        }
                        2 -> if (kotlin.math.abs(state.first - initialMass) < 1.0e-6 && state.second < initialY - 0.1) {
                            cleanup()
                            helper.succeed()
                        }
                    }
                }
            }
        }
    }
}
