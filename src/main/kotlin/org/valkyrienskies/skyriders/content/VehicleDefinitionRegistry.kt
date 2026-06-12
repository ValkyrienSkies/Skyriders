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
        collisionBoxOffset = Vector3d(0.0, 0.3, -0.14),
        wheelTopSpeed = 30.0,
        driveForce = 9800.0,
        brakeForce = 6400.0,
        maxSteerHighSpeedRad = Math.toRadians(13.0),
        steeringHighSpeedStart = 9.0,
        steeringFullSpeed = 28.0,
        yawAssistMaxSpeed = 24.0,
        speedLimitSoftness = 0.16,
        driftTopSpeedMultiplier = 1.0,
        driftBoostForces = listOf(5200.0, 7600.0, 10000.0),
        driftBoostDurations = listOf(0.28, 0.42, 0.58),
        transmission = VehicleTransmissionConfig(
            automatic = false,
            manualClutch = false,
            finalDriveRatio = 3.2,
            reverseGearRatio = 3.0,
            forwardGears = listOf(
                VehicleTransmissionGearConfig(maxSpeed = 8.0, torqueMultiplier = 1.55, launchTorqueScale = 1.0, gearRatio = 3.0),
                VehicleTransmissionGearConfig(maxSpeed = 14.5, torqueMultiplier = 1.12, launchTorqueScale = 0.58, gearRatio = 2.0),
                VehicleTransmissionGearConfig(maxSpeed = 21.0, torqueMultiplier = 0.84, launchTorqueScale = 0.26, gearRatio = 1.35),
                VehicleTransmissionGearConfig(maxSpeed = 30.0, torqueMultiplier = 0.66, launchTorqueScale = 0.12, gearRatio = 1.0)
            ),
            reverseTopSpeed = 6.0,
            reverseTorqueMultiplier = 0.74,
            shiftCooldownSeconds = 0.25
        )
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
        render: VehicleRenderDefinition,
        fuelCapCenter: Vector3d = Vector3d(-0.52, 0.48, -0.42)
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
                ),
                VehicleInteractionZone(
                    id = VehicleInteractionDefinition.FUEL_CAP,
                    center = Vector3d(fuelCapCenter),
                    size = Vector3d(0.32, 0.32, 0.32),
                    actions = setOf(VehicleInteractionActions.REFUEL, VehicleInteractionActions.TOGGLE),
                    partId = VehicleInteractionDefinition.FUEL_CAP
                )
            )
        ),
        behavior = KartVehicleBehaviorDefinition(physics),
        parts = listOf(fuelCapPartDefinition())
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

    val PICKUP_TRUCK = pickupTruckDefinition()

    private val definitionsById = listOf(ATV, CAR, PICKUP_TRUCK).associateBy(VehicleDefinition::id)

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
                    actions = setOf(VehicleInteractionActions.REFUEL, VehicleInteractionActions.TOGGLE),
                    partId = VehicleInteractionDefinition.FUEL_CAP
                )
            )
        ),
        behavior = WheeledVehicleBehaviorDefinition(physics),
        parts = listOf(fuelCapPartDefinition())
    )

    private fun pickupTruckDefinition(): VehicleDefinition {
        val physics = WheeledVehiclePhysicsConfig.TRUCK
        val leftDoor = "left_door"
        val rightDoor = "right_door"
        val leftSeat = "left_seat"
        val rightSeat = "right_seat"
        return VehicleDefinition(
            id = ResourceLocation(SkyridersMod.MOD_ID, "pickup_truck"),
            displayName = "Pickup Truck",
            body = VehicleBodyDefinition(
                collisionBoxSize = Vector3d(physics.collisionBoxSize),
                collisionBoxOffset = Vector3d(physics.collisionBoxOffset),
                mass = physics.mass,
                centerOfMassOffset = Vector3d(0.0, -0.24, -0.1),
                collisionBoxes = listOf(
                    VehicleCollisionBoxDefinition(
                        size = Vector3d(2.54, 0.38, 2.25),
                        offset = Vector3d(0.0, -0.26, 0.65)
                    ),
                    VehicleCollisionBoxDefinition(
                        size = Vector3d(2.34, 0.12, 2.5),
                        offset = Vector3d(0.0, -0.48, -1.56)
                    ),
                    VehicleCollisionBoxDefinition(
                        size = Vector3d(2.32, 0.82, 1.25),
                        offset = Vector3d(0.0, 0.36, 0.25)
                    ),
                    VehicleCollisionBoxDefinition(
                        size = Vector3d(2.22, 0.24, 1.2),
                        offset = Vector3d(0.0, 1.04, 0.08)
                    ),
                    VehicleCollisionBoxDefinition(
                        size = Vector3d(2.18, 0.16, 1.02),
                        offset = Vector3d(0.0, 0.82, 0.86),
                        rotationDegrees = Vector3d(42.5, 0.0, 0.0)
                    ),
                    VehicleCollisionBoxDefinition(
                        size = Vector3d(2.34, 0.72, 1.34),
                        offset = Vector3d(0.0, 0.18, 1.38)
                    ),
                    VehicleCollisionBoxDefinition(
                        size = Vector3d(0.18, 0.42, 2.34),
                        offset = Vector3d(1.18, -0.22, -1.58)
                    ),
                    VehicleCollisionBoxDefinition(
                        size = Vector3d(0.18, 0.42, 2.34),
                        offset = Vector3d(-1.18, -0.22, -1.58)
                    ),
                    VehicleCollisionBoxDefinition(
                        size = Vector3d(2.42, 0.42, 0.18),
                        offset = Vector3d(0.0, -0.22, -2.72)
                    ),
                    VehicleCollisionBoxDefinition(
                        size = Vector3d(2.24, 0.44, 0.18),
                        offset = Vector3d(0.0, -0.2, -0.48)
                    )
                ),
                impactDamageScale = 1.65,
                impactDamageCap = 38.0
            ),
            render = truckRender(),
            sounds = VehicleSoundDefinition.GENERIC_ENGINE.copy(
                engineLoop = ResourceLocation(SkyridersMod.MOD_ID, "truck_engine"),
                engineStart = ResourceLocation(SkyridersMod.MOD_ID, "truck_engine_start"),
                engineStop = ResourceLocation(SkyridersMod.MOD_ID, "truck_engine_stop"),
                horn = ResourceLocation(SkyridersMod.MOD_ID, "truck_horn"),
                idlePitch = 0.64,
                speedPitch = 0.28,
                throttlePitch = 0.22,
                minPitch = 0.58,
                maxPitch = 1.12,
                referenceSpeed = 18.0
            ),
            seats = listOf(
                VehicleSeatDefinition(
                    id = "driver",
                    localPos = Vector3d(0.46, -0.65, 0.18),
                    role = VehicleSeatRole.DRIVER,
                    interactionZone = leftSeat,
                    requiredOpenPartId = leftDoor
                ),
                VehicleSeatDefinition(
                    id = "passenger",
                    localPos = Vector3d(-0.46, -0.65, 0.18),
                    role = VehicleSeatRole.PASSENGER,
                    interactionZone = rightSeat,
                    requiredOpenPartId = rightDoor
                )
            ),
            interactions = VehicleInteractionDefinition(
                zones = listOf(
                    VehicleInteractionZone(
                        id = leftDoor,
                        center = Vector3d(1.18, 0.92, 0.72),
                        size = Vector3d(0.36, 1.18, 1.24),
                        actions = setOf(VehicleInteractionActions.OPEN_DOOR),
                        partId = leftDoor,
                        sounds = truckDoorSounds()
                    ),
                    VehicleInteractionZone(
                        id = rightDoor,
                        center = Vector3d(-1.18, 0.92, 0.72),
                        size = Vector3d(0.36, 1.18, 1.24),
                        actions = setOf(VehicleInteractionActions.OPEN_DOOR),
                        partId = rightDoor,
                        sounds = truckDoorSounds()
                    ),
                    VehicleInteractionZone(
                        id = leftSeat,
                        center = Vector3d(0.46, 0.46, 0.18),
                        size = Vector3d(0.72, 0.72, 0.88),
                        actions = setOf(VehicleInteractionActions.MOUNT)
                    ),
                    VehicleInteractionZone(
                        id = rightSeat,
                        center = Vector3d(-0.46, 0.46, 0.18),
                        size = Vector3d(0.72, 0.72, 0.88),
                        actions = setOf(VehicleInteractionActions.MOUNT)
                    ),
                    VehicleInteractionZone(
                        id = "truck_bed",
                        center = Vector3d(0.0, 0.92, -1.62),
                        size = Vector3d(1.94, 0.86, 1.82),
                        actions = setOf(VehicleInteractionActions.STORAGE),
                        partId = "truck_bed"
                    ),
                    VehicleInteractionZone(
                        id = VehicleInteractionDefinition.FUEL_CAP,
                        center = Vector3d(-1.14, 0.76, -1.98),
                        size = Vector3d(0.36, 0.36, 0.36),
                        actions = setOf(VehicleInteractionActions.REFUEL, VehicleInteractionActions.TOGGLE),
                        partId = VehicleInteractionDefinition.FUEL_CAP
                    )
                )
            ),
            behavior = WheeledVehicleBehaviorDefinition(physics),
            fuel = VehicleFuelDefinition(
                capacity = 185.0,
                idleUsePerSecond = 0.024,
                throttleUsePerSecond = 0.098,
                motionWorkUsePerSpeedPerSecond = 0.003,
                driveWorkUsePerWorkSecond = 0.00115,
                stepAssistUsePerWorkSecond = 0.014
            ),
            parts = listOf(
                fuelCapPartDefinition(),
                doorPartDefinition(leftDoor),
                doorPartDefinition(rightDoor),
                VehiclePartDefinition(
                    id = "truck_bed",
                    type = VehiclePartTypes.STORAGE
                )
            )
        )
    }

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

    private fun truckRender(): VehicleRenderDefinition {
        val base = "vehicles/truck"
        return VehicleRenderDefinition(
            model = ResourceLocation(SkyridersMod.MOD_ID, "$base/bergentruck_body"),
            texture = ResourceLocation(SkyridersMod.MOD_ID, "textures/vehicles/bergentruckung.png"),
            seatTexture = ResourceLocation(SkyridersMod.MOD_ID, "textures/vehicles/bergentruckung.png"),
            showWheels = true,
            modelYawRad = Math.PI,
            modelScale = 1.28,
            modelOffset = Vector3d(-0.64, -1.08, -0.64),
            renderOpenModelNoCull = true,
            wheelSpinVisualScale = 0.46,
            wheelSpinSmoothingTime = 0.12,
            wheelParts = listOf(
                VehicleWheelRenderDefinition(
                    id = "rear_left_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "$base/bergentruck_brw"),
                    pivot = Vector3d(1.34375, 0.328125, -0.7531),
                    visualOffset = Vector3d(0.0, -0.20, 2.25),
                    steerSource = VehicleWheelSteerSource.NONE,
                    spinSource = VehicleWheelSpinSource.REAR
                ),
                VehicleWheelRenderDefinition(
                    id = "rear_right_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "$base/bergentruck_blw"),
                    pivot = Vector3d(-0.34375, 0.328125, -0.7531),
                    visualOffset = Vector3d(0.0, -0.20, 2.25),
                    steerSource = VehicleWheelSteerSource.NONE,
                    spinSource = VehicleWheelSpinSource.REAR
                ),
                VehicleWheelRenderDefinition(
                    id = "front_left_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "$base/bergentruck_brw"),
                    pivot = Vector3d(1.34375, 0.328125, -0.7531),
                    visualOffset = Vector3d(0.0, -0.20, 0.0),
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.FRONT
                ),
                VehicleWheelRenderDefinition(
                    id = "front_right_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "$base/bergentruck_blw"),
                    pivot = Vector3d(-0.34375, 0.328125, -0.7531),
                    visualOffset = Vector3d(0.0, -0.20, 0.0),
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.FRONT
                ),
                VehicleWheelRenderDefinition(
                    id = "steering_wheel",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "$base/bergentruck_steer"),
                    pivot = Vector3d(0.09375, 1.078125, -0.1906),
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.NONE,
                    steerAxis = VehicleVisualRotationAxis.Z,
                    steerVisualScale = 2.6
                )
            ),
            modelParts = listOf(
                VehicleModelPartRenderDefinition(
                    id = "left_door",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "$base/bergentruck_ldoor"),
                    pivot = Vector3d(-0.458915625, 1.156365625, -0.130723125),
                    openRotationDegrees = Vector3d(0.0, -68.0, 0.0),
                    partStateId = "left_door",
                    renderOpenModelNoCull = true
                ),
                VehicleModelPartRenderDefinition(
                    id = "right_door",
                    model = ResourceLocation(SkyridersMod.MOD_ID, "$base/bergentruck_rdoor"),
                    pivot = Vector3d(1.458915625, 1.156365625, -0.130723125),
                    openRotationDegrees = Vector3d(0.0, 68.0, 0.0),
                    partStateId = "right_door",
                    renderOpenModelNoCull = true
                )
            ),
            exhaustPoints = listOf(
                VehicleEffectPointDefinition("left_rear_exhaust", Vector3d(-0.76, 0.22, -2.46)),
                VehicleEffectPointDefinition("right_rear_exhaust", Vector3d(0.76, 0.22, -2.46))
            ),
            tireParticlePoints = listOf(
                VehicleEffectPointDefinition("front_left_tire", Vector3d(-1.02, -0.7, 1.95)),
                VehicleEffectPointDefinition("front_right_tire", Vector3d(1.02, -0.7, 1.95)),
                VehicleEffectPointDefinition("rear_left_tire", Vector3d(-1.02, -0.7, -1.85)),
                VehicleEffectPointDefinition("rear_right_tire", Vector3d(1.02, -0.7, -1.85))
            )
        )
    }

    private fun doorPartDefinition(id: String): VehiclePartDefinition = VehiclePartDefinition(
        id = id,
        type = VehiclePartTypes.DOOR,
        defaultState = net.minecraft.nbt.CompoundTag().apply { putBoolean("open", false) },
        interactionActions = setOf(VehicleInteractionActions.OPEN_DOOR)
    )

    private fun truckDoorSounds(): Map<ResourceLocation, VehicleInteractionSoundDefinition> {
        return mapOf(
            VehicleInteractionActions.OPEN to VehicleInteractionSoundDefinition(
                sound = ResourceLocation(SkyridersMod.MOD_ID, "truck_door_open"),
                volume = 0.72f,
                pitch = 1.0f
            ),
            VehicleInteractionActions.CLOSE to VehicleInteractionSoundDefinition(
                sound = ResourceLocation(SkyridersMod.MOD_ID, "truck_door_close"),
                volume = 0.72f,
                pitch = 1.0f
            )
        )
    }
}
