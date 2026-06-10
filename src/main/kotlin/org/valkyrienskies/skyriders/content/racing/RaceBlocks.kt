package org.valkyrienskies.skyriders.content.racing

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.item.RaceFlagItem

class RaceMarkerBlock(properties: Properties) : BaseEntityBlock(properties) {
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = RaceMarkerBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>
    ): BlockEntityTicker<T>? {
        if (level.isClientSide || blockEntityType != SkyridersMod.RACE_MARKER_BLOCK_ENTITY.get()) return null
        @Suppress("UNCHECKED_CAST")
        return object : BlockEntityTicker<T> {
            override fun tick(tickLevel: Level, tickPos: BlockPos, tickState: BlockState, blockEntity: T) {
                RaceMarkerBlockEntity.serverTick(tickLevel, tickPos, tickState, blockEntity as RaceMarkerBlockEntity)
            }
        }
    }

    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult
    ): InteractionResult {
        val marker = level.getBlockEntity(pos) as? RaceMarkerBlockEntity ?: return InteractionResult.PASS
        val stack = player.getItemInHand(hand)
        if (stack.item is RaceFlagItem) {
            if (!level.isClientSide) {
                marker.setColor(RaceFlagItem.getColor(stack))
                player.displayClientMessage(Component.literal("Race marker color set to ${RaceFlagItem.getColor(stack).name}"), true)
                if (!player.abilities.instabuild) stack.shrink(1)
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        if (stack.item == SkyridersMod.RACE_ENDPOINT_ITEM.get()) {
            if (!level.isClientSide) {
                RaceEndpointBlockItem.storeMarker(stack, pos)
                player.displayClientMessage(Component.literal("Endpoint item linked to this marker."), true)
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        if (stack.isEmpty) {
            if (!level.isClientSide) {
                if (player.isShiftKeyDown) {
                    val type = marker.cycleType()
                    player.displayClientMessage(Component.literal("Race marker type: $type"), true)
                } else {
                    val index = marker.incrementCheckpointIndex()
                    player.displayClientMessage(Component.literal("Checkpoint index: $index"), true)
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        return InteractionResult.PASS
    }

    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: net.minecraft.world.level.block.Block,
        fromPos: BlockPos,
        isMoving: Boolean
    ) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving)
        val serverLevel = level as? ServerLevel ?: return
        val marker = level.getBlockEntity(pos) as? RaceMarkerBlockEntity ?: return
        marker.setPoweredState(level.hasNeighborSignal(pos), serverLevel)
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (state.block != newState.block) {
            (level.getBlockEntity(pos) as? RaceMarkerBlockEntity)?.endpointPos?.let { endpointPos ->
                (level.getBlockEntity(endpointPos) as? RaceEndpointBlockEntity)?.setMarker(null)
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    companion object {
        private val SHAPE: VoxelShape = box(2.0, 0.0, 2.0, 14.0, 14.0, 14.0)
    }
}

class RaceEndpointBlock(properties: Properties) : BaseEntityBlock(properties) {
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = RaceEndpointBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun setPlacedBy(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        placer: net.minecraft.world.entity.LivingEntity?,
        stack: net.minecraft.world.item.ItemStack
    ) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return
        val markerPos = RaceEndpointBlockItem.readMarker(stack) ?: return
        val endpoint = level.getBlockEntity(pos) as? RaceEndpointBlockEntity ?: return
        val marker = level.getBlockEntity(markerPos) as? RaceMarkerBlockEntity
        endpoint.setMarker(markerPos)
        marker?.setEndpoint(pos)
        (placer as? Player)?.displayClientMessage(Component.literal("Race endpoint connected."), true)
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (state.block != newState.block) {
            val endpoint = level.getBlockEntity(pos) as? RaceEndpointBlockEntity
            endpoint?.markerPos?.let { markerPos ->
                val marker = level.getBlockEntity(markerPos) as? RaceMarkerBlockEntity
                if (marker?.endpointPos == pos) marker.setEndpoint(null)
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    companion object {
        private val SHAPE: VoxelShape = box(3.0, 0.0, 3.0, 13.0, 12.0, 13.0)
    }
}

class RaceEndpointBlockItem(block: net.minecraft.world.level.block.Block, properties: Properties) : net.minecraft.world.item.BlockItem(block, properties) {
    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        if (level.getBlockState(pos).block == SkyridersMod.RACE_MARKER_BLOCK.get()) {
            if (!level.isClientSide) {
                storeMarker(context.itemInHand, pos)
                context.player?.displayClientMessage(Component.literal("Endpoint item linked to marker."), true)
            }
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        return super.useOn(context)
    }

    override fun place(context: BlockPlaceContext): InteractionResult {
        val result = super.place(context)
        if (result.consumesAction()) {
            context.itemInHand.tag?.remove(MARKER_KEY)
        }
        return result
    }

    companion object {
        private const val MARKER_KEY = "RaceMarkerPos"

        fun storeMarker(stack: net.minecraft.world.item.ItemStack, markerPos: BlockPos) {
            stack.orCreateTag.putLong(MARKER_KEY, markerPos.asLong())
        }

        fun readMarker(stack: net.minecraft.world.item.ItemStack): BlockPos? {
            val tag = stack.tag ?: return null
            if (!tag.contains(MARKER_KEY)) return null
            return BlockPos.of(tag.getLong(MARKER_KEY))
        }
    }
}
