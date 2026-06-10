package org.valkyrienskies.skyriders.content

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraftforge.registries.ForgeRegistries
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity

object VehicleImpairmentEffects {
    private val TIPSY_EFFECT = ResourceLocation("brewinandchewin", "tipsy")
    private const val STEER_VEER_CHANCE_PER_TICK = 0.018f
    private const val STEER_VEER_MIN_TICKS = 8
    private const val STEER_VEER_EXTRA_TICKS = 14
    private const val STEER_VEER_STRENGTH = 1.0
    private const val IMPACT_DAMAGE_MULTIPLIER = 1.45

    private val intendedInputs = HashMap<VehicleKey, VehicleInput>()
    private val veerStates = HashMap<VehicleKey, VeerState>()

    fun hasTipsy(entity: LivingEntity?): Boolean {
        val effect = ForgeRegistries.MOB_EFFECTS.getValue(TIPSY_EFFECT) ?: return false
        return entity?.hasEffect(effect) == true
    }

    fun impactDamageMultiplier(driver: LivingEntity?): Double {
        return if (hasTipsy(driver)) IMPACT_DAMAGE_MULTIPLIER else 1.0
    }

    fun handleDriverInput(player: Player, seat: BikeSeatEntity, input: VehicleInput) {
        val key = VehicleKey(player.level().dimensionId, seat.bodyId)
        intendedInputs[key] = input.copy(riderPresent = true).clamped()
        applyInput(player.level() as? ServerLevel ?: return, key, player)
    }

    fun tickDriverInput(level: ServerLevel, bodyId: BodyId, driver: LivingEntity) {
        val key = VehicleKey(level.dimensionId, bodyId)
        intendedInputs.putIfAbsent(key, VehicleManager.getInput(level.dimensionId, bodyId).copy(riderPresent = true))
        applyInput(level, key, driver)
    }

    fun clear(level: ServerLevel, bodyId: BodyId) {
        val key = VehicleKey(level.dimensionId, bodyId)
        intendedInputs.remove(key)
        veerStates.remove(key)
    }

    private fun applyInput(level: ServerLevel, key: VehicleKey, driver: LivingEntity) {
        val intended = intendedInputs[key] ?: VehicleInput.EMPTY.copy(riderPresent = true)
        val modified = if (hasTipsy(driver)) applyTipsySteer(level, key, intended) else {
            veerStates.remove(key)
            intended
        }
        VehicleManager.updateInput(key.dimensionId, key.bodyId) { modified.copy(riderPresent = true) }
    }

    private fun applyTipsySteer(level: ServerLevel, key: VehicleKey, input: VehicleInput): VehicleInput {
        val state = veerStates[key]?.takeIf { it.remainingTicks > 0 }
            ?: maybeStartVeer(level, key)
            ?: return input

        state.remainingTicks--
        if (state.remainingTicks <= 0) {
            veerStates.remove(key)
        }

        return input.copy(steer = state.direction * STEER_VEER_STRENGTH).clamped()
    }

    private fun maybeStartVeer(level: ServerLevel, key: VehicleKey): VeerState? {
        if (level.random.nextFloat() >= STEER_VEER_CHANCE_PER_TICK) return null
        val direction = if (level.random.nextBoolean()) 1.0 else -1.0
        val duration = STEER_VEER_MIN_TICKS + level.random.nextInt(STEER_VEER_EXTRA_TICKS + 1)
        return VeerState(direction, duration).also { veerStates[key] = it }
    }

    private data class VehicleKey(val dimensionId: DimensionId, val bodyId: BodyId)
    private data class VeerState(val direction: Double, var remainingTicks: Int)
}
