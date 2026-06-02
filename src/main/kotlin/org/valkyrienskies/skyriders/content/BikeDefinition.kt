package org.valkyrienskies.skyriders.content

import net.minecraft.resources.ResourceLocation
import org.valkyrienskies.skyriders.SkyridersMod

data class BikeDefinition(
    val id: ResourceLocation,
    val displayName: String,
    val config: BikePhysicsConfig,
    val seatOffset: Double
)

object BikeDefinitions {
    val DEBUG_BIKE = BikeDefinition(
        id = ResourceLocation(SkyridersMod.MOD_ID, "debug_bike"),
        displayName = "Debug Bike",
        config = BikePhysicsConfig.DEBUG_MOTORCYCLE,
        seatOffset = 0.22
    )

    private val definitionsById = listOf(DEBUG_BIKE).associateBy(BikeDefinition::id)

    val ids: Set<ResourceLocation>
        get() = definitionsById.keys

    fun get(id: ResourceLocation): BikeDefinition? {
        return definitionsById[id]
    }

    fun resolveSavedId(savedId: String): ResourceLocation? {
        if (':' !in savedId) {
            return ResourceLocation(SkyridersMod.MOD_ID, savedId)
        }
        return ResourceLocation.tryParse(savedId)
    }
}
