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
    val seatLocalPos: Vector3d = Vector3d(0.0, seatOffset, 0.0),
    val render: BikeRenderDefinition = BikeRenderDefinition.DEFAULT_BIKE,
    val sounds: BikeSoundDefinition = BikeSoundDefinition.DEFAULT_ENGINE,
    val interactions: BikeInteractionDefinition = BikeInteractionDefinition.DEFAULT_BIKE,
    val factory: BikeFactory = BikeFactory(::DefaultBike)
)

data class BikeInteractionDefinition(
    val zones: List<BikeInteractionZone>
) {
    fun zone(id: String): BikeInteractionZone? = zones.firstOrNull { it.id == id }

    companion object {
        const val BODY = "body"
        const val SEAT = "seat"
        const val FUEL_CAP = "fuel_cap"

        val DEFAULT_BIKE = BikeInteractionDefinition(
            zones = listOf(
                BikeInteractionZone(
                    id = BODY,
                    center = Vector3d(0.0, 0.25, -0.05),
                    size = Vector3d(0.9, 0.95, 2.0)
                ),
                BikeInteractionZone(
                    id = SEAT,
                    center = Vector3d(0.0, 0.48, -0.25),
                    size = Vector3d(0.75, 0.58, 0.75)
                ),
                BikeInteractionZone(
                    id = FUEL_CAP,
                    center = Vector3d(0.0, 0.62, 0.15),
                    size = Vector3d(0.45, 0.25, 0.45)
                )
            )
        )

        val DIRT_BIKE = DEFAULT_BIKE.copy(
            zones = DEFAULT_BIKE.zones.map { zone ->
                if (zone.id == SEAT) {
                    zone.copy(
                        center = Vector3d(0.0, 0.36, -0.35),
                        size = Vector3d(0.85, 0.55, 0.85)
                    )
                } else {
                    zone
                }
            }
        )
    }
}

data class BikeInteractionZone(
    val id: String,
    val center: Vector3d,
    val size: Vector3d
)

data class BikeSoundDefinition(
    val engineLoop: ResourceLocation? = null,
    val engineStart: ResourceLocation? = null,
    val engineStop: ResourceLocation? = null,
    val idleVolume: Double = 0.18,
    val speedVolume: Double = 0.18,
    val throttleVolume: Double = 0.16,
    val idlePitch: Double = 0.72,
    val speedPitch: Double = 0.42,
    val throttlePitch: Double = 0.35,
    val minPitch: Double = 0.7,
    val maxPitch: Double = 1.45,
    val referenceSpeed: Double = 18.0
) {
    companion object {
        val DEFAULT_ENGINE = BikeSoundDefinition(
            engineLoop = ResourceLocation(SkyridersMod.MOD_ID, "bike_engine"),
            engineStart = ResourceLocation(SkyridersMod.MOD_ID, "bike_engine_start"),
            engineStop = ResourceLocation(SkyridersMod.MOD_ID, "bike_engine_stop"),
            idleVolume = 0.22,
            speedVolume = 0.22,
            throttleVolume = 0.18
        )

        val NONE = BikeSoundDefinition()
    }
}

data class BikeRenderDefinition(
    val model: ResourceLocation,
    val texture: ResourceLocation,
    val seatTexture: ResourceLocation = texture,
    val showWheels: Boolean = true,
    val frontWheelModel: ResourceLocation? = null,
    val rearWheelModel: ResourceLocation? = null,
    val frontWheelPivot: Vector3d = Vector3d(0.5, 0.1875, 0.0),
    val rearWheelPivot: Vector3d = Vector3d(0.5, 0.1875, 1.0),
    val modelYawRad: Double = Math.PI,
    val modelOffset: Vector3d = Vector3d(-0.8, -0.62, -0.8),
    val modelScale: Double = 1.6,
    val wheelSpinVisualScale: Double = 0.35,
    val wheelSpinSmoothingTime: Double = 0.08,
    val exhaustLocalPos: Vector3d = Vector3d(0.0, 0.35, -0.85),
    val tireParticleLocalYOffset: Double = -0.45,
    val wheelParts: List<VehicleWheelRenderDefinition> = emptyList()
) {
    companion object {
        val DEFAULT_BIKE = BikeRenderDefinition(
            model = ResourceLocation(SkyridersMod.MOD_ID, "bikes/debug_bike_body"),
            texture = ResourceLocation(SkyridersMod.MOD_ID, "textures/bikes/debug_bike.png"),
            frontWheelModel = ResourceLocation(SkyridersMod.MOD_ID, "bikes/debug_bike_front_wheel"),
            rearWheelModel = ResourceLocation(SkyridersMod.MOD_ID, "bikes/debug_bike_rear_wheel")
        )

        val DIRT_BIKE = BikeRenderDefinition(
            model = ResourceLocation(SkyridersMod.MOD_ID, "bikes/dirt_bike/dirt_bike_body"),
            texture = ResourceLocation(SkyridersMod.MOD_ID, "textures/bikes/dirt_bike.png"),
            frontWheelModel = null,
            rearWheelModel = null,
            modelOffset = Vector3d(-0.625, -0.413, -0.605),
            modelScale = 1.25,
            wheelSpinVisualScale = 0.35,
            wheelParts = listOf(
                VehicleWheelRenderDefinition(
                    id = "front_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "bikes/dirt_bike/dirt_bike_fwheel"),
                    visualOffset = Vector3d(0.0, -0.125, 0.0),
                    pivot = Vector3d(0.5, 0.234375, -0.0625),
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.FRONT
                ),
                VehicleWheelRenderDefinition(
                    id = "rear_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "bikes/dirt_bike/dirt_bike_bwheel"),
                    visualOffset = Vector3d(0.0, -0.125, 0.0),
                    pivot = Vector3d(0.5, 0.234375, 1.03125),
                    steerSource = VehicleWheelSteerSource.NONE,
                    spinSource = VehicleWheelSpinSource.REAR
                ),
                VehicleWheelRenderDefinition(
                    id = "steering_assembly",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "bikes/dirt_bike/dirt_bike_steer"),
                    pivot = Vector3d(0.5, 0.87272625, 0.289041875),
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.NONE
                )
            )
        )

        val HOVER_BIKE = DEFAULT_BIKE.copy(
            showWheels = false,
            frontWheelModel = null,
            rearWheelModel = null
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
        seatOffset = 0.24,
        seatLocalPos = Vector3d(0.0, 0.15, -0.35),
        render = BikeRenderDefinition.DIRT_BIKE,
        interactions = BikeInteractionDefinition.DIRT_BIKE
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
        sounds = BikeSoundDefinition.NONE,
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
