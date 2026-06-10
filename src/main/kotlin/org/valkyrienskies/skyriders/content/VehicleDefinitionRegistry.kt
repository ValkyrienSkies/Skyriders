package org.valkyrienskies.skyriders.content

import net.minecraft.nbt.CompoundTag
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
                    actions = setOf(VehicleInteractionActions.PICK_UP)
                ),
                VehicleInteractionZone(
                    id = VehicleInteractionDefinition.SEAT,
                    center = Vector3d(0.0, 0.48, -0.15),
                    size = Vector3d(0.95, 0.55, 0.85),
                    actions = setOf(VehicleInteractionActions.MOUNT)
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

@Suppress("DEPRECATION", "DEPRECATION_ERROR")
object WheeledVehicleDefinitions {
    val ATV = createWheeledDefinition(
        id = ResourceLocation(SkyridersMod.MOD_ID, "atv"),
        displayName = "ATV",
        physics = WheeledVehiclePhysicsConfig.ATV,
        render = atvRender(),
        seatLocalPos = Vector3d(0.0, 0.04, -0.18),
        seatZoneCenter = Vector3d(0.0, 0.48, -0.2),
        seatZoneSize = Vector3d(0.95, 0.58, 0.9),
        fuelCapCenter = Vector3d(-0.56, 0.46, -0.48)
    )

    val CAR = createWheeledDefinition(
        id = ResourceLocation(SkyridersMod.MOD_ID, "car"),
        displayName = "Generic Car",
        physics = WheeledVehiclePhysicsConfig.CAR,
        render = carRender(),
        seatLocalPos = Vector3d(0.0, 0.1, -0.55),
        seatZoneCenter = Vector3d(0.0, 0.62, -0.55),
        seatZoneSize = Vector3d(1.15, 0.72, 1.05),
        fuelCapCenter = Vector3d(-0.92, 0.55, -1.16)
    )

    private val definitionsById = listOf(ATV, CAR).associateBy(VehicleDefinition::id)

    val ids: Set<ResourceLocation>
        get() = definitionsById.keys

    fun get(id: ResourceLocation): VehicleDefinition? {
        return definitionsById[id]
    }

    private fun createWheeledDefinition(
        id: ResourceLocation,
        displayName: String,
        physics: WheeledVehiclePhysicsConfig,
        render: VehicleRenderDefinition,
        seatLocalPos: Vector3d,
        seatZoneCenter: Vector3d,
        seatZoneSize: Vector3d,
        fuelCapCenter: Vector3d
    ): VehicleDefinition = VehicleDefinition(
        id = id,
        displayName = displayName,
        body = VehicleBodyDefinition(
            collisionBoxSize = Vector3d(physics.collisionBoxSize),
            collisionBoxOffset = Vector3d(physics.collisionBoxOffset),
            mass = physics.mass,
            centerOfMassOffset = Vector3d(0.0, -0.2, 0.0)
        ),
        render = render,
        sounds = VehicleSoundDefinition.GENERIC_ENGINE,
        seats = listOf(
            VehicleSeatDefinition(
                id = VehicleInteractionDefinition.SEAT,
                localPos = Vector3d(seatLocalPos),
                role = VehicleSeatRole.DRIVER,
                interactionZone = VehicleInteractionDefinition.SEAT
            )
        ),
        interactions = VehicleInteractionDefinition(
            zones = listOf(
                VehicleInteractionZone(
                    id = VehicleInteractionDefinition.BODY,
                    center = Vector3d(0.0, 0.3, -0.05),
                    size = Vector3d(
                        physics.collisionBoxSize.x + 0.18,
                        physics.collisionBoxSize.y + 0.28,
                        physics.collisionBoxSize.z + 0.2
                    ),
                    actions = setOf(VehicleInteractionActions.PICK_UP)
                ),
                VehicleInteractionZone(
                    id = VehicleInteractionDefinition.SEAT,
                    center = Vector3d(seatZoneCenter),
                    size = Vector3d(seatZoneSize),
                    actions = setOf(VehicleInteractionActions.MOUNT)
                ),
                VehicleInteractionZone(
                    id = VehicleInteractionDefinition.FUEL_CAP,
                    center = Vector3d(fuelCapCenter),
                    size = Vector3d(0.32, 0.32, 0.32),
                    actions = setOf(VehicleInteractionActions.TOGGLE),
                    partId = VehicleInteractionDefinition.FUEL_CAP
                )
            )
        ),
        behavior = WheeledVehicleBehaviorDefinition(physics),
        parts = listOf(
            VehiclePartDefinition(
                id = VehicleInteractionDefinition.FUEL_CAP,
                type = VehiclePartTypes.FUEL_CAP,
                defaultState = CompoundTag().apply { putBoolean("open", false) },
                interactionActions = setOf(VehicleInteractionActions.TOGGLE)
            )
        )
    )

    private fun atvRender(): VehicleRenderDefinition {
        return VehicleRenderDefinition(
            model = ResourceLocation(SkyridersMod.MOD_ID, "vehicles/atv/atv_body"),
            texture = ResourceLocation(SkyridersMod.MOD_ID, "textures/vehicles/atv.png"),
            seatTexture = ResourceLocation(SkyridersMod.MOD_ID, "textures/vehicles/atv.png"),
            showWheels = true,
            modelYawRad = Math.PI,
            modelScale = 1.28,
            modelOffset = Vector3d(-0.64, -0.53, -0.64),
            wheelSpinVisualScale = 0.6,
            wheelSpinSmoothingTime = 0.08,
            wheelParts = listOf(
                VehicleWheelRenderDefinition(
                    id = "front_left_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "vehicles/atv/atv_flw"),
                    pivot = Vector3d(0.8453125, 0.234375, 0.0625),
                    visualOffset = Vector3d(-0.0015625, 0.0546875, -0.09375),
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.FRONT
                ),
                VehicleWheelRenderDefinition(
                    id = "front_right_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "vehicles/atv/atv_frw"),
                    pivot = Vector3d(0.8453125, 0.234375, 0.0625),
                    visualOffset = Vector3d(-0.6265625, 0.0546875, -0.09375),
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.FRONT
                ),
                VehicleWheelRenderDefinition(
                    id = "rear_left_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "vehicles/atv/atv_blw"),
                    pivot = Vector3d(0.8453125, 0.234375, 0.0625),
                    visualOffset = Vector3d(-0.0015625, 0.0546875, 0.96875),
                    steerSource = VehicleWheelSteerSource.NONE,
                    spinSource = VehicleWheelSpinSource.REAR
                ),
                VehicleWheelRenderDefinition(
                    id = "rear_right_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "vehicles/atv/atv_brw"),
                    pivot = Vector3d(0.8453125, 0.234375, 0.0625),
                    visualOffset = Vector3d(-0.6265625, 0.0546875, 0.96875),
                    steerSource = VehicleWheelSteerSource.NONE,
                    spinSource = VehicleWheelSpinSource.REAR
                ),
                VehicleWheelRenderDefinition(
                    id = "steering_handle",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "vehicles/atv/atv_steer"),
                    pivot = Vector3d(0.5, 0.81022625, 0.289041875),
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.NONE
                )
            ),
            exhaustPoints = listOf(
                VehicleEffectPointDefinition("rear_exhaust", Vector3d(0.0, 0.25, -0.74))
            )
        )
    }

    private fun carRender(): VehicleRenderDefinition {
        return VehicleRenderDefinition(
            model = ResourceLocation(SkyridersMod.MOD_ID, "karts/debug_kart_body"),
            texture = ResourceLocation(SkyridersMod.MOD_ID, "textures/karts/kart.png"),
            seatTexture = ResourceLocation(SkyridersMod.MOD_ID, "textures/karts/kart.png"),
            showWheels = true,
            modelYawRad = 0.0,
            modelScale = 2.15,
            modelOffset = Vector3d(-1.08, -0.72, -1.08),
            wheelSpinVisualScale = 0.72,
            wheelSpinSmoothingTime = 0.1,
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
                VehicleEffectPointDefinition("left_rear_exhaust", Vector3d(-0.36, 0.25, -1.55)),
                VehicleEffectPointDefinition("right_rear_exhaust", Vector3d(0.36, 0.25, -1.55))
            ),
            tireParticlePoints = listOf(
                VehicleEffectPointDefinition("front_left_tire", Vector3d(-0.74, -0.57, 1.25)),
                VehicleEffectPointDefinition("front_right_tire", Vector3d(0.74, -0.57, 1.25)),
                VehicleEffectPointDefinition("rear_left_tire", Vector3d(-0.74, -0.57, -1.25)),
                VehicleEffectPointDefinition("rear_right_tire", Vector3d(0.74, -0.57, -1.25))
            )
        )
    }
}
