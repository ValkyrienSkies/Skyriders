package org.valkyrienskies.skyriders.content

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.bikes.DefaultBike
import org.valkyrienskies.skyriders.content.bikes.HoverBike

fun interface BikeFactory {
    fun create(
        bodyId: BodyId,
        boundingBox: AABB,
        level: Level,
        definition: BikeDefinition,
        state: BikeRuntimeState
    ): IBike
}

data class BikeDefinition(
    val id: ResourceLocation,
    val displayName: String,
    val config: BikePhysicsConfig,
    val seatOffset: Double,
    val render: BikeRenderDefinition = BikeRenderDefinition.DEFAULT_BIKE,
    val factory: BikeFactory = BikeFactory(::DefaultBike)
)

data class BikeRenderDefinition(
    val model: ResourceLocation,
    val texture: ResourceLocation,
    val seatTexture: ResourceLocation = texture,
    val showWheels: Boolean = true,
    val modelOffset: Vector3d = Vector3d(-0.5, -0.2, -0.5),
    val modelScale: Double = 1.45,
    val exhaustLocalPos: Vector3d = Vector3d(0.0, 0.35, -0.85),
    val tireParticleLocalYOffset: Double = -0.45
) {
    companion object {
        val DEFAULT_BIKE = BikeRenderDefinition(
            model = ResourceLocation(SkyridersMod.MOD_ID, "bikes/debug_bike"),
            texture = ResourceLocation(SkyridersMod.MOD_ID, "textures/bikes/debug_bike.png")
        )

        val HOVER_BIKE = DEFAULT_BIKE.copy(
            showWheels = false
        )
    }
}

object BikeDefinitions {
    val DEBUG_BIKE = BikeDefinition(
        id = ResourceLocation(SkyridersMod.MOD_ID, "debug_bike"),
        displayName = "Motorcycle",
        config = BikePhysicsConfig.MOTORCYCLE,
        seatOffset = 0.22
    )

    val DIRT_BIKE = BikeDefinition(
        id = ResourceLocation(SkyridersMod.MOD_ID, "dirt_bike"),
        displayName = "Dirt Bike",
        config = BikePhysicsConfig.DIRT_BIKE,
        seatOffset = 0.24
    )

    val CRUISER = BikeDefinition(
        id = ResourceLocation(SkyridersMod.MOD_ID, "cruiser"),
        displayName = "Cruiser",
        config = BikePhysicsConfig.CRUISER,
        seatOffset = 0.26
    )

    val HOVER_BIKE = BikeDefinition(
        id = ResourceLocation(SkyridersMod.MOD_ID, "hover_bike"),
        displayName = "Hover Bike",
        config = BikePhysicsConfig.HOVER_BIKE,
        seatOffset = 0.24,
        render = BikeRenderDefinition.HOVER_BIKE,
        factory = BikeFactory(::HoverBike)
    )

    private val definitionsById = listOf(DEBUG_BIKE, DIRT_BIKE, CRUISER, HOVER_BIKE).associateBy(BikeDefinition::id)

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
