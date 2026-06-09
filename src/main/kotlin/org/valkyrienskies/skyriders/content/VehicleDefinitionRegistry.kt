package org.valkyrienskies.skyriders.content

import net.minecraft.resources.ResourceLocation
import org.joml.Vector3d
import org.valkyrienskies.skyriders.SkyridersMod

@Suppress("DEPRECATION", "DEPRECATION_ERROR")
object KartDefinitions {
    private val KART_PHYSICS = KartPhysicsConfig.DEBUG_KART.copy(
        collisionBoxSize = Vector3d(1.5, 0.58, 2.0),
        collisionBoxOffset = Vector3d(0.0, 0.3, 0.0),
        wheelLocalPositions = listOf(
            Vector3d(-0.61, -0.12, 0.82),
            Vector3d(0.61, -0.12, 0.82),
            Vector3d(-0.61, -0.12, -0.82),
            Vector3d(0.61, -0.12, -0.82)
        ),
        maxSteerRad = Math.toRadians(42.0),
        maxSteerHighSpeedRad = Math.toRadians(25.0),
        steeringHighSpeedStart = 8.0,
        steeringFullSpeed = 22.0,
        frontLateralGrip = 1.24,
        rearLateralGrip = 0.8,
        lateralSlipShape = 0.34,
        yawAssist = 4300.0,
        yawAssistMinSpeed = 0.35,
        yawAssistMaxSpeed = 16.0,
        driftYawAssist = 5200.0
    )

    private val SPEEDSTER_PHYSICS = KART_PHYSICS.copy(
        collisionBoxSize = Vector3d(1.5, 0.58, 1.72),
        collisionBoxOffset = Vector3d(0.0, 0.3, -0.14)
    )

    val DEBUG_KART = createKartDefinition(
        id = ResourceLocation(SkyridersMod.MOD_ID, "debug_kart"),
        displayName = "Debug Kart",
        physics = KartPhysicsConfig.DEBUG_KART,
        render = debugKartRender()
    )

    val KART = createKartDefinition(
        id = ResourceLocation(SkyridersMod.MOD_ID, "kart"),
        displayName = "Kart",
        physics = KART_PHYSICS,
        render = kartRender()
    )

    val SPEEDSTER = createKartDefinition(
        id = ResourceLocation(SkyridersMod.MOD_ID, "speedster"),
        displayName = "Speedster",
        physics = SPEEDSTER_PHYSICS,
        render = speedsterRender()
    )

    private val definitionsById = listOf(DEBUG_KART, KART, SPEEDSTER).associateBy(VehicleDefinition::id)

    val ids: Set<ResourceLocation>
        get() = definitionsById.keys

    fun get(id: ResourceLocation): VehicleDefinition? {
        return definitionsById[id]
    }

