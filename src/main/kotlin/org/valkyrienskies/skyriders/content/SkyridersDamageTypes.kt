package org.valkyrienskies.skyriders.content

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import org.valkyrienskies.skyriders.SkyridersMod

object SkyridersDamageTypes {
    val VEHICLE_IMPACT: ResourceKey<DamageType> = ResourceKey.create(
        Registries.DAMAGE_TYPE,
        ResourceLocation(SkyridersMod.MOD_ID, "vehicle_impact")
    )

    fun vehicleImpact(level: ServerLevel, driver: Entity? = null): DamageSource {
        val damageTypes = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
        val holder = damageTypes.getHolderOrThrow(VEHICLE_IMPACT)
        return if (driver != null) DamageSource(holder, driver) else DamageSource(holder)
    }
}
