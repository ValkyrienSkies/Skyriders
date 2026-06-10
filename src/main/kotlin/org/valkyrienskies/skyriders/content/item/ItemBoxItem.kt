package org.valkyrienskies.skyriders.content.item

import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import org.valkyrienskies.skyriders.SkyridersMod

class ItemBoxItem(properties: Properties) : Item(properties) {
    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val player = context.player
        val clicked = context.clickedPos
        val spawnPos = spawnPosition(clicked, context.clickedFace)
        if (!level.isClientSide) {
            val entity = SkyridersMod.ITEM_BOX_ENTITY.get().create(level) ?: return InteractionResult.FAIL
            val yaw = player?.yRot ?: 0.0f
            entity.placeAt(spawnPos.x + 0.5, spawnPos.y.toDouble(), spawnPos.z + 0.5, yaw)
            level.addFreshEntity(entity)
            if (player?.abilities?.instabuild != true) {
                context.itemInHand.shrink(1)
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    private fun spawnPosition(clicked: BlockPos, face: net.minecraft.core.Direction): BlockPos {
        return clicked.relative(face)
    }
}
