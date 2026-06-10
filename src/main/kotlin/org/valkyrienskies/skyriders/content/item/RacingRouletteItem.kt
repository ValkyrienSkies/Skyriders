package org.valkyrienskies.skyriders.content.item

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.client.model.geom.EntityModelSet
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.extensions.common.IClientItemExtensions
import net.minecraftforge.fml.DistExecutor
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.client.RacingRouletteClientSounds
import org.valkyrienskies.skyriders.content.SkyridersSounds
import java.util.function.Consumer

class RacingRouletteItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        return InteractionResultHolder.fail(player.getItemInHand(hand))
    }

    override fun inventoryTick(stack: ItemStack, level: Level, entity: Entity, slotId: Int, isSelected: Boolean) {
        if (level.isClientSide) {
            if (entity is Player) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable { RacingRouletteClientSounds.ensureSpinLoop() }
                }
            }
            return
        }
        if (entity !is Player) return
        val tag = stack.orCreateTag
        val age = tag.getInt(AGE_KEY) + 1
        tag.putInt(AGE_KEY, age)
        if (age < DURATION_TICKS) return
        val reward = rewardStack(stack)
        if (reward.isEmpty) {
            stack.shrink(1)
            return
        }
        level.playSound(
            null,
            entity.x,
            entity.y,
            entity.z,
            SkyridersSounds.ROULETTE_WIN_SOUND.get(),
            SoundSource.PLAYERS,
            0.72f,
            1.0f
        )
        entity.inventory.setItem(slotId, reward)
    }

    override fun initializeClient(consumer: Consumer<IClientItemExtensions>) {
        consumer.accept(object : IClientItemExtensions {
            override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer {
                return RacingRouletteItemRenderer.instance()
            }
        })
    }

    companion object {
        private const val REWARD_KEY = "Reward"
        private const val AGE_KEY = "Age"
        private const val DURATION_TICKS = 45

        fun create(reward: ItemStack): ItemStack {
            val roulette = ItemStack(SkyridersMod.RACING_ROULETTE.get())
            roulette.orCreateTag.put(REWARD_KEY, reward.copy().save(net.minecraft.nbt.CompoundTag()))
            roulette.orCreateTag.putInt(AGE_KEY, 0)
            return roulette
        }

        fun rewardStack(stack: ItemStack): ItemStack {
            val rewardTag = stack.tag?.getCompound(REWARD_KEY) ?: return ItemStack.EMPTY
            return ItemStack.of(rewardTag)
        }

        fun displayStack(stack: ItemStack): ItemStack {
            val minecraft = Minecraft.getInstance()
            val ticks = minecraft.level?.gameTime ?: (System.currentTimeMillis() / 50L)
            val options = listOf(
                SkyridersMod.HONEY_CANISTER.get(),
                SkyridersMod.CAVENDISH.get(),
                SkyridersMod.SUGAR_ROCKET.get(),
                SkyridersMod.HOMING_SUGAR_ROCKET.get(),
                SkyridersMod.THUNDERBOLT.get()
            )
            val index = ((ticks / 3L) % options.size).toInt()
            return ItemStack(options[index])
        }
    }
}

private class RacingRouletteItemRenderer(
    dispatcher: BlockEntityRenderDispatcher,
    modelSet: EntityModelSet
) : BlockEntityWithoutLevelRenderer(dispatcher, modelSet) {
    override fun renderByItem(
        stack: ItemStack,
        displayContext: ItemDisplayContext,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        val minecraft = Minecraft.getInstance()
        poseStack.pushPose()
        // BEWLR is invoked after ItemRenderer already centers the custom item model at -0.5.
        // Undo that before delegating to the borrowed reward item's normal renderer.
        poseStack.translate(0.5, 0.5, 0.5)
        minecraft.itemRenderer.renderStatic(
            RacingRouletteItem.displayStack(stack),
            displayContext,
            packedLight,
            packedOverlay,
            poseStack,
            buffer,
            minecraft.level,
            0
        )
        poseStack.popPose()
    }

    companion object {
        private var renderer: RacingRouletteItemRenderer? = null

        fun instance(): RacingRouletteItemRenderer {
            val minecraft = Minecraft.getInstance()
            return renderer ?: RacingRouletteItemRenderer(
                minecraft.blockEntityRenderDispatcher,
                minecraft.entityModels
            ).also { renderer = it }
        }
    }
}
