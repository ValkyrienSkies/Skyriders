package org.valkyrienskies.skyriders.content

import net.minecraft.resources.ResourceLocation
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.bodies.properties.BodyTransform
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.skyriders.SkyridersMod

interface IVehicle {
    val id: String
    val bodyId: BodyId
    val level: Level
    val vehicleDefinition: VehicleDefinition
    val vehicleState: VehicleRuntimeState

    fun getRenderTransform(): BodyTransform

    fun tick()
    fun physTick(physLevel: PhysLevel, body: PhysVsBody, input: VehicleInput, dt: Double)
    fun toVehicleSaveRecord(): VehicleSaveRecord
}

fun interface VehicleFactory {
    fun create(
        bodyId: BodyId,
        level: Level,
        definition: VehicleDefinition,
        state: VehicleRuntimeState
    ): IVehicle
}

data class VehicleDefinition(
    val id: ResourceLocation,
    val displayName: String,
    val body: VehicleBodyDefinition,
    val render: VehicleRenderDefinition,
    val sounds: VehicleSoundDefinition,
    val seats: List<VehicleSeatDefinition>,
    val interactions: VehicleInteractionDefinition,
    val behavior: VehicleBehaviorDefinition
)

data class VehicleBodyDefinition(
    val collisionBoxSize: Vector3d,
    val collisionBoxOffset: Vector3d,
    val mass: Double,
    val centerOfMassOffset: Vector3d = Vector3d()
)

data class VehicleRenderDefinition(
    val model: ResourceLocation,
    val texture: ResourceLocation,
    val seatTexture: ResourceLocation = texture,
    val showWheels: Boolean = true,
    val frontWheelModel: ResourceLocation? = null,
    val rearWheelModel: ResourceLocation? = null,
    val frontWheelPivot: Vector3d = Vector3d(0.5, 0.1875, 0.0),
    val rearWheelPivot: Vector3d = Vector3d(0.5, 0.1875, 1.0),
    val modelYawRad: Double = Math.PI,
    val modelOffset: Vector3d = Vector3d(),
    val modelScale: Double = 1.0,
    val wheelSpinVisualScale: Double = 1.0,
    val wheelSpinSmoothingTime: Double = 0.08,
    val exhaustLocalPos: Vector3d = Vector3d(),
    val tireParticleLocalYOffset: Double = 0.0,
    val wheelParts: List<VehicleWheelRenderDefinition> = emptyList(),
    val exhaustPoints: List<VehicleEffectPointDefinition> = emptyList(),
    val tireParticlePoints: List<VehicleEffectPointDefinition> = emptyList()
) {
    fun resolvedWheelParts(): List<VehicleWheelRenderDefinition> {
        if (!showWheels) return emptyList()
        if (wheelParts.isNotEmpty()) return wheelParts

        return listOfNotNull(
            frontWheelModel?.let { model ->
                VehicleWheelRenderDefinition(
                    id = "front",
                    model = model,
                    pivot = Vector3d(frontWheelPivot),
                    steerSource = VehicleWheelSteerSource.FRONT,
                    spinSource = VehicleWheelSpinSource.FRONT
                )
            },
            rearWheelModel?.let { model ->
                VehicleWheelRenderDefinition(
                    id = "rear",
                    model = model,
                    pivot = Vector3d(rearWheelPivot),
                    steerSource = VehicleWheelSteerSource.NONE,
                    spinSource = VehicleWheelSpinSource.REAR
                )
            }
        )
    }

    fun resolvedExhaustPoints(): List<VehicleEffectPointDefinition> {
        return exhaustPoints.ifEmpty {
            listOf(VehicleEffectPointDefinition("exhaust", Vector3d(exhaustLocalPos)))
        }
    }

    fun resolvedTireParticlePoints(frontFallback: Vector3d, rearFallback: Vector3d): List<VehicleEffectPointDefinition> {
        return tireParticlePoints.ifEmpty {
            listOf(
                VehicleEffectPointDefinition("front_tire", frontFallback),
                VehicleEffectPointDefinition("rear_tire", rearFallback)
            )
        }
    }
}

data class VehicleWheelRenderDefinition(
    val id: String,
    val model: ResourceLocation,
    val pivot: Vector3d,
    val visualOffset: Vector3d = Vector3d(),
    val steerSource: VehicleWheelSteerSource = VehicleWheelSteerSource.NONE,
    val spinSource: VehicleWheelSpinSource = VehicleWheelSpinSource.NONE
)

enum class VehicleWheelSteerSource {
    NONE,
    FRONT
}

enum class VehicleWheelSpinSource {
    NONE,
    FRONT,
    REAR
}

data class VehicleEffectPointDefinition(
    val id: String,
    val localPos: Vector3d
)

