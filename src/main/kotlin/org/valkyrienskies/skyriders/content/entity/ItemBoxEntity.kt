package org.valkyrienskies.skyriders.content.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.loot.LootDataId
import net.minecraft.world.level.storage.loot.LootDataType
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import org.joml.Vector3d
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.SkyridersSounds
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.content.item.RacingRouletteItem
import kotlin.math.sqrt

class ItemBoxEntity(type: EntityType<ItemBoxEntity>, level: Level) : Entity(type, level) {
    var rechargeTicks: Int
        get() = entityData.get(RECHARGE_TICKS)
        set(value) = entityData.set(RECHARGE_TICKS, value.coerceAtLeast(0))

    val recharging: Boolean
        get() = rechargeTicks > 0

    init {
        blocksBuilding = false
        noPhysics = true
        noCulling = true
    }

    override fun tick() {
        super.tick()
        if (level().isClientSide) return

        if (rechargeTicks > 0) {
            rechargeTicks = rechargeTicks - 1
            return
        }

        val serverLevel = level() as? ServerLevel ?: return
        val shipWorld = serverLevel.shipWorld ?: return
        val center = Vector3d(x, y + PICKUP_Y_OFFSET, z)
        for (vehicle in VehicleManager.getVehicles(serverLevel)) {
            val driver = driverForBody(serverLevel, vehicle.bodyId) ?: continue
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: continue
            val radius = PICKUP_RADIUS + vehicleApproxRadius(vehicle.vehicleDefinition.body.collisionBoxSize)
            if (body.kinematics.position.distanceSquared(center) > radius * radius) continue
            if (grantPickup(serverLevel, driver)) {
                rechargeTicks = RECHARGE_TICKS_DEFAULT
                playPickupSound(serverLevel)
                spawnPickupBurst(serverLevel)
                return
            }
        }
    }

    override fun defineSynchedData() {
        entityData.define(RECHARGE_TICKS, 0)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        rechargeTicks = compound.getInt(RECHARGE_TICKS_KEY)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putInt(RECHARGE_TICKS_KEY, rechargeTicks)
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> = ClientboundAddEntityPacket(this)

    fun placeAt(x: Double, y: Double, z: Double, yaw: Float) {
        moveTo(x, y, z, yaw, 0.0f)
        rechargeTicks = 0
    }

    private fun grantPickup(level: ServerLevel, driver: ServerPlayer): Boolean {
        val table: LootTable? = level.server.getLootData()
            .getElement(LootDataId(LootDataType.TABLE, DEFAULT_LOOT_TABLE))
        val params = LootParams.Builder(level).create(LootContextParamSets.EMPTY)
        val generated = table?.getRandomItems(params).orEmpty()
        val stacks = if (generated.isEmpty()) fallbackItems(level) else generated
        if (stacks.isEmpty()) return false
        stacks.forEach { stack -> giveOrDrop(driver, RacingRouletteItem.create(stack.copy())) }
        return true
    }

    private fun fallbackItems(level: ServerLevel): List<ItemStack> {
        val options = listOf(
            SkyridersMod.HONEY_CANISTER.get(),
            SkyridersMod.CAVENDISH.get(),
            SkyridersMod.SUGAR_ROCKET.get(),
            SkyridersMod.HOMING_SUGAR_ROCKET.get(),
            SkyridersMod.THUNDERBOLT.get()
        )
        return listOf(ItemStack(options[level.random.nextInt(options.size)]))
    }

    private fun giveOrDrop(driver: ServerPlayer, stack: ItemStack) {
        if (stack.isEmpty) return
        if (!driver.inventory.add(stack)) {
            driver.drop(stack, false)
        }
    }

    private fun playPickupSound(level: ServerLevel) {
        level.playSound(
            null,
            x,
            y + PICKUP_Y_OFFSET,
            z,
            SkyridersSounds.RACING_ITEM_GET_SOUND.get(),
            SoundSource.PLAYERS,
            0.85f,
            1.0f
        )
    }

    private fun spawnPickupBurst(level: ServerLevel) {
        level.sendParticles(
            ParticleTypes.END_ROD,
            x,
            y + PICKUP_Y_OFFSET,
            z,
            28,
            0.45,
            0.35,
            0.45,
            0.08
        )
        level.sendParticles(
            ParticleTypes.ELECTRIC_SPARK,
            x,
            y + PICKUP_Y_OFFSET,
            z,
            18,
            0.35,
            0.25,
            0.35,
            0.11
        )
    }

    private fun driverForBody(level: ServerLevel, bodyId: Long): ServerPlayer? {
        return level.players().firstOrNull { player ->
            val seat = player.vehicle as? BikeSeatEntity ?: return@firstOrNull false
            seat.bodyId == bodyId && seat.isDriverSeat()
        }
    }

    private fun vehicleApproxRadius(size: Vector3d): Double {
        return sqrt(size.x * size.x + size.z * size.z) * 0.5
    }

    companion object {
        val DEFAULT_LOOT_TABLE = ResourceLocation(SkyridersMod.MOD_ID, "racing_item_boxes/default")
        private const val RECHARGE_TICKS_KEY = "RechargeTicks"
        private const val RECHARGE_TICKS_DEFAULT = 20 * 5
        private const val PICKUP_RADIUS = 0.85
        private const val PICKUP_Y_OFFSET = 0.85
        private val RECHARGE_TICKS: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(ItemBoxEntity::class.java, EntityDataSerializers.INT)
    }
}
