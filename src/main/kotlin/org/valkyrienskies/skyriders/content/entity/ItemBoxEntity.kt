package org.valkyrienskies.skyriders.content.entity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.loot.LootDataId
import net.minecraft.world.level.storage.loot.LootDataType
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import org.joml.Vector3d
import org.joml.Vector3f
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.SkyridersSounds
import org.valkyrienskies.skyriders.content.VehicleFuel
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleRefuelSources
import org.valkyrienskies.skyriders.content.item.RacingRouletteItem
import org.valkyrienskies.skyriders.content.racing.RaceManager
import net.minecraftforge.registries.ForgeRegistries
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.math.sqrt

class ItemBoxEntity(type: EntityType<ItemBoxEntity>, level: Level) : Entity(type, level) {
    var rechargeTicks: Int
        get() = entityData.get(RECHARGE_TICKS)
        set(value) = entityData.set(RECHARGE_TICKS, value.coerceAtLeast(0))

    val recharging: Boolean
        get() = rechargeTicks > 0

    val frozenRotationTick: Int
        get() = entityData.get(FROZEN_ROTATION_TICK)

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
            if (rechargeTicks == 0) {
                playRechargeSound(serverLevel())
                spawnRechargeBurst(serverLevel())
            }
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
            if (grantPickup(serverLevel, driver, vehicle)) {
                freezeRotation()
                rechargeTicks = RECHARGE_TICKS_DEFAULT
                playPickupSound(serverLevel)
                spawnPickupBurst(serverLevel)
                return
            }
        }
    }

    override fun defineSynchedData() {
        entityData.define(RECHARGE_TICKS, 0)
        entityData.define(FROZEN_ROTATION_TICK, 0)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        rechargeTicks = compound.getInt(RECHARGE_TICKS_KEY)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putInt(RECHARGE_TICKS_KEY, rechargeTicks)
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> = ClientboundAddEntityPacket(this)

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean {
        return distance < RENDER_DISTANCE * RENDER_DISTANCE
    }

    override fun skipAttackInteraction(attacker: Entity): Boolean {
        val player = attacker as? Player ?: return false
        if (player.vehicle != null) return false
        if (!level().isClientSide) {
            level().addFreshEntity(
                ItemEntity(
                    level(),
                    x,
                    y + 0.45,
                    z,
                    ItemStack(SkyridersMod.ITEM_BOX.get())
                )
            )
            discard()
        }
        return true
    }

    fun placeAt(x: Double, y: Double, z: Double, yaw: Float) {
        moveTo(x, y, z, yaw, 0.0f)
        rechargeTicks = 0
        entityData.set(FROZEN_ROTATION_TICK, tickCount)
    }

    private fun freezeRotation() {
        entityData.set(FROZEN_ROTATION_TICK, tickCount)
    }

    private fun grantPickup(level: ServerLevel, driver: ServerPlayer, vehicle: IVehicle): Boolean {
        val lowFuel = VehicleFuel.fraction(vehicle) <= LOW_FUEL_THRESHOLD
        val placeWeighted = placeWeightedItems(level, vehicle.bodyId, lowFuel)
        val stacks = placeWeighted ?: vanillaLootItems(level, lowFuel)
        if (stacks.isEmpty()) return false
        stacks.forEach { stack -> giveOrDrop(driver, RacingRouletteItem.create(stack.copy())) }
        return true
    }

    private fun vanillaLootItems(level: ServerLevel, lowFuel: Boolean): List<ItemStack> {
        val table: LootTable? = level.server.getLootData()
            .getElement(LootDataId(LootDataType.TABLE, DEFAULT_LOOT_TABLE))
        val params = LootParams.Builder(level).create(LootContextParamSets.EMPTY)
        val generated = table?.getRandomItems(params).orEmpty()
        return if (generated.isEmpty()) fallbackItems(level, lowFuel) else generated
    }

    private fun placeWeightedItems(level: ServerLevel, bodyId: Long, lowFuel: Boolean): List<ItemStack>? {
        val entries = loadPlaceWeightedEntries(level)
        if (entries.isEmpty()) return null
        val placeFactor = placeFactor(level, bodyId)
        val totalWeight = entries.sumOf { it.effectiveWeight(placeFactor, lowFuel) }
        if (totalWeight <= 0.0) return null

        var pick = level.random.nextDouble() * totalWeight
        entries.forEach { entry ->
            pick -= entry.effectiveWeight(placeFactor, lowFuel)
            if (pick <= 0.0) {
                return listOf(entry.stack())
            }
        }
        return listOf(entries.last().stack())
    }

    private fun placeFactor(level: ServerLevel, bodyId: Long): Double {
        val placement = RaceManager.placementFor(level, bodyId) ?: return 0.5
        if (placement.total <= 1) return 0.5
        return ((placement.place - 1).toDouble() / (placement.total - 1).toDouble()).coerceIn(0.0, 1.0)
    }

    private fun loadPlaceWeightedEntries(level: ServerLevel): List<PlaceWeightedReward> {
        val resource = level.server.resourceManager.getResource(PLACE_WEIGHT_TABLE)
        if (resource.isEmpty) return emptyList()
        return try {
            resource.get().open().use { stream ->
                val json = JsonParser.parseReader(InputStreamReader(stream, StandardCharsets.UTF_8)).asJsonObject
                val entries = json.getAsJsonArray("entries") ?: return emptyList()
                entries.mapNotNull { element ->
                    val entry = element.asJsonObject ?: return@mapNotNull null
                    val itemId = ResourceLocation.tryParse(entry.get("item")?.asString ?: return@mapNotNull null)
                        ?: return@mapNotNull null
                    val item = ForgeRegistries.ITEMS.getValue(itemId) ?: return@mapNotNull null
                    val weight = entry.doubleOr("weight", 1.0)
                    if (weight <= 0.0) return@mapNotNull null
                    PlaceWeightedReward(
                        item = item,
                        weight = weight,
                        frontMultiplier = entry.doubleOr("frontMultiplier", 1.0),
                        backMultiplier = entry.doubleOr("backMultiplier", 1.0),
                        count = entry.intOr("count", 1).coerceAtLeast(1)
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fallbackItems(level: ServerLevel, lowFuel: Boolean): List<ItemStack> {
        val options = listOf(
            SkyridersMod.HONEY_CANISTER.get(),
            SkyridersMod.ROYAL_JELLY_CANISTER.get(),
            SkyridersMod.HONEY_TANK.get(),
            SkyridersMod.CAVENDISH.get(),
            SkyridersMod.FAKE_ITEM_BOX.get(),
            SkyridersMod.SUGAR_ROCKET.get(),
            SkyridersMod.HOMING_SUGAR_ROCKET.get(),
            SkyridersMod.GLASSO.get(),
            SkyridersMod.HONEY_HEISTER.get(),
            SkyridersMod.BOXING_GLOVE.get(),
            SkyridersMod.GRABBY_HAND.get(),
            SkyridersMod.THUNDERBOLT.get()
        )
        val weightedOptions = options.flatMap { item ->
            val copies = if (lowFuel && ItemStack(item).`is`(VehicleRefuelSources.LOW_FUEL_RESCUE_TAG)) {
                LOW_FUEL_RESCUE_MULTIPLIER.toInt().coerceAtLeast(1)
            } else {
                1
            }
            List(copies) { item }
        }
        return listOf(ItemStack(weightedOptions[level.random.nextInt(weightedOptions.size)]))
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

    private fun playRechargeSound(level: ServerLevel) {
        level.playSound(
            null,
            x,
            y + PICKUP_Y_OFFSET,
            z,
            SoundEvents.NOTE_BLOCK_BELL.value(),
            SoundSource.PLAYERS,
            0.8f,
            1.65f
        )
    }

    private fun spawnRechargeBurst(level: ServerLevel) {
        level.sendParticles(
            DustParticleOptions(Vector3f(0.15f, 1.0f, 0.12f), 1.2f),
            x,
            y + PICKUP_Y_OFFSET,
            z,
            34,
            0.45,
            0.35,
            0.45,
            0.08
        )
        level.sendParticles(
            ParticleTypes.HAPPY_VILLAGER,
            x,
            y + PICKUP_Y_OFFSET,
            z,
            10,
            0.35,
            0.28,
            0.35,
            0.03
        )
    }

    private fun serverLevel(): ServerLevel = level() as ServerLevel

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
        val PLACE_WEIGHT_TABLE = ResourceLocation(SkyridersMod.MOD_ID, "racing_item_box_weights/default.json")
        private const val LOW_FUEL_THRESHOLD = 0.25
        private const val LOW_FUEL_RESCUE_MULTIPLIER = 6.0
        private const val RECHARGE_TICKS_KEY = "RechargeTicks"
        private const val RECHARGE_TICKS_DEFAULT = 20 * 5
        private const val PICKUP_RADIUS = 0.85
        private const val PICKUP_Y_OFFSET = 0.85
        private const val RENDER_DISTANCE = 192.0
        private val RECHARGE_TICKS: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(ItemBoxEntity::class.java, EntityDataSerializers.INT)
        private val FROZEN_ROTATION_TICK: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(ItemBoxEntity::class.java, EntityDataSerializers.INT)
    }

    private data class PlaceWeightedReward(
        val item: net.minecraft.world.item.Item,
        val weight: Double,
        val frontMultiplier: Double,
        val backMultiplier: Double,
        val count: Int
    ) {
        fun effectiveWeight(placeFactor: Double, lowFuel: Boolean): Double {
            val multiplier = frontMultiplier + (backMultiplier - frontMultiplier) * placeFactor
            val rescueMultiplier = if (lowFuel && stack().`is`(VehicleRefuelSources.LOW_FUEL_RESCUE_TAG)) {
                LOW_FUEL_RESCUE_MULTIPLIER
            } else {
                1.0
            }
            return (weight * multiplier * rescueMultiplier).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        }

        fun stack(): ItemStack = ItemStack(item, count.coerceAtMost(item.defaultInstance.maxStackSize))
    }
}

private fun JsonObject.doubleOr(key: String, defaultValue: Double): Double {
    return try {
        get(key)?.asDouble?.takeIf { it.isFinite() } ?: defaultValue
    } catch (_: Exception) {
        defaultValue
    }
}

private fun JsonObject.intOr(key: String, defaultValue: Int): Int {
    return try {
        get(key)?.asInt ?: defaultValue
    } catch (_: Exception) {
        defaultValue
    }
}
