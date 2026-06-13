package org.valkyrienskies.skyriders.content

import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.entity.BadExplosionEntity
import org.valkyrienskies.skyriders.content.racing.RaceManager
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object VehicleDamage {
    const val BODY_PART_ID = "body"
    const val ENGINE_PART_ID = "engine"
    const val FRONT_WHEEL_PART_ID = "front_wheel"
    const val REAR_WHEEL_PART_ID = "rear_wheel"

    private const val HEALTH_KEY = "health"
    private const val MAX_HEALTH_KEY = "max_health"
    private const val POPPED_KEY = "popped"

    private const val BODY_HEALTH = 150.0
    private const val ENGINE_HEALTH = 90.0
    private const val WHEEL_HEALTH = 36.0
    private const val ACTIVE_RACE_DAMAGE_SCALE = 0.5
    private const val WHEEL_POP_FRACTION = 0.32
    private const val MIN_ENGINE_POWER_SCALE = 0.18
    private const val ENGINE_MALFUNCTION_START = 0.72
    private const val ENGINE_STALL_START = 0.38
    private const val ENGINE_STALL_CHANCE_PER_SECOND = 0.42
    private const val CRITICAL_FUSE_TICKS = 90L
    private const val CRITICAL_HISS_INTERVAL_TICKS = 16L
    private const val CRITICAL_EFFECT_INTERVAL_TICKS = 8L
    private const val CRITICAL_FLASH_START_TICKS = 22L
    private const val CRITICAL_DROPS_MIN = 3
    private const val CRITICAL_DROPS_RANDOM = 3

    private val criticalFailures = HashMap<VehicleKey, CriticalFailureState>()

    fun bodyPartDefinition(bodyHealth: Double = BODY_HEALTH): VehiclePartDefinition = damageablePart(BODY_PART_ID, VehiclePartTypes.BODY, bodyHealth)

    fun enginePartDefinition(engineHealth: Double = ENGINE_HEALTH): VehiclePartDefinition = damageablePart(ENGINE_PART_ID, VehiclePartTypes.ENGINE, engineHealth)

    fun bikeWheelPartDefinitions(wheelHealth: Double = WHEEL_HEALTH): List<VehiclePartDefinition> = listOf(
        damageablePart(FRONT_WHEEL_PART_ID, VehiclePartTypes.WHEEL, wheelHealth),
        damageablePart(REAR_WHEEL_PART_ID, VehiclePartTypes.WHEEL, wheelHealth)
    )

    fun fourWheelPartDefinitions(wheelHealth: Double = WHEEL_HEALTH): List<VehiclePartDefinition> = listOf(
        damageablePart("front_left_wheel", VehiclePartTypes.WHEEL, wheelHealth),
        damageablePart("front_right_wheel", VehiclePartTypes.WHEEL, wheelHealth),
        damageablePart("rear_left_wheel", VehiclePartTypes.WHEEL, wheelHealth),
        damageablePart("rear_right_wheel", VehiclePartTypes.WHEEL, wheelHealth)
    )

    fun bikePartZones(config: BikePhysicsConfig): List<VehicleInteractionZone> = listOf(
        bodyRepairZone(config.collisionBoxOffset, config.collisionBoxSize),
        repairZone(FRONT_WHEEL_PART_ID, config.frontWheelLocalPos, wheelZoneSize(config.wheelRadius, config.wheelWidth)),
        repairZone(REAR_WHEEL_PART_ID, config.rearWheelLocalPos, wheelZoneSize(config.wheelRadius, config.wheelWidth)),
        repairZone(ENGINE_PART_ID, enginePosition(config.collisionBoxSize, config.collisionBoxOffset), Vector3d(0.5, 0.45, 0.55))
    )

    fun kartPartZones(config: KartPhysicsConfig): List<VehicleInteractionZone> {
        val wheelIds = listOf("front_left_wheel", "front_right_wheel", "rear_left_wheel", "rear_right_wheel")
        return listOf(bodyRepairZone(config.collisionBoxOffset, config.collisionBoxSize)) +
            config.wheelLocalPositions.zip(wheelIds).map { (localPos, id) ->
            repairZone(id, localPos, wheelZoneSize(config.wheelRadius, config.wheelSampleWidth))
        } + repairZone(ENGINE_PART_ID, enginePosition(config.collisionBoxSize, config.collisionBoxOffset), Vector3d(0.55, 0.45, 0.65))
    }

    fun wheeledPartZones(config: WheeledVehiclePhysicsConfig): List<VehicleInteractionZone> {
        val wheelZones = config.axles.flatMap { axle ->
            listOf(
                repairZone(
                    "${axle.id}_left_wheel",
                    Vector3d(-axle.halfTrackWidth, axle.localY, axle.localZ),
                    wheelZoneSize(axle.wheelRadius, axle.wheelWidth)
                ),
                repairZone(
                    "${axle.id}_right_wheel",
                    Vector3d(axle.halfTrackWidth, axle.localY, axle.localZ),
                    wheelZoneSize(axle.wheelRadius, axle.wheelWidth)
                )
            )
        }
        return listOf(bodyRepairZone(config.collisionBoxOffset, config.collisionBoxSize)) + wheelZones + repairZone(
            ENGINE_PART_ID,
            enginePosition(config.collisionBoxSize, config.collisionBoxOffset),
            Vector3d(0.72, 0.58, 0.82)
        )
    }

    fun damageCrash(level: ServerLevel, vehicle: IVehicle, severity: Double) {
        if (VehicleStatusEffects.isMoondropActive(vehicle)) return
        val damage = severity.takeIf { it.isFinite() && it > 0.0 } ?: return
        damagePart(level, vehicle, BODY_PART_ID, damage * 0.85)
        damagePart(level, vehicle, ENGINE_PART_ID, damage * 0.32)
        wheelPartIds(vehicle).randomOrNull()?.let { wheelId ->
            damagePart(level, vehicle, wheelId, damage * 0.48)
        }
        playVehicleSound(level, vehicle, SkyridersSounds.CRASH_SOUND.get(), 0.72f, randomPitch(0.9f, 1.08f))
        finishDamage(level, vehicle)
    }

    fun damageExplosion(
        level: ServerLevel,
        origin: Vec3,
        baseDamage: Double,
        radius: Double,
        directTarget: IVehicle? = null,
        ignoredBodyId: Long? = null
    ) {
        val shipWorld = level.shipWorld ?: return
        val originVec = Vector3d(origin.x, origin.y, origin.z)
        VehicleManager.getVehicles(level).forEach { vehicle ->
            if (ignoredBodyId != null && vehicle.bodyId == ignoredBodyId) return@forEach
            if (VehicleStatusEffects.isMoondropActive(vehicle)) return@forEach
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: return@forEach
            val vehicleRadius = vehicleRadius(vehicle)
            val effectiveRadius = radius + vehicleRadius
            val distance = sqrt(body.kinematics.position.distanceSquared(originVec))
            if (distance > effectiveRadius) return@forEach

            val directScale = if (vehicle.bodyId == directTarget?.bodyId) 1.45 else 1.0
            val falloff = (1.0 - distance / effectiveRadius).coerceIn(0.18, 1.0)
            val damage = baseDamage * falloff * directScale
            damagePart(level, vehicle, BODY_PART_ID, damage * 0.75)
            damagePart(level, vehicle, ENGINE_PART_ID, damage * 0.55)
            worldToLocal(vehicle, origin)?.let { nearestWheelPart(vehicle, it) }?.let { wheelId ->
                damagePart(level, vehicle, wheelId, damage * 0.85)
            }
            playVehicleSound(level, vehicle, SkyridersSounds.CRASH_SOUND.get(), 0.65f, randomPitch(0.84f, 1.02f))
            finishDamage(level, vehicle)
        }
    }

    fun damageAt(level: ServerLevel, vehicle: IVehicle, worldPos: Vec3, amount: Double) {
        if (VehicleStatusEffects.isMoondropActive(vehicle)) return
        val safeAmount = amount.takeIf { it.isFinite() && it > 0.0 } ?: return
        val partId = targetedPart(vehicle, worldPos) ?: BODY_PART_ID
        val scaled = when {
            isWheelPart(vehicle, partId) -> safeAmount * 1.35
            partId == ENGINE_PART_ID -> safeAmount * 1.15
            else -> safeAmount
        }
        damagePart(level, vehicle, partId, scaled)
        if (partId != BODY_PART_ID) {
            damagePart(level, vehicle, BODY_PART_ID, scaled * 0.18)
        }
        playVehicleSound(level, vehicle, SkyridersSounds.CRASH_SOUND.get(), 0.38f, randomPitch(1.04f, 1.22f))
        finishDamage(level, vehicle)
    }

    fun handleRepair(player: ServerPlayer, vehicle: IVehicle, zone: VehicleInteractionZone): Boolean {
        val partId = zone.partId ?: return false
        val part = vehicle.vehicleDefinition.parts.firstOrNull { it.id == partId } ?: return false
        if (part.type != VehiclePartTypes.WHEEL && part.type != VehiclePartTypes.ENGINE && part.type != VehiclePartTypes.BODY) {
            return false
        }

        val hand = InteractionHand.entries.firstOrNull { hand ->
            repairAmountFor(player.getItemInHand(hand).item, vehicle, part.type) > 0.0
        } ?: return false
        val stack = player.getItemInHand(hand)
        val amount = repairAmountFor(stack.item, vehicle, part.type)
        if (amount <= 0.0) return false

        val level = player.level() as? ServerLevel ?: return false
        var repaired = 0.0
        val changed = VehicleManager.mutatePartState(level, vehicle.bodyId, partId) { state ->
            val maxHealth = maxHealth(state, part)
            val before = health(state, part)
            val replace = part.type == VehiclePartTypes.WHEEL ||
                stack.item == SkyridersMod.ENGINE.get() ||
                stack.item == SkyridersMod.BIKE_ENGINE.get()
            val next = if (replace) maxHealth else (before + amount).coerceAtMost(maxHealth)
            state.putDouble(HEALTH_KEY, next)
            state.putDouble(MAX_HEALTH_KEY, maxHealth)
            if (part.type == VehiclePartTypes.WHEEL) {
                state.putBoolean(POPPED_KEY, false)
            }
            repaired = next - before
        }
        if (!changed || repaired <= 1.0e-4) {
            player.displayClientMessage(Component.literal("${partDisplayName(partId)} is already repaired"), true)
            return true
        }

        if (!player.abilities.instabuild) {
            stack.shrink(1)
        }
        clearEngineDestroyedState(vehicle)
        criticalFailures.remove(VehicleKey(level.dimensionId, vehicle.bodyId))
        player.displayClientMessage(Component.literal("Repaired ${partDisplayName(partId)}"), true)
        level.playSound(null, player.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.45f, 1.35f)
        return true
    }

    fun canEngineStart(vehicle: IVehicle): Boolean {
        return healthFraction(vehicle, ENGINE_PART_ID) > 0.02
    }

    fun modifyInput(vehicle: IVehicle, input: VehicleInput): VehicleInput {
        val engineHealth = healthFraction(vehicle, ENGINE_PART_ID)
        if (engineHealth >= ENGINE_MALFUNCTION_START) return input

        val powerScale = enginePowerScale(vehicle)
        val throttle = input.throttle * powerScale
        val brake = if (engineHealth <= 0.0) input.brake.coerceAtLeast(1.0) else input.brake
        return input.copy(throttle = throttle, brake = brake).clamped()
    }

    fun physTick(vehicle: IVehicle, dt: Double) {
        val engineHealth = healthFraction(vehicle, ENGINE_PART_ID)
        if (engineHealth <= 0.0) {
            stopEngine(vehicle, stalled = true)
            return
        }
        if (!isEngineOn(vehicle) || engineHealth >= ENGINE_STALL_START) return

        val safeDt = dt.coerceIn(0.0, 0.25)
        val chance = (ENGINE_STALL_START - engineHealth) / ENGINE_STALL_START *
            ENGINE_STALL_CHANCE_PER_SECOND * safeDt
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            stopEngine(vehicle, stalled = true)
        }
    }

    fun tick(level: ServerLevel, vehicles: Iterable<IVehicle>) {
        val now = level.gameTime
        val activeKeys = HashSet<VehicleKey>()
        vehicles.forEach { vehicle ->
            val key = VehicleKey(level.dimensionId, vehicle.bodyId)
            activeKeys.add(key)
            if (VehicleStatusEffects.isMoondropActive(vehicle)) {
                criticalFailures.remove(key)
                return@forEach
            }
            if (!isCriticalFailure(vehicle)) {
                criticalFailures.remove(key)
                return@forEach
            }

            val state = criticalFailures.getOrPut(key) { CriticalFailureState(now) }
            val elapsed = now - state.startTick
            if (elapsed >= CRITICAL_FUSE_TICKS) {
                detonateCriticalFailure(level, vehicle)
                criticalFailures.remove(key)
                return@forEach
            }
            playCriticalFailureEffects(level, vehicle, state, now, elapsed)
        }
        criticalFailures.keys.removeIf { key -> key.dimensionId == level.dimensionId && key !in activeKeys }
    }

    fun tractionScale(dimensionId: org.valkyrienskies.core.api.world.properties.DimensionId, bodyId: Long): Double {
        val vehicle = VehicleManager.getVehicle(dimensionId, bodyId) ?: return 1.0
        val wheels = wheelPartIds(vehicle)
        if (wheels.isEmpty()) return 1.0

        val averageHealth = wheels.sumOf { healthFraction(vehicle, it) } / wheels.size
        val popped = wheels.count { isWheelPopped(vehicle, it) || healthFraction(vehicle, it) <= 0.0 }
        val poppedPenalty = 1.0 - popped.toDouble() / wheels.size * 0.7
        return (0.22 + 0.78 * averageHealth).coerceIn(0.22, 1.0) * poppedPenalty.coerceIn(0.25, 1.0)
    }

    fun healthFraction(vehicle: IVehicle, partId: String): Double {
        val part = vehicle.vehicleDefinition.parts.firstOrNull { it.id == partId } ?: return 1.0
        val state = vehicle.vehicleState.partStates[partId]?.data ?: part.defaultState
        val maxHealth = maxHealth(state, part)
        if (maxHealth <= 0.0) return 1.0
        return (health(state, part) / maxHealth).coerceIn(0.0, 1.0)
    }

    fun damageFraction(vehicle: IVehicle, partId: String): Double {
        return 1.0 - healthFraction(vehicle, partId)
    }

    fun repairAllParts(level: ServerLevel, vehicle: IVehicle, fraction: Double): Double {
        val safeFraction = fraction.takeIf { it.isFinite() && it > 0.0 } ?: return 0.0
        var totalRepaired = 0.0
        vehicle.vehicleDefinition.parts
            .filter(::isDamageablePart)
            .forEach { part ->
                val state = vehicle.vehicleState.partStates[part.id]?.data?.copy() ?: part.defaultState.copy()
                val maxHealth = maxHealth(state, part)
                val before = health(state, part)
                val next = (before + maxHealth * safeFraction).coerceAtMost(maxHealth)
                if (next <= before + 1.0e-4) return@forEach
                state.putDouble(HEALTH_KEY, next)
                state.putDouble(MAX_HEALTH_KEY, maxHealth)
                if (part.type == VehiclePartTypes.WHEEL && next / maxHealth > WHEEL_POP_FRACTION) {
                    state.putBoolean(POPPED_KEY, false)
                }
                vehicle.vehicleState.partStates[part.id] = VehiclePartState(part.id, state)
                totalRepaired += next - before
            }

        if (totalRepaired > 1.0e-4) {
            clearEngineDestroyedState(vehicle)
            criticalFailures.remove(VehicleKey(level.dimensionId, vehicle.bodyId))
            BikeLifecycle.saveLevel(level)
            BikeLifecycle.syncLevel(level)
        }
        return totalRepaired
    }

    fun repairAllPartsFully(level: ServerLevel, vehicle: IVehicle): Double {
        return repairAllParts(level, vehicle, 1.0)
    }

    fun wheelPartIds(vehicle: IVehicle): List<String> {
        return vehicle.vehicleDefinition.parts
            .filter { it.type == VehiclePartTypes.WHEEL }
            .map { it.id }
    }

    fun isWheelPopped(vehicle: IVehicle, partId: String): Boolean {
        val part = vehicle.vehicleDefinition.parts.firstOrNull { it.id == partId } ?: return false
        if (part.type != VehiclePartTypes.WHEEL) return false
        val state = vehicle.vehicleState.partStates[partId]?.data ?: part.defaultState
        return state.getBoolean(POPPED_KEY) || healthFraction(vehicle, partId) <= WHEEL_POP_FRACTION
    }

    fun isPartDestroyed(vehicle: IVehicle, partId: String): Boolean {
        return healthFraction(vehicle, partId) <= 0.0
    }

    fun partWorldPosition(vehicle: IVehicle, partId: String): Vector3d? {
        val zone = vehicle.vehicleDefinition.interactions.zones.firstOrNull { it.partId == partId }
            ?: return null
        val transform = try {
            vehicle.getRenderTransform()
        } catch (_: IllegalStateException) {
            null
        } ?: return null
        return transform.toWorld.transformPosition(Vector3d(zone.center)).takeIf { it.isFinite() }
    }

    private fun damagePart(level: ServerLevel, vehicle: IVehicle, partId: String, amount: Double) {
        val part = vehicle.vehicleDefinition.parts.firstOrNull { it.id == partId } ?: return
        val safeAmount = amount.takeIf { it.isFinite() && it > 0.0 } ?: return
        val effectiveAmount = safeAmount * damageScale(level, vehicle)
        val stateMap = vehicle.vehicleState.partStates
        val state = stateMap[partId]?.data?.copy() ?: part.defaultState.copy()
        val maxHealth = maxHealth(state, part)
        val before = health(state, part)
        val wasPopped = part.type == VehiclePartTypes.WHEEL && before / maxHealth <= WHEEL_POP_FRACTION
        val wasEngineBroken = part.id == ENGINE_PART_ID && before <= 0.0
        val next = (before - effectiveAmount).coerceIn(0.0, maxHealth)
        state.putDouble(HEALTH_KEY, next)
        state.putDouble(MAX_HEALTH_KEY, maxHealth)
        if (part.type == VehiclePartTypes.WHEEL && next / maxHealth <= WHEEL_POP_FRACTION) {
            state.putBoolean(POPPED_KEY, true)
        }
        stateMap[partId] = VehiclePartState(partId, state)
        if (part.type == VehiclePartTypes.WHEEL && !wasPopped && next / maxHealth <= WHEEL_POP_FRACTION) {
            playVehicleSound(level, vehicle, SkyridersSounds.TIRE_POP_SOUND.get(), 0.9f, randomPitch(0.92f, 1.08f), partId)
        }
        if (part.id == ENGINE_PART_ID && !wasEngineBroken && next <= 0.0) {
            playVehicleSound(level, vehicle, SkyridersSounds.ENGINE_BREAK_SOUND.get(), 0.95f, randomPitch(0.94f, 1.06f), partId)
        }
    }

    private fun finishDamage(level: ServerLevel, vehicle: IVehicle) {
        if (!canEngineStart(vehicle)) {
            stopEngine(vehicle, stalled = true)
        }
        BikeLifecycle.saveLevel(level)
        BikeLifecycle.syncLevel(level)
    }

    fun isCriticalFailure(vehicle: IVehicle): Boolean {
        return healthFraction(vehicle, BODY_PART_ID) <= 0.0 && healthFraction(vehicle, ENGINE_PART_ID) <= 0.0
    }

    private fun playCriticalFailureEffects(
        level: ServerLevel,
        vehicle: IVehicle,
        state: CriticalFailureState,
        now: Long,
        elapsed: Long
    ) {
        val position = vehiclePosition(level, vehicle) ?: return
        if (now - state.lastHissTick >= CRITICAL_HISS_INTERVAL_TICKS) {
            level.playSound(null, position.x, position.y, position.z, SoundEvents.TNT_PRIMED, SoundSource.NEUTRAL, 0.78f, randomPitch(0.86f, 1.08f))
            state.lastHissTick = now
        }
        if (now - state.lastEffectTick >= CRITICAL_EFFECT_INTERVAL_TICKS) {
            val size = vehicle.vehicleDefinition.body.collisionBoxSize
            level.sendParticles(
                ParticleTypes.CAMPFIRE_COSY_SMOKE,
                position.x,
                position.y + size.y.coerceAtLeast(0.4) * 0.25,
                position.z,
                2,
                size.x.coerceAtLeast(0.6) * 0.22,
                size.y.coerceAtLeast(0.4) * 0.16,
                size.z.coerceAtLeast(0.6) * 0.22,
                0.015
            )
            level.sendParticles(
                ParticleTypes.ELECTRIC_SPARK,
                position.x,
                position.y,
                position.z,
                3,
                size.x.coerceAtLeast(0.6) * 0.22,
                size.y.coerceAtLeast(0.4) * 0.18,
                size.z.coerceAtLeast(0.6) * 0.22,
                0.04
            )
            state.lastEffectTick = now
        }
        if (CRITICAL_FUSE_TICKS - elapsed <= CRITICAL_FLASH_START_TICKS && now - state.lastFlashTick >= 4L) {
            level.sendParticles(ParticleTypes.FLASH, position.x, position.y + 0.35, position.z, 1, 0.0, 0.0, 0.0, 0.0)
            level.playSound(null, position.x, position.y, position.z, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.NEUTRAL, 0.42f, 1.85f)
            state.lastFlashTick = now
        }
    }

    private fun detonateCriticalFailure(level: ServerLevel, vehicle: IVehicle) {
        val position = vehiclePosition(level, vehicle) ?: return
        level.playSound(null, position.x, position.y, position.z, SkyridersSounds.SUGAR_ROCKET_EXPLODE_SOUND.get(), SoundSource.NEUTRAL, 1.4f, randomPitch(0.88f, 1.04f))
        level.sendParticles(ParticleTypes.FLASH, position.x, position.y + 0.4, position.z, 1, 0.0, 0.0, 0.0, 0.0)
        level.sendParticles(ParticleTypes.EXPLOSION, position.x, position.y + 0.25, position.z, 2, 0.35, 0.25, 0.35, 0.02)
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, position.x, position.y, position.z, 36, 0.9, 0.5, 0.9, 0.12)
        val visual = BadExplosionEntity(SkyridersMod.BAD_EXPLOSION_ENTITY.get(), level)
        visual.setPos(position.x, position.y, position.z)
        level.addFreshEntity(visual)
        damageExplosion(level, Vec3(position.x, position.y, position.z), baseDamage = 30.0, radius = 4.6, ignoredBodyId = vehicle.bodyId)
        VehicleManager.removeVehicle(level, vehicle.bodyId)
        dropCriticalFailureComponents(level, vehicle, position)
    }

    private fun dropCriticalFailureComponents(level: ServerLevel, vehicle: IVehicle, position: Vector3d) {
        vehicleRecipeComponentDrops(level, vehicle).forEach { stack ->
            val item = ItemEntity(
                level,
                position.x + ThreadLocalRandom.current().nextDouble(-0.35, 0.35),
                position.y + 0.25,
                position.z + ThreadLocalRandom.current().nextDouble(-0.35, 0.35),
                stack.copy()
            )
            item.setDeltaMovement(
                ThreadLocalRandom.current().nextDouble(-0.08, 0.08),
                ThreadLocalRandom.current().nextDouble(0.08, 0.18),
                ThreadLocalRandom.current().nextDouble(-0.08, 0.08)
            )
            level.addFreshEntity(item)
        }
    }

    private fun vehicleRecipeComponentDrops(level: ServerLevel, vehicle: IVehicle): List<ItemStack> {
        val recipe = level.recipeManager.byKey(vehicle.vehicleDefinition.id).orElse(null)
        val ingredients = recipe?.ingredients
            ?.flatMap { ingredient -> ingredient.items.toList() }
            ?.filter { stack -> !stack.isEmpty && stack.item != Items.AIR }
            ?: emptyList()
        if (ingredients.isEmpty()) {
            return List(CRITICAL_DROPS_MIN) { ItemStack(Items.IRON_INGOT) }
        }

        val random = ThreadLocalRandom.current()
        val dropCount = (CRITICAL_DROPS_MIN + random.nextInt(CRITICAL_DROPS_RANDOM)).coerceAtMost(ingredients.size)
        return ingredients.shuffled(random).take(dropCount).map { stack -> stack.copyWithCount(1) }
    }

    private fun vehiclePosition(level: ServerLevel, vehicle: IVehicle): Vector3d? {
        return try {
            vehicle.getRenderTransform()?.toWorld?.transformPosition(Vector3d())
        } catch (_: IllegalStateException) {
            null
        } ?: level.shipWorld?.allBodies?.getById(vehicle.bodyId)?.kinematics?.position?.let { Vector3d(it) }
    }

    private fun damageablePart(id: String, type: net.minecraft.resources.ResourceLocation, maxHealth: Double): VehiclePartDefinition {
        return VehiclePartDefinition(
            id = id,
            type = type,
            defaultState = defaultState(maxHealth),
            interactionActions = setOf(VehicleInteractionActions.REPAIR)
        )
    }

    private fun defaultState(maxHealth: Double): CompoundTag = CompoundTag().apply {
        putDouble(HEALTH_KEY, maxHealth)
        putDouble(MAX_HEALTH_KEY, maxHealth)
        putBoolean(POPPED_KEY, false)
    }

    private fun repairZone(id: String, center: Vector3d, size: Vector3d): VehicleInteractionZone {
        return VehicleInteractionZone(
            id = id,
            center = Vector3d(center),
            size = Vector3d(size),
            actions = setOf(VehicleInteractionActions.REPAIR, VehicleInteractionActions.PICK_UP),
            partId = id
        )
    }

    private fun bodyRepairZone(center: Vector3d, size: Vector3d): VehicleInteractionZone {
        return VehicleInteractionZone(
            id = BODY_PART_ID,
            center = Vector3d(center),
            size = Vector3d(size),
            actions = setOf(VehicleInteractionActions.REPAIR),
            partId = BODY_PART_ID
        )
    }

    private fun wheelZoneSize(radius: Double, width: Double): Vector3d {
        val diameter = (radius * 2.25).coerceAtLeast(0.42)
        return Vector3d((width + 0.28).coerceAtLeast(0.42), diameter, diameter)
    }

    private fun enginePosition(collisionBoxSize: Vector3d, collisionBoxOffset: Vector3d): Vector3d {
        return Vector3d(
            collisionBoxOffset.x,
            collisionBoxOffset.y + collisionBoxSize.y * 0.08,
            collisionBoxOffset.z + collisionBoxSize.z * 0.24
        )
    }

    private fun repairAmountFor(item: Item, vehicle: IVehicle, partType: net.minecraft.resources.ResourceLocation): Double {
        return when (partType) {
            VehiclePartTypes.WHEEL -> if (item == wheelRepairItem(vehicle)) WHEEL_HEALTH else 0.0
            VehiclePartTypes.ENGINE -> when (item) {
                SkyridersMod.ENGINE.get(), SkyridersMod.BIKE_ENGINE.get() -> ENGINE_HEALTH
                SkyridersMod.ENGINE_COMPONENT.get() -> ENGINE_HEALTH * 0.36
                else -> 0.0
            }
            VehiclePartTypes.BODY -> when (item) {
                SkyridersMod.SUSPENSION.get(), SkyridersMod.GEAR.get(), SkyridersMod.ENGINE_COMPONENT.get() -> BODY_HEALTH * 0.18
                else -> 0.0
            }
            else -> 0.0
        }
    }

    private fun wheelRepairItem(vehicle: IVehicle): Item {
        return if (vehicle is IBike) SkyridersMod.BIKE_WHEEL.get() else SkyridersMod.TRUCK_WHEEL.get()
    }

    private fun targetedPart(vehicle: IVehicle, worldPos: Vec3): String? {
        val local = worldToLocal(vehicle, worldPos) ?: return null
        return vehicle.vehicleDefinition.interactions.zones
            .filter { it.partId != null && it.actions.contains(VehicleInteractionActions.REPAIR) }
            .filter { localInsideZone(local, it) }
            .minByOrNull { local.distanceSquared(Vector3d(it.center)) }
            ?.partId
            ?: nearestWheelPart(vehicle, local)
    }

    private fun nearestWheelPart(vehicle: IVehicle, local: Vector3d): String? {
        return vehicle.vehicleDefinition.interactions.zones
            .filter { zone -> zone.partId?.let { isWheelPart(vehicle, it) } == true }
            .minByOrNull { zone -> local.distanceSquared(Vector3d(zone.center)) }
            ?.partId
    }

    private fun localInsideZone(local: Vector3d, zone: VehicleInteractionZone): Boolean {
        return local.x >= zone.center.x - zone.size.x * 0.5 &&
            local.x <= zone.center.x + zone.size.x * 0.5 &&
            local.y >= zone.center.y - zone.size.y * 0.5 &&
            local.y <= zone.center.y + zone.size.y * 0.5 &&
            local.z >= zone.center.z - zone.size.z * 0.5 &&
            local.z <= zone.center.z + zone.size.z * 0.5
    }

    private fun worldToLocal(vehicle: IVehicle, worldPos: Vec3): Vector3d? {
        val transform = try {
            vehicle.getRenderTransform()
        } catch (_: IllegalStateException) {
            null
        } ?: return null
        val local = Vector3d(
            worldPos.x - transform.position.x(),
            worldPos.y - transform.position.y(),
            worldPos.z - transform.position.z()
        )
        Quaterniond(transform.rotation).invert().transform(local)
        return local
    }

    private fun isWheelPart(vehicle: IVehicle, partId: String): Boolean {
        return vehicle.vehicleDefinition.parts.firstOrNull { it.id == partId }?.type == VehiclePartTypes.WHEEL
    }

    private fun isDamageablePart(part: VehiclePartDefinition): Boolean {
        return part.type == VehiclePartTypes.BODY ||
            part.type == VehiclePartTypes.ENGINE ||
            part.type == VehiclePartTypes.WHEEL
    }

    private fun damageScale(level: ServerLevel, vehicle: IVehicle): Double {
        return if (RaceManager.isVehicleInActiveRace(level, vehicle.bodyId)) ACTIVE_RACE_DAMAGE_SCALE else 1.0
    }

    private fun maxHealth(state: CompoundTag, part: VehiclePartDefinition): Double {
        val storedMax = state.getDouble(MAX_HEALTH_KEY)
            .takeIf { it.isFinite() && it > 0.0 }
        val definitionMax = part.defaultState.getDouble(MAX_HEALTH_KEY).takeIf { it.isFinite() && it > 0.0 }
        val currentBaseMax = defaultMaxHealth(part.type)
        return max(storedMax ?: 0.0, max(definitionMax ?: 0.0, currentBaseMax))
    }

    private fun health(state: CompoundTag, part: VehiclePartDefinition): Double {
        val maxHealth = maxHealth(state, part)
        val storedHealth = state.getDouble(HEALTH_KEY).takeIf { it.isFinite() } ?: return maxHealth
        val storedMax = state.getDouble(MAX_HEALTH_KEY).takeIf { it.isFinite() && it > 0.0 }
        val scaledHealth = if (storedMax != null && storedMax < maxHealth) {
            storedHealth.coerceIn(0.0, storedMax) / storedMax * maxHealth
        } else {
            storedHealth
        }
        return scaledHealth.coerceIn(0.0, maxHealth)
    }

    private fun defaultMaxHealth(type: net.minecraft.resources.ResourceLocation): Double {
        return when (type) {
            VehiclePartTypes.ENGINE -> ENGINE_HEALTH
            VehiclePartTypes.WHEEL -> WHEEL_HEALTH
            else -> BODY_HEALTH
        }
    }

    private fun enginePowerScale(vehicle: IVehicle): Double {
        val health = healthFraction(vehicle, ENGINE_PART_ID)
        if (health >= ENGINE_MALFUNCTION_START) return 1.0
        return (MIN_ENGINE_POWER_SCALE + (health / ENGINE_MALFUNCTION_START) * (1.0 - MIN_ENGINE_POWER_SCALE))
            .coerceIn(0.0, 1.0)
    }

    private fun stopEngine(vehicle: IVehicle, stalled: Boolean) {
        when (vehicle) {
            is IBike -> vehicle.state.engineOn = false
            is KartVehicle -> vehicle.kartState.engineOn = false
            is WheeledVehicle -> {
                vehicle.wheeledState.engineOn = false
                vehicle.wheeledState.engineStalled = stalled
                vehicle.wheeledState.debugEngineStalled = stalled
                if (stalled) {
                    vehicle.wheeledState.engineRpm = 0.0
                    vehicle.wheeledState.debugEngineRpm = 0.0
                }
            }
        }
    }

    private fun clearEngineDestroyedState(vehicle: IVehicle) {
        if (canEngineStart(vehicle) && vehicle is WheeledVehicle) {
            vehicle.wheeledState.engineStalled = false
            vehicle.wheeledState.debugEngineStalled = false
        }
    }

    private fun isEngineOn(vehicle: IVehicle): Boolean {
        return when (vehicle) {
            is IBike -> vehicle.state.engineOn
            is KartVehicle -> vehicle.kartState.engineOn
            is WheeledVehicle -> vehicle.wheeledState.engineOn
            else -> vehicle.vehicleState.engineOn
        }
    }

    private fun partDisplayName(partId: String): String {
        return partId.replace('_', ' ')
    }

    private fun playVehicleSound(
        level: ServerLevel,
        vehicle: IVehicle,
        sound: net.minecraft.sounds.SoundEvent,
        volume: Float,
        pitch: Float,
        partId: String? = null
    ) {
        val position = partId?.let { partWorldPosition(vehicle, it) }
            ?: try {
                vehicle.getRenderTransform()?.toWorld?.transformPosition(Vector3d())
            } catch (_: IllegalStateException) {
                null
            }
            ?: return
        level.playSound(null, position.x, position.y, position.z, sound, SoundSource.NEUTRAL, volume, pitch)
    }

    private fun randomPitch(min: Float, max: Float): Float {
        return ThreadLocalRandom.current().nextDouble(min.toDouble(), max.toDouble()).toFloat()
    }

    private fun vehicleRadius(vehicle: IVehicle): Double {
        val size = vehicle.vehicleDefinition.body.collisionBoxSize
        return sqrt(size.x * size.x + size.z * size.z) * 0.5
    }

    private data class VehicleKey(val dimensionId: org.valkyrienskies.core.api.world.properties.DimensionId, val bodyId: Long)
    private data class CriticalFailureState(
        val startTick: Long,
        var lastHissTick: Long = startTick - CRITICAL_HISS_INTERVAL_TICKS,
        var lastEffectTick: Long = startTick - CRITICAL_EFFECT_INTERVAL_TICKS,
        var lastFlashTick: Long = startTick - 4L
    )

    private fun Vector3d.isFinite(): Boolean {
        return x.isFinite() && y.isFinite() && z.isFinite()
    }
}