data class VehicleSoundDefinition(
    val engineLoop: ResourceLocation? = null,
    val driftLoop: ResourceLocation? = null,
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
        val KART_ENGINE = VehicleSoundDefinition(
            engineLoop = ResourceLocation(SkyridersMod.MOD_ID, "kart_engine"),
            driftLoop = ResourceLocation(SkyridersMod.MOD_ID, "kart_drift"),
            engineStart = ResourceLocation(SkyridersMod.MOD_ID, "bike_engine_start"),
            engineStop = ResourceLocation(SkyridersMod.MOD_ID, "bike_engine_stop"),
            idleVolume = 0.2,
            speedVolume = 0.2,
            throttleVolume = 0.2,
            idlePitch = 0.8,
            speedPitch = 0.5,
            throttlePitch = 0.4,
            minPitch = 0.75,
            maxPitch = 1.5,
            referenceSpeed = 18.0
        )
    }
}

data class VehicleInteractionDefinition(
    val zones: List<VehicleInteractionZone>
) {
    fun zone(id: String): VehicleInteractionZone? = zones.firstOrNull { it.id == id }

    companion object {
        const val BODY = "body"
        const val SEAT = "seat"
        const val FUEL_CAP = "fuel_cap"
    }
}

data class VehicleInteractionZone(
    val id: String,
    val center: Vector3d,
    val size: Vector3d,
    val actions: Set<VehicleInteractionAction> = emptySet()
)

enum class VehicleInteractionAction {
    MOUNT,
    PICK_UP,
    OPEN_DOOR,
    REFUEL,
    STORAGE,
    ENGINE_TOGGLE
}

data class VehicleSeatDefinition(
    val id: String,
    val localPos: Vector3d,
    val role: VehicleSeatRole,
    val interactionZone: String
)

enum class VehicleSeatRole {
    DRIVER,
    PASSENGER
}

sealed interface VehicleBehaviorDefinition

data class BikeVehicleBehaviorDefinition(
    val physics: BikePhysicsConfig
) : VehicleBehaviorDefinition

data class KartVehicleBehaviorDefinition(
    val physics: KartPhysicsConfig
) : VehicleBehaviorDefinition

data class VehicleInput(
    val steer: Double = 0.0,
    val throttle: Double = 0.0,
    val brake: Double = 0.0,
    val jump: Double = 0.0,
    val pitch: Double = 0.0,
    val handbrake: Double = 0.0,
    val riderPresent: Boolean = false
) {
    fun clamped(): VehicleInput = copy(
        steer = steer.coerceIn(-1.0, 1.0),
        throttle = throttle.coerceIn(-1.0, 1.0),
        brake = brake.coerceIn(0.0, 1.0),
        jump = jump.coerceIn(0.0, 1.0),
        pitch = pitch.coerceIn(-1.0, 1.0),
        handbrake = handbrake.coerceIn(0.0, 1.0)
    )

    fun toBikeInput(): BikeInput = BikeInput(
        steer = steer,
        throttle = throttle,
        brake = brake.coerceAtLeast(handbrake),
        jump = jump,
        pitch = pitch,
        riderPresent = riderPresent
    ).clamped()

    companion object {
        val EMPTY = VehicleInput()
    }
}

data class VehicleRuntimeState(
    var engineOn: Boolean = false
)

data class VehicleSaveRecord(
    val bodyId: BodyId,
    val vehicleType: String,
    val engineOn: Boolean,
    val behaviorTag: CompoundTag = CompoundTag()
) {
    fun save(): CompoundTag = CompoundTag().apply {
        putLong(BODY_ID_KEY, bodyId)
        putString(VEHICLE_TYPE_KEY, vehicleType)
        putBoolean(ENGINE_ON_KEY, engineOn)
        put(BEHAVIOR_KEY, behaviorTag.copy())
    }

    companion object {
        private const val BODY_ID_KEY = "body_id"
        private const val VEHICLE_TYPE_KEY = "vehicle_type"
        private const val LEGACY_BIKE_TYPE_KEY = "bike_type"
        private const val ENGINE_ON_KEY = "engine_on"
        private const val BEHAVIOR_KEY = "behavior"

        fun load(tag: CompoundTag): VehicleSaveRecord = VehicleSaveRecord(
            bodyId = tag.getLong(BODY_ID_KEY),
            vehicleType = tag.getString(VEHICLE_TYPE_KEY).ifBlank { tag.getString(LEGACY_BIKE_TYPE_KEY) },
            engineOn = tag.getBoolean(ENGINE_ON_KEY),
            behaviorTag = tag.getCompound(BEHAVIOR_KEY).copy()
        )
    }
}

object VehicleDefinitions {
    val ids: Set<ResourceLocation>
        get() = BikeDefinitions.ids + KartDefinitions.ids

    fun get(id: ResourceLocation): VehicleDefinition? {
        return BikeDefinitions.get(id)?.toVehicleDefinition()
            ?: KartDefinitions.get(id)
    }

    fun resolveSavedId(savedId: String): ResourceLocation? {
        val id = if (':' in savedId) {
            ResourceLocation.tryParse(savedId)
        } else {
            ResourceLocation(SkyridersMod.MOD_ID, savedId)
        } ?: return null

        return id.takeIf { get(it) != null }
    }
}

fun BikeInput.toVehicleInput(): VehicleInput = VehicleInput(
    steer = steer,
    throttle = throttle,
    brake = brake,
    jump = jump,
    pitch = pitch,
    riderPresent = riderPresent
).clamped()

