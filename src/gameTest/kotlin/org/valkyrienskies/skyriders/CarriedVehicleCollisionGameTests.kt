package org.valkyrienskies.skyriders

import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.MoverType
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.joml.Vector3d
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.skyriders.content.BikeInteractionHandler
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.VehicleInteractionZone
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.WheeledVehicleDefinitions
import org.valkyrienskies.skyriders.util.VehicleCollisionSweep
import java.util.UUID

@GameTestHolder("skyriders")
@PrefixGameTestTemplate(false)
class CarriedVehicleCollisionGameTests {
    companion object {
        @JvmStatic
        @GameTest(template = "empty", batch = "carry_collision")
        fun carrierCanMoveThroughItsOwnVehicle(helper: GameTestHelper) {
            val level = helper.level
            val origin = helper.absolutePos(BlockPos(5, 5, 5)).atY(290)
            val car = VehicleManager.createVehicle(WheeledVehicleDefinitions.CAR.id, level,
                Vector3d(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble()))
            val player = FakePlayerFactory.get(level, GameProfile(UUID.randomUUID(), "CarryCollision"))
            try {
                val center = car.getRenderTransform()!!.toWorld.transformPosition(Vector3d(0.0, 0.3, 0.0))
                player.moveTo(center.x, center.y - player.eyeHeight, center.z - 3.0, 0f, 0f)
                player.xo = player.x
                player.yo = player.y
                player.zo = player.z
                player.yRotO = player.yRot
                player.xRotO = player.xRot
                BikeInteractionHandler::class.java.getDeclaredMethod("startHoisting", ServerPlayer::class.java,
                    IVehicle::class.java, VehicleInteractionZone::class.java).apply { isAccessible = true }
                    .invoke(BikeInteractionHandler, player, car, null)
                helper.assertTrue(BikeInteractionHandler.saveCarryState(level.dimensionId, car.toVehicleSaveRecord())
                    .behaviorTag.contains("carry_original_static"), "Fixture failed to pick up vehicle")
                val start = player.position()
                val movement = Vec3(0.0, 0.2, 4.5)
                player.setOnGround(false)
                player.deltaMovement = movement
                player.move(MoverType.SELF, movement)
                helper.assertTrue(player.position().distanceTo(start.add(movement)) < 1e-8,
                    "Carried vehicle blocked carrier: expected=${start.add(movement)} actual=${player.position()}")
                helper.assertFalse(player.verticalCollision, "Carried vehicle caused a vertical collision")
                val observer = FakePlayerFactory.get(level, GameProfile(UUID.randomUUID(), "CarryObserver"))
                observer.moveTo(start.x, start.y, start.z)
                observer.setOnGround(false)
                observer.move(MoverType.SELF, movement)
                helper.assertTrue(observer.position().distanceTo(start.add(movement)) > 0.1,
                    "Carried vehicle stopped colliding with other players")
                BikeInteractionHandler.releaseOnExit(player.uuid)
                player.moveTo(start.x, start.y, start.z)
                player.setOnGround(false)
                player.move(MoverType.SELF, movement)
                helper.assertTrue(player.position().distanceTo(start.add(movement)) > 0.1,
                    "Released vehicle still ignored its former carrier")
                helper.succeed()
            } finally {
                BikeInteractionHandler.releaseOnExit(player.uuid)
                VehicleManager.removeVehicle(level, car.bodyId)
            }
        }

        @JvmStatic
        @GameTest(template = "empty", batch = "near_vehicle_collision")
        fun nonIntersectingVehicleDoesNotCatchFallingPlayer(helper: GameTestHelper) {
            val level = helper.level
            val origin = helper.absolutePos(BlockPos(5, 5, 5)).atY(290)
            val car = VehicleManager.createVehicle(WheeledVehicleDefinitions.CAR.id, level,
                Vector3d(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble()))
            val player = FakePlayerFactory.get(level, GameProfile(UUID.randomUUID(), "NearCollision"))
            try {
                val transform = car.getRenderTransform()!!
                var samples = 0
                for (x in listOf(-2.0, -1.3, -0.9, 0.0, 0.9, 1.3, 2.0)) {
                    for (y in listOf(-3.0, -2.0, 0.9, 1.3, 2.0, 3.0)) {
                        for (speed in listOf(0.13, 0.7, 1.1, 3.7, 8.3)) {
                            player.moveTo(origin.x + x, origin.y + y, origin.z - 3.0)
                            player.setOnGround(false)
                            val movement = Vec3(0.0, -0.78, speed)
                            if (VehicleCollisionSweep.cast(car.vehicleDefinition.body.resolvedCollisionBoxes(),
                                    transform, transform, player.boundingBox,
                                    Vector3d(movement.x, movement.y, movement.z)) != null) continue
                            samples++
                            player.deltaMovement = movement
                            val start = player.position()
                            player.move(MoverType.SELF, movement)
                            helper.assertFalse(player.verticalCollision || player.onGround(),
                                "Nearby vehicle caused phantom landing: x=$x y=$y speed=$speed start=$start actual=${player.position()}")
                            helper.assertTrue(player.deltaMovement.y == movement.y, "Nearby vehicle reset falling velocity")
                        }
                    }
                }
                helper.assertTrue(samples > 100, "Not enough nearby non-contact cases: $samples")
                helper.succeed()
            } finally {
                VehicleManager.removeVehicle(level, car.bodyId)
            }
        }
    }
}
