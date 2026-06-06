package org.valkyrienskies.skyriders.content.block

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.valkyrienskies.skyriders.content.BoostPadHandler

class BoostPadBlock(
    properties: Properties,
    private val fullBlock: Boolean = false
) : Block(properties) {
    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = if (fullBlock) FULL_SHAPE else THIN_SHAPE

    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = if (fullBlock) FULL_SHAPE else THIN_SHAPE

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, movedByPiston: Boolean) {
        super.onPlace(state, level, pos, oldState, movedByPiston)
        BoostPadHandler.cacheBoostPad(level, pos)
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (state.block != newState.block) {
            BoostPadHandler.uncacheBoostPad(level, pos)
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    companion object {
        private val THIN_SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0)
        private val FULL_SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 16.0, 16.0)
    }
}
