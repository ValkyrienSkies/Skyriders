package org.valkyrienskies.skyriders

import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.common.ForgeMod
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3i
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.toWorldCoordinates
import org.valkyrienskies.mod.common.yRange
import org.valkyrienskies.skyriders.content.VehicleDamage
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.WheeledVehicleDefinitions
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@GameTestHolder("skyriders")
@PrefixGameTestTemplate(false)
class ShipInteractionGameTests {
    companion object {
        @JvmStatic
        @GameTest(template = "empty", batch = "ship_interactions", timeoutTicks = 600)
        fun shipBlockClicksDoNotStallOrBypassVehicleOcclusion(helper: GameTestHelper) =
            checkShipInteractions(helper, 1.0, Quaterniond())

        @JvmStatic
        @GameTest(template = "empty", batch = "scaled_ship_interactions", timeoutTicks = 600)
        fun rotatedScaledShipBlockClicksUseWorldCoordinates(helper: GameTestHelper) =
            checkShipInteractions(helper, 2.0, Quaterniond().rotateY(0.7).rotateZ(0.3))

        private fun checkShipInteractions(helper: GameTestHelper, scale: Double, rotation: Quaterniond) {
            val level = helper.level
            val world = level.shipObjectWorld
            val origin = helper.absolutePos(BlockPos(5, 5, 5)).atY(280)
            helper.assertFalse(VehicleManager.hasServerVehicles(), "Ship interaction batch started with vehicles")
            val ship = world.createNewShipAtBlock(Vector3i(origin.x, origin.y, origin.z), false, scale, level.dimensionId)
            val center = ship.chunkClaim.getCenterBlockCoordinates(level.yRange, Vector3i())
            val block = BlockPos(center.x, center.y, center.z)
            level.setBlockAndUpdate(block, Blocks.STONE.defaultBlockState())
            level.setBlockAndUpdate(block.below(), Blocks.STONE.defaultBlockState())
            val rotated = AtomicBoolean(false)
            val listener = vsApi.physTickEvent.on { event ->
                if (event.world.dimension != level.dimensionId) return@on
                val body = event.world.getBodyById(ship.id) ?: return@on
                body.isStatic = true
                if (rotated.compareAndSet(false, true)) body.setRotation(rotation)
            }
            val player = FakePlayerFactory.get(level, GameProfile(UUID.randomUUID(), "ShipClickTest"))
            player.getAttribute(ForgeMod.BLOCK_REACH.get())!!.baseValue = 6.0
            var finished = false
            fun cleanup() {
                if (finished) return
                finished = true
                listener.unregister()
                level.setBlockAndUpdate(block, Blocks.AIR.defaultBlockState())
                level.setBlockAndUpdate(block.below(), Blocks.AIR.defaultBlockState())
                world.deleteShip(ship)
            }
            helper.runAtTickTime(590) { cleanup() }
            helper.onEachTick {
                if (!finished && rotated.get() && kotlin.math.abs(ship.transform.shipToWorldRotation.dot(rotation)) > 0.9999) {
                    try {
                        val target = level.toWorldCoordinates(block.center)
                        player.moveTo(target.x, target.y - player.eyeHeight, target.z - 6.0)
                        fun click(pos: BlockPos = block): PlayerInteractEvent.LeftClickBlock {
                            val event = PlayerInteractEvent.LeftClickBlock(player, pos, Direction.NORTH,
                                PlayerInteractEvent.LeftClickBlock.Action.START)
                            MinecraftForge.EVENT_BUS.post(event)
                            return event
                        }
                        val chunksBefore = level.chunkSource.loadedChunksCount
                        repeat(10) {
                            helper.assertFalse(click().isCanceled, "Empty-world ship click was intercepted")
                            helper.assertTrue(level.destroyBlock(block, false), "Ship block could not be broken")
                            level.setBlockAndUpdate(block, Blocks.STONE.defaultBlockState())
                        }
                        helper.assertTrue(level.chunkSource.loadedChunksCount == chunksBefore,
                            "Ship interaction loaded unrelated terrain chunks")

                        val distant = VehicleManager.createVehicle(WheeledVehicleDefinitions.CAR.id, level,
                            Vector3d(target.x + 100.0, target.y, target.z))
                        try {
                            helper.assertFalse(click().isCanceled, "Distant vehicle intercepted ship click")
                        } finally {
                            VehicleManager.removeVehicle(level, distant.bodyId)
                        }

                        val car = VehicleManager.createVehicle(WheeledVehicleDefinitions.CAR.id, level,
                            Vector3d(target.x, target.y - 0.3, target.z - 2.0))
                        val wall = BlockPos.containing(target.x, target.y, target.z - 5.0)
                        try {
                            helper.assertTrue(click().isCanceled, "Vehicle between player and ship was missed")
                            helper.assertTrue(VehicleDamage.healthFraction(car, VehicleDamage.BODY_PART_ID) < 1.0,
                                "Ship click did not damage the intervening vehicle")
                            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState())
                            helper.assertFalse(click().isCanceled, "Ship click hit a vehicle through terrain")
                            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState())
                            player.moveTo(target.x, target.y - player.eyeHeight, target.z - 100.0)
                            helper.assertFalse(click().isCanceled, "Out-of-reach block packet attacked a vehicle")
                        } finally {
                            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState())
                            VehicleManager.removeVehicle(level, car.bodyId)
                        }
                        helper.succeed()
                    } finally {
                        cleanup()
                    }
                }
            }
        }
    }
}