    private fun createKartDefinition(
        id: ResourceLocation,
        displayName: String,
        physics: KartPhysicsConfig,
        render: VehicleRenderDefinition
    ): VehicleDefinition = VehicleDefinition(
        id = id,
        displayName = displayName,
        body = VehicleBodyDefinition(
            collisionBoxSize = Vector3d(physics.collisionBoxSize),
            collisionBoxOffset = Vector3d(physics.collisionBoxOffset),
            mass = physics.mass,
            centerOfMassOffset = Vector3d(0.0, -0.18, 0.0)
        ),
        render = render,
        sounds = BikeSoundDefinition.DEFAULT_ENGINE.toVehicleSoundDefinition(),
        seats = listOf(
            VehicleSeatDefinition(
                id = VehicleInteractionDefinition.SEAT,
                localPos = Vector3d(0.0, 0.0, -0.15),
                role = VehicleSeatRole.DRIVER,
                interactionZone = VehicleInteractionDefinition.SEAT
            )
        ),
        interactions = VehicleInteractionDefinition(
            zones = listOf(
                VehicleInteractionZone(
                    id = VehicleInteractionDefinition.BODY,
                    center = Vector3d(0.0, 0.28, 0.0),
                    size = Vector3d(
                        physics.collisionBoxSize.x + 0.2,
                        physics.collisionBoxSize.y + 0.3,
                        physics.collisionBoxSize.z + 0.25
                    ),
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
        behavior = KartVehicleBehaviorDefinition(physics)
    )

    private fun debugKartRender(): VehicleRenderDefinition {
        return BikeRenderDefinition.DEFAULT_BIKE.toVehicleRenderDefinition().copy(
            model = ResourceLocation(SkyridersMod.MOD_ID, "karts/debug_kart_body"),
            showWheels = true,
            frontWheelModel = null,
            rearWheelModel = null,
            modelYawRad = 0.0,
            modelScale = 1.45,
            modelOffset = Vector3d(-0.72, -0.58, -0.72),
            wheelParts = listOf(
                VehicleWheelRenderDefinition(
                    id = "front_left_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "karts/debug_kart_front_left_wheel"),
                    pivot = Vector3d(0.1175, 0.3175, 0.9244),
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.FRONT
                ),
                VehicleWheelRenderDefinition(
                    id = "front_right_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "karts/debug_kart_front_right_wheel"),
                    pivot = Vector3d(0.8756, 0.3175, 0.9244),
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.FRONT
                ),
                VehicleWheelRenderDefinition(
                    id = "rear_left_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "karts/debug_kart_rear_left_wheel"),
                    pivot = Vector3d(0.1175, 0.3175, 0.0688),
                    steerSource = VehicleWheelSteerSource.NONE,
                    spinSource = VehicleWheelSpinSource.REAR
                ),
                VehicleWheelRenderDefinition(
                    id = "rear_right_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "karts/debug_kart_rear_right_wheel"),
                    pivot = Vector3d(0.8756, 0.3175, 0.0688),
                    steerSource = VehicleWheelSteerSource.NONE,
                    spinSource = VehicleWheelSpinSource.REAR
                )
            ),
            exhaustPoints = listOf(
                VehicleEffectPointDefinition("rear_exhaust", Vector3d(0.0, 0.22, -0.78))
            )
        )
    }

    private fun kartRender(): VehicleRenderDefinition {
        return debugKartRender().copy(
            model = ResourceLocation(SkyridersMod.MOD_ID, "karts/kart_body"),
            modelYawRad = Math.PI,
            modelScale = 1.45,
            modelOffset = Vector3d(-0.725, -0.392, -0.861),
            wheelParts = listOf(
                VehicleWheelRenderDefinition(
                    id = "front_left_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "karts/kart_front_left_wheel"),
                    pivot = Vector3d(0.078125, 0.1875, 0.03125),
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.FRONT
                ),
                VehicleWheelRenderDefinition(
                    id = "front_right_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "karts/kart_front_right_wheel"),
                    pivot = Vector3d(0.921875, 0.1875, 0.03125),
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.FRONT
                ),
                VehicleWheelRenderDefinition(
                    id = "rear_left_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "karts/kart_rear_left_wheel"),
                    pivot = Vector3d(0.078125, 0.1875, 1.15625),
                    steerSource = VehicleWheelSteerSource.NONE,
                    spinSource = VehicleWheelSpinSource.REAR
                ),
                VehicleWheelRenderDefinition(
                    id = "rear_right_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "karts/kart_rear_right_wheel"),
                    pivot = Vector3d(0.921875, 0.1875, 1.15625),
                    steerSource = VehicleWheelSteerSource.NONE,
                    spinSource = VehicleWheelSpinSource.REAR
                )
            ),
            exhaustPoints = listOf(
                VehicleEffectPointDefinition("rear_exhaust", Vector3d(0.0, 0.22, -1.0))
            )
        )
    }

    private fun speedsterRender(): VehicleRenderDefinition {
        val wheelVisualOffset = Vector3d(0.0, -0.16, 0.0)
        return debugKartRender().copy(
            model = ResourceLocation(SkyridersMod.MOD_ID, "karts/speedster/kart_frame"),
            texture = ResourceLocation(SkyridersMod.MOD_ID, "textures/karts/speedster.png"),
            seatTexture = ResourceLocation(SkyridersMod.MOD_ID, "textures/karts/speedster.png"),
            modelYawRad = Math.PI,
            modelScale = 1.45,
            modelOffset = Vector3d(-0.725, -0.256, -0.770),
            wheelParts = listOf(
                VehicleWheelRenderDefinition(
                    id = "front_left_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "karts/speedster/kart_flw"),
                    pivot = Vector3d(0.0625, 0.09375, -0.03125),
                    visualOffset = wheelVisualOffset,
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.FRONT
                ),
                VehicleWheelRenderDefinition(
                    id = "front_right_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "karts/speedster/kart_frw"),
                    pivot = Vector3d(0.90625, 0.09375, -0.03125),
                    visualOffset = wheelVisualOffset,
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.FRONT
                ),
                VehicleWheelRenderDefinition(
                    id = "rear_left_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "karts/speedster/kart_blw"),
                    pivot = Vector3d(0.0625, 0.09375, 1.09375),
                    visualOffset = wheelVisualOffset,
                    steerSource = VehicleWheelSteerSource.NONE,
                    spinSource = VehicleWheelSpinSource.REAR
                ),
                VehicleWheelRenderDefinition(
                    id = "rear_right_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "karts/speedster/kart_brw"),
                    pivot = Vector3d(0.90625, 0.09375, 1.09375),
                    visualOffset = wheelVisualOffset,
                    steerSource = VehicleWheelSteerSource.NONE,
                    spinSource = VehicleWheelSpinSource.REAR
                )
            ),
            exhaustPoints = listOf(
                VehicleEffectPointDefinition("left_engine_exhaust", Vector3d(-0.335, 0.37, -1.02)),
                VehicleEffectPointDefinition("right_engine_exhaust", Vector3d(0.291, 0.37, -1.055))
            )
        )
    }
}
