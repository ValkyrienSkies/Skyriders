package org.valkyrienskies.skyriders.content.item

import net.minecraft.core.BlockSource
import net.minecraft.core.Direction
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.DispenserBlock
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.skyriders.SkyridersMod

object RacingDispenserBehaviors {
    fun register() {
        DispenserBlock.registerBehavior(SkyridersMod.SUGAR_ROCKET.get(), SugarRocketDispenseBehavior(homing = false))
        DispenserBlock.registerBehavior(SkyridersMod.HOMING_SUGAR_ROCKET.get(), SugarRocketDispenseBehavior(homing = true))
        DispenserBlock.registerBehavior(SkyridersMod.FAKE_ITEM_BOX.get(), FakeItemBoxDispenseBehavior)
        DispenserBlock.registerBehavior(SkyridersMod.CAVENDISH.get(), CavendishDispenseBehavior)
    }

    private class SugarRocketDispenseBehavior(private val homing: Boolean) : DefaultDispenseItemBehavior() {
        override fun execute(source: BlockSource, stack: ItemStack): ItemStack {
            val level = source.level as? ServerLevel ?: return stack
            val direction = source.direction()
            val entity = SkyridersMod.SUGAR_ROCKET_ENTITY.get().create(level) ?: return stack
            entity.homing = homing
            entity.launch(
                origin = source.launchOrigin(direction, 0.82),
                direction = direction.asVec3()
            )
            level.addFreshEntity(entity)
            stack.shrink(1)
            return stack
        }
    }

    private object FakeItemBoxDispenseBehavior : DefaultDispenseItemBehavior() {
        override fun execute(source: BlockSource, stack: ItemStack): ItemStack {
            val level = source.level as? ServerLevel ?: return stack
            val direction = source.direction()
            val entity = SkyridersMod.FAKE_ITEM_BOX_ENTITY.get().create(level) ?: return stack
            entity.launch(
                origin = source.launchOrigin(direction, 0.72),
                velocity = tossedVelocity(direction, horizontalLift = 0.2, speed = 0.46),
                ownerBodyId = 0L,
                yaw = level.random.nextFloat() * 360.0f
            )
            level.addFreshEntity(entity)
            stack.shrink(1)
            return stack
        }
    }

    private object CavendishDispenseBehavior : DefaultDispenseItemBehavior() {
        override fun execute(source: BlockSource, stack: ItemStack): ItemStack {
            val level = source.level as? ServerLevel ?: return stack
            val direction = source.direction()
            val entity = SkyridersMod.CAVENDISH_ENTITY.get().create(level) ?: return stack
            entity.ownerBodyId = 0L
            entity.toss(
                origin = source.launchOrigin(direction, 0.72),
                velocity = tossedVelocity(direction, horizontalLift = 0.24, speed = 0.5),
                yaw = level.random.nextFloat() * 360.0f
            )
            level.addFreshEntity(entity)
            stack.shrink(1)
            return stack
        }
    }

    private fun BlockSource.direction(): Direction {
        return blockState.getValue(DispenserBlock.FACING)
    }

    private fun BlockSource.launchOrigin(direction: Direction, distance: Double): Vec3 {
        return Vec3.atCenterOf(pos).add(direction.asVec3().scale(distance))
    }

    private fun Direction.asVec3(): Vec3 {
        return Vec3(stepX.toDouble(), stepY.toDouble(), stepZ.toDouble())
    }

    private fun tossedVelocity(direction: Direction, horizontalLift: Double, speed: Double): Vec3 {
        val facing = direction.asVec3().scale(speed)
        val lift = if (direction.axis.isHorizontal) horizontalLift else 0.0
        return facing.add(0.0, lift, 0.0)
    }
}