fun BikeRuntimeState.toVehicleRuntimeState(): VehicleRuntimeState = VehicleRuntimeState(
    engineOn = engineOn
)

fun BikeSaveRecord.toVehicleSaveRecord(): VehicleSaveRecord = VehicleSaveRecord(
    bodyId = bodyId,
    vehicleType = bikeType,
    engineOn = engineOn,
    behaviorTag = CompoundTag().apply {
        putString("behavior_type", "bike")
        putDouble("visual_lean", visualLeanRad)
        putDouble("front_wheel_spin", frontWheelSpin)
        putDouble("rear_wheel_spin", rearWheelSpin)
        putDouble("front_wheel_angular_velocity", frontWheelAngularVelocity)
        putDouble("rear_wheel_angular_velocity", rearWheelAngularVelocity)
    }
)

fun VehicleSaveRecord.toBikeSaveRecord(): BikeSaveRecord = BikeSaveRecord(
    bodyId = bodyId,
    bikeType = vehicleType,
    engineOn = engineOn,
    visualLeanRad = behaviorTag.getDouble("visual_lean"),
    frontWheelSpin = behaviorTag.getDouble("front_wheel_spin"),
    rearWheelSpin = behaviorTag.getDouble("rear_wheel_spin"),
    frontWheelAngularVelocity = behaviorTag.getDouble("front_wheel_angular_velocity"),
    rearWheelAngularVelocity = behaviorTag.getDouble("rear_wheel_angular_velocity")
)

fun BikeDefinition.toVehicleDefinition(): VehicleDefinition = VehicleDefinition(
    id = id,
    displayName = displayName,
    body = VehicleBodyDefinition(
        collisionBoxSize = Vector3d(config.collisionBoxSize),
        collisionBoxOffset = Vector3d(config.collisionBoxOffset),
        mass = config.mass,
        centerOfMassOffset = Vector3d(0.0, -0.2, 0.0)
    ),
    render = render.toVehicleRenderDefinition(),
    sounds = sounds.toVehicleSoundDefinition(),
    seats = listOf(
        VehicleSeatDefinition(
            id = VehicleInteractionDefinition.SEAT,
            localPos = Vector3d(seatLocalPos),
            role = VehicleSeatRole.DRIVER,
            interactionZone = VehicleInteractionDefinition.SEAT
        )
    ),
    interactions = interactions.toVehicleInteractionDefinition(),
    behavior = BikeVehicleBehaviorDefinition(config)
)

fun BikeRenderDefinition.toVehicleRenderDefinition(): VehicleRenderDefinition = VehicleRenderDefinition(
    model = model,
    texture = texture,
    seatTexture = seatTexture,
    showWheels = showWheels,
    frontWheelModel = frontWheelModel,
    rearWheelModel = rearWheelModel,
    frontWheelPivot = Vector3d(frontWheelPivot),
    rearWheelPivot = Vector3d(rearWheelPivot),
    modelYawRad = modelYawRad,
    modelOffset = Vector3d(modelOffset),
    modelScale = modelScale,
    wheelSpinVisualScale = wheelSpinVisualScale,
    wheelSpinSmoothingTime = wheelSpinSmoothingTime,
    exhaustLocalPos = Vector3d(exhaustLocalPos),
    tireParticleLocalYOffset = tireParticleLocalYOffset,
    wheelParts = wheelParts.map { part ->
        VehicleWheelRenderDefinition(
            id = part.id,
            model = part.model,
            pivot = Vector3d(part.pivot),
            visualOffset = Vector3d(part.visualOffset),
            steerSource = part.steerSource,
            spinSource = part.spinSource
        )
    }
)

fun BikeSoundDefinition.toVehicleSoundDefinition(): VehicleSoundDefinition = VehicleSoundDefinition(
    engineLoop = engineLoop,
    driftLoop = engineLoop,
    engineStart = engineStart,
    engineStop = engineStop,
    idleVolume = idleVolume,
    speedVolume = speedVolume,
    throttleVolume = throttleVolume,
    idlePitch = idlePitch,
    speedPitch = speedPitch,
    throttlePitch = throttlePitch,
    minPitch = minPitch,
    maxPitch = maxPitch,
    referenceSpeed = referenceSpeed
)

fun BikeInteractionDefinition.toVehicleInteractionDefinition(): VehicleInteractionDefinition =
    VehicleInteractionDefinition(
        zones = zones.map { zone ->
            VehicleInteractionZone(
                id = zone.id,
                center = Vector3d(zone.center),
                size = Vector3d(zone.size),
                actions = when (zone.id) {
                    BikeInteractionDefinition.SEAT -> setOf(VehicleInteractionAction.MOUNT)
                    BikeInteractionDefinition.BODY -> setOf(VehicleInteractionAction.PICK_UP)
                    BikeInteractionDefinition.FUEL_CAP -> setOf(VehicleInteractionAction.REFUEL)
                    else -> emptySet()
                }
            )
        }
    )
