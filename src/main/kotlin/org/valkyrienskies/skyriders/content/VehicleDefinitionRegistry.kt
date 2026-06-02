package org.valkyrienskies.skyriders.content

import net.minecraft.resources.ResourceLocation
import org.joml.Vector3d
import org.valkyrienskies.skyriders.SkyridersMod

object KartDefinitions {
    val DEBUG_KART = VehicleDefinition(
        id = ResourceLocation(SkyridersMod.MOD_ID, "debug_kart"),
        displayName = "Kart",
        body = VehicleBodyDefinition(
            collisionBoxSize = Vector3d(KartPhysicsConfig.DEBUG_KART.collisionBoxSize),
            collisionBoxOffset = Vector3d(KartPhysicsConfig.DEBUG_KART.collisionBoxOffset),
            mass = KartPhysicsConfig.DEBUG_KART.mass,
            centerOfMassOffset = Vector3d(0.0, -0.18, 0.0)
        ),
        render = BikeRenderDefinition.DEFAULT_BIKE.toVehicleRenderDefinition().copy(
            showWheels = false,
            modelScale = 1.45,
            modelOffset = Vector3d(-0.72, -0.58, -0.72),
            exhaustPoints = listOf(
                VehicleEffectPointDefinition("rear_exhaust", Vector3d(0.0, 0.22, -0.78))
            )
        ),
        sounds = BikeSoundDefinition.DEFAULT_ENGINE.toVehicleSoundDefinition(),
        seats = listOf(
            VehicleSeatDefinition(
                id = VehicleInteractionDefinition.SEAT,
                localPos = Vector3d(0.0, 0.2, -0.15),
                role = VehicleSeatRole.DRIVER,
                interactionZone = VehicleInteractionDefinition.SEAT
            )
        ),
        interactions = VehicleInteractionDefinition(
            zones = listOf(
                VehicleInteractionZone(
                    id = VehicleInteractionDefinition.BODY,
                    center = Vector3d(0.0, 0.28, 0.0),
                    size = Vector3d(1.45, 0.8, 1.8),
                    actions = setOf(VehicleInteractionAction.PICK_UP)
                ),
                VehicleInteractionZone(
                    id = VehicleInteractionDefinition.SEAT,
                    center = Vector3d(0.0, 0.48, -0.15),
                    size = Vector3d(0.95, 0.55, 0.85),
                    actions = setOf(VehicleInteractionAction.MOUNT)
                )
            )
        ),
        behavior = KartVehicleBehaviorDefinition(KartPhysicsConfig.DEBUG_KART)
    )

    private val definitionsById = listOf(DEBUG_KART).associateBy(VehicleDefinition::id)

    val ids: Set<ResourceLocation>
        get() = definitionsById.keys

    fun get(id: ResourceLocation): VehicleDefinition? {
        return definitionsById[id]
    }
}
