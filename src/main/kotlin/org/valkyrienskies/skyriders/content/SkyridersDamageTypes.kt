package org.valkyrienskies.skyriders.content

import net.minecraft.core.registries.Registries
import net.minecraft.core.Holder
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import org.valkyrienskies.skyriders.SkyridersMod

object SkyridersDamageTypes {
    private const val VEHICLE_IMPACT_VARIANT_CHANCE = 0.35f
    private val VEHICLE_IMPACT_VARIANTS = arrayOf(
        "death.attack.skyriders.vehicle_impact.variant_1",
        "death.attack.skyriders.vehicle_impact.variant_2",
        "death.attack.skyriders.vehicle_impact.variant_3"
    )

    val VEHICLE_IMPACT: ResourceKey<DamageType> = ResourceKey.create(
        Registries.DAMAGE_TYPE,
        ResourceLocation(SkyridersMod.MOD_ID, "vehicle_impact")
    )

    fun vehicleImpact(level: ServerLevel, driver: Entity? = null): DamageSource {
        val damageTypes = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
        val holder = damageTypes.getHolderOrThrow(VEHICLE_IMPACT)
        val variantKey = VEHICLE_IMPACT_VARIANTS
            .takeIf { level.random.nextFloat() < VEHICLE_IMPACT_VARIANT_CHANCE }
            ?.let { variants -> variants[level.random.nextInt(variants.size)] }
        return VehicleImpactDamageSource(holder, driver, variantKey)
    }

    private class VehicleImpactDamageSource(
        type: Holder<DamageType>,
        private val driver: Entity?,
        private val variantKey: String?
    ) : DamageSource(type, driver) {
        override fun getLocalizedDeathMessage(victim: LivingEntity): Component {
            val baseKey = variantKey ?: "death.attack.${msgId}"
            val driver = driver ?: return Component.translatable(baseKey, victim.displayName)
            val heldItem = (driver as? LivingEntity)?.mainHandItem ?: ItemStack.EMPTY

            return if (!heldItem.isEmpty && heldItem.hasCustomHoverName()) {
                Component.translatable("$baseKey.item", victim.displayName, driver.displayName, heldItem.displayName)
            } else {
                Component.translatable("$baseKey.player", victim.displayName, driver.displayName)
            }
        }
    }
}
