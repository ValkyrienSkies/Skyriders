package org.valkyrienskies.skyriders.content.item

import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.VsBody
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.SkyridersSounds
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity

class HoneyCanisterItem(properties: Properties) : RacingVehicleItem(properties) {
    override fun useOnVehicle(level: ServerLevel, player: Player, vehicle: IVehicle, stack: ItemStack): Boolean {
        playItemUseSound(level, player)
        level.playSound(
            null,
            player.x,
            player.y,
            player.z,
            SoundEvents.HONEY_DRINK,
            SoundSource.PLAYERS,
            0.9f,
            1.0f
        )
        VehicleStatusEffects.applyBoost(vehicle, duration = 0.95, acceleration = 36.0, targetSpeed = 34.0, fadeRange = 8.0)
        return true
    }
}

class ThunderboltItem(properties: Properties) : RacingVehicleItem(properties) {
    override fun useOnVehicle(level: ServerLevel, player: Player, vehicle: IVehicle, stack: ItemStack): Boolean {
        val sourceBody = level.shipWorld?.allBodies?.getById(vehicle.bodyId) ?: return false
        val sourcePosition = sourceBody.kinematics.position
        playThunderboltSound(level, player)

        VehicleManager.getVehicles(level).forEach { target ->
            if (target.bodyId == vehicle.bodyId) return@forEach
            val targetBody = level.shipWorld?.allBodies?.getById(target.bodyId) ?: return@forEach
            if (targetBody.kinematics.position.distanceSquared(sourcePosition) > RADIUS * RADIUS) return@forEach
            VehicleStatusEffects.applySpinOut(target, duration = 2.15, yawSpeed = 12.5)
        }
        return true
    }

    private fun playThunderboltSound(level: ServerLevel, player: Player) {
        level.playSound(
            null,
            player.x,
            player.y,
            player.z,
            SkyridersSounds.THUNDERBOLT_SOUND.get(),
            SoundSource.PLAYERS,
            0.9f,
            1.0f
        )
    }

    private companion object {
        const val RADIUS = 10.0
    }
}

class CavendishItem(properties: Properties) : RacingVehicleItem(properties) {
    override fun useOnVehicle(level: ServerLevel, player: Player, vehicle: IVehicle, stack: ItemStack): Boolean {
        val body = level.shipWorld?.allBodies?.getById(vehicle.bodyId) ?: return false
        val entity = SkyridersMod.CAVENDISH_ENTITY.get().create(level) ?: return false
        val forward = body.kinematics.transform.rotation.transform(Vector3d(0.0, 0.0, 1.0)).normalize()
        val spawnPosition = tossOrigin(body, forward)
        val tossVelocity = tossVelocity(body, forward)
        entity.ownerBodyId = vehicle.bodyId
        entity.toss(
            origin = net.minecraft.world.phys.Vec3(spawnPosition.x, spawnPosition.y, spawnPosition.z),
            velocity = net.minecraft.world.phys.Vec3(tossVelocity.x, tossVelocity.y, tossVelocity.z),
            yaw = level.random.nextFloat() * 360.0f
        )
        level.addFreshEntity(entity)
        playItemUseSound(level, player)
        return true
    }

    private fun tossOrigin(body: VsBody, forward: Vector3d): Vector3d {
        val transform = body.kinematics.transform
        return transform.toWorld.transformPosition(Vector3d())
            .sub(Vector3d(forward).mul(0.75))
            .add(0.0, 0.55, 0.0)
    }

    private fun tossVelocity(body: VsBody, forward: Vector3d): Vector3d {
        val inheritedVelocity = body.kinematics.velocity
        return Vector3d(forward).mul(-0.48)
            .add(0.0, 0.34, 0.0)
            .add(inheritedVelocity.x() / TICKS_PER_SECOND, inheritedVelocity.y() / TICKS_PER_SECOND, inheritedVelocity.z() / TICKS_PER_SECOND)
    }

    private companion object {
        const val TICKS_PER_SECOND = 20.0
    }
}

class SugarRocketItem(
    properties: Properties,
    private val homing: Boolean = false
) : RacingVehicleItem(properties) {
    override fun useOnVehicle(level: ServerLevel, player: Player, vehicle: IVehicle, stack: ItemStack): Boolean {
        val body = level.shipWorld?.allBodies?.getById(vehicle.bodyId) ?: return false
        val entity = SkyridersMod.SUGAR_ROCKET_ENTITY.get().create(level) ?: return false
        val direction = player.lookAngle.normalize()
        val forwardOffset = Vector3d(direction.x, direction.y, direction.z).mul(1.1)
        val bodyPosition = body.kinematics.position
        val inheritedVelocity = body.kinematics.velocity
        val origin = net.minecraft.world.phys.Vec3(
            bodyPosition.x() + forwardOffset.x,
            bodyPosition.y() + 0.55 + forwardOffset.y,
            bodyPosition.z() + forwardOffset.z
        )

        entity.ownerBodyId = vehicle.bodyId
        entity.homing = homing
        entity.launch(
            origin = origin,
            direction = direction,
            inheritedVelocity = net.minecraft.world.phys.Vec3(
                inheritedVelocity.x() / TICKS_PER_SECOND,
                inheritedVelocity.y() / TICKS_PER_SECOND,
                inheritedVelocity.z() / TICKS_PER_SECOND
            )
        )
        level.addFreshEntity(entity)
        playItemUseSound(level, player)
        return true
    }

    private companion object {
        const val TICKS_PER_SECOND = 20.0
    }
}

abstract class RacingVehicleItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true)
        }

        val serverLevel = level as? ServerLevel ?: return InteractionResultHolder.fail(stack)
        val vehicle = playerDriverVehicle(player) ?: return InteractionResultHolder.fail(stack)
        if (!useOnVehicle(serverLevel, player, vehicle, stack)) {
            return InteractionResultHolder.fail(stack)
        }

        if (!player.abilities.instabuild) {
            stack.shrink(1)
        }
        return InteractionResultHolder.success(stack)
    }

    protected abstract fun useOnVehicle(level: ServerLevel, player: Player, vehicle: IVehicle, stack: ItemStack): Boolean

    protected fun playItemUseSound(level: ServerLevel, player: Player) {
        level.playSound(
            null,
            player.x,
            player.y,
            player.z,
            SkyridersSounds.RACING_ITEM_USE_SOUND.get(),
            SoundSource.PLAYERS,
            0.65f,
            1.0f
        )
    }

    protected fun playerDriverVehicle(player: Player): IVehicle? {
        val seat = player.vehicle as? BikeSeatEntity ?: return null
        if (!seat.isDriverSeat()) return null
        return VehicleManager.getVehicle(player.level().dimensionId, seat.bodyId)
    }
}
