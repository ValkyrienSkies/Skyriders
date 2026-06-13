package org.valkyrienskies.skyriders.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.client.BikeClientEffects
import org.valkyrienskies.skyriders.client.BikeClientHoistState
import org.valkyrienskies.skyriders.client.BikeDebugOverlay
import org.valkyrienskies.skyriders.client.ClientBikeSyncHandler
import org.valkyrienskies.skyriders.client.RaceCompassClientState
import org.valkyrienskies.skyriders.client.RaceHudClientState
import org.valkyrienskies.skyriders.client.RaceMusicClientState
import org.valkyrienskies.skyriders.client.RaceResultsClientState
import org.valkyrienskies.skyriders.client.RacingClientSounds
import org.valkyrienskies.skyriders.client.VehicleHudOverlay
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.BikeInteractionHandler
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.BikeSaveRecord
import org.valkyrienskies.skyriders.content.BikeVehicleBehaviorDefinition
import org.valkyrienskies.skyriders.content.IBike
import org.valkyrienskies.skyriders.content.KartVehicleBehaviorDefinition
import org.valkyrienskies.skyriders.content.VehicleInput
import org.valkyrienskies.skyriders.content.VehicleFuel
import org.valkyrienskies.skyriders.content.VehicleDamageEvents
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleSaveRecord
import org.valkyrienskies.skyriders.content.VehicleImpairmentEffects
import org.valkyrienskies.skyriders.content.WheeledVehicleBehaviorDefinition
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.content.toVehicleInput
import java.util.UUID
import java.util.function.Supplier
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle

object SkyridersNetwork {
    private const val PROTOCOL_VERSION = "3"
    private const val VEHICLE_DEBUG_SYNC_ENABLED = false
    private const val VEHICLE_VISUAL_ENGINE_ON_FLAG = 1
    private const val VEHICLE_VISUAL_LEAN_FLAG = 1 shl 1
    private const val VEHICLE_VISUAL_MOONDROP_FLAG = 1 shl 2
    private const val MAX_RACE_RESULT_ENTRIES = 16
    private const val MAX_RACE_RESULT_TEXT_LENGTH = 48
    private var nextPacketId = 0

    private val CHANNEL: SimpleChannel = NetworkRegistry.ChannelBuilder
        .named(ResourceLocation(SkyridersMod.MOD_ID, "main"))
        .networkProtocolVersion { PROTOCOL_VERSION }
        .clientAcceptedVersions(PROTOCOL_VERSION::equals)
        .serverAcceptedVersions(PROTOCOL_VERSION::equals)
        .simpleChannel()

    fun register() {
        CHANNEL.registerMessage(
            nextPacketId++,
            VehicleInputPacket::class.java,
            VehicleInputPacket::encode,
            VehicleInputPacket::decode,
            VehicleInputPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            BikeDismountPacket::class.java,
            BikeDismountPacket::encode,
            BikeDismountPacket::decode,
            BikeDismountPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            BikeEngineTogglePacket::class.java,
            BikeEngineTogglePacket::encode,
            BikeEngineTogglePacket::decode,
            BikeEngineTogglePacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            VehicleControlActionPacket::class.java,
            VehicleControlActionPacket::encode,
            VehicleControlActionPacket::decode,
            VehicleControlActionPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            BikeSyncPacket::class.java,
            BikeSyncPacket::encode,
            BikeSyncPacket::decode,
            BikeSyncPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            VehicleSyncPacket::class.java,
            VehicleSyncPacket::encode,
            VehicleSyncPacket::decode,
            VehicleSyncPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            BikeDebugPacket::class.java,
            BikeDebugPacket::encode,
            BikeDebugPacket::decode,
            BikeDebugPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            VehicleTelemetryPacket::class.java,
            VehicleTelemetryPacket::encode,
            VehicleTelemetryPacket::decode,
            VehicleTelemetryPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            VehicleDebugPacket::class.java,
            VehicleDebugPacket::encode,
            VehicleDebugPacket::decode,
            VehicleDebugPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            BikeVisualStatePacket::class.java,
            BikeVisualStatePacket::encode,
            BikeVisualStatePacket::decode,
            BikeVisualStatePacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            VehicleVisualStatePacket::class.java,
            VehicleVisualStatePacket::encode,
            VehicleVisualStatePacket::decode,
            VehicleVisualStatePacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            BikeUsePacket::class.java,
            BikeUsePacket::encode,
            BikeUsePacket::decode,
            BikeUsePacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            VehicleMeleeAttackPacket::class.java,
            VehicleMeleeAttackPacket::encode,
            VehicleMeleeAttackPacket::decode,
            VehicleMeleeAttackPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            BikeHoistStatePacket::class.java,
            BikeHoistStatePacket::encode,
            BikeHoistStatePacket::decode,
            BikeHoistStatePacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            RaceCompassTargetPacket::class.java,
            RaceCompassTargetPacket::encode,
            RaceCompassTargetPacket::decode,
            RaceCompassTargetPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            MoondropMusicPacket::class.java,
            MoondropMusicPacket::encode,
            MoondropMusicPacket::decode,
            MoondropMusicPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            RaceMusicPacket::class.java,
            RaceMusicPacket::encode,
            RaceMusicPacket::decode,
            RaceMusicPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            RaceHudPacket::class.java,
            RaceHudPacket::encode,
            RaceHudPacket::decode,
            RaceHudPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            RaceResultsPacket::class.java,
            RaceResultsPacket::encode,
            RaceResultsPacket::decode,
            RaceResultsPacket::handle
        )
        CHANNEL.registerMessage(
            nextPacketId++,
            RocketExplosionSoundPacket::class.java,
            RocketExplosionSoundPacket::encode,
            RocketExplosionSoundPacket::decode,
            RocketExplosionSoundPacket::handle
        )
    }

    fun sendBikeInput(input: BikeInput) {
        sendVehicleInput(input.toVehicleInput())
    }

    fun sendVehicleInput(input: VehicleInput) {
        CHANNEL.sendToServer(VehicleInputPacket(input))
    }

    fun sendBikeDismount() {
        CHANNEL.sendToServer(BikeDismountPacket())
    }

    fun sendBikeEngineToggle() {
        CHANNEL.sendToServer(BikeEngineTogglePacket())
    }

    fun sendVehicleControlAction(action: ResourceLocation) {
        CHANNEL.sendToServer(VehicleControlActionPacket(action))
    }

    fun sendBikeUse(shiftDown: Boolean = false) {
        CHANNEL.sendToServer(BikeUsePacket(shiftDown))
    }

    fun sendVehicleMeleeAttack() {
        CHANNEL.sendToServer(VehicleMeleeAttackPacket())
    }

    fun sendBikeSync(player: ServerPlayer, records: List<BikeSaveRecord>) {
        SkyridersNetworkStats.record("S2C.BikeSync", estimateBikeSyncBytes(records), records.size)
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, BikeSyncPacket(records))
    }

    fun sendVehicleSync(player: ServerPlayer, records: List<VehicleSaveRecord>) {
        SkyridersNetworkStats.record("S2C.VehicleSync", estimateVehicleSyncBytes(records), records.size)
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, VehicleSyncPacket(records))
    }

    fun sendBikeDebug(player: ServerPlayer, bike: IBike) {
        val state = bike.state
        val bikeId = bike.id
        val bikeName = bike.definition.displayName
        SkyridersNetworkStats.record("S2C.BikeDebug", estimateBikeDebugBytes(bikeId, bikeName))
        CHANNEL.send(
            PacketDistributor.PLAYER.with { player },
            BikeDebugPacket(
                bodyId = bike.bodyId,
                bikeId = bikeId,
                bikeName = bikeName,
                speed = state.debugSpeed,
                frontGrounded = state.debugFrontWheelGrounded,
                rearGrounded = state.debugRearWheelGrounded,
                throttle = state.debugThrottle,
                steeringAngleRad = state.debugSteeringAngleRad,
                drifting = state.debugDrifting,
                engineOn = state.engineOn,
                driftBoostCharge = state.driftBoostCharge,
                driftBoostLevel = state.driftBoostLevel,
                jumpCharge = state.jumpCharge
            )
        )
    }

    fun sendVehicleTelemetry(player: ServerPlayer, vehicle: org.valkyrienskies.skyriders.content.IVehicle) {
        val input = VehicleManager.getInput(player.level().dimensionId, vehicle.bodyId)
        val speed = try {
            player.level().shipWorld?.allBodies?.getById(vehicle.bodyId)?.kinematics?.velocity?.length() ?: 0.0
        } catch (_: IllegalStateException) {
            0.0
        }
        val maxSpeed = when (vehicle) {
            is KartVehicle -> (vehicle.vehicleDefinition.behavior as KartVehicleBehaviorDefinition).physics.wheelTopSpeed
            is WheeledVehicle -> (vehicle.vehicleDefinition.behavior as WheeledVehicleBehaviorDefinition).physics.wheelTopSpeed
            is IBike -> (vehicle.vehicleDefinition.behavior as BikeVehicleBehaviorDefinition).physics.wheelTopSpeed
            else -> 0.0
        }
        val groundedCount = when (vehicle) {
            is KartVehicle -> vehicle.kartState.debugGroundedWheels
            is WheeledVehicle -> vehicle.wheeledState.debugGroundedWheels
            is IBike -> listOf(vehicle.state.debugFrontWheelGrounded, vehicle.state.debugRearWheelGrounded).count { it }
            else -> -1
        }
        val drifting = when (vehicle) {
            is KartVehicle -> vehicle.kartState.drifting
            is WheeledVehicle -> vehicle.wheeledState.drifting
            is IBike -> vehicle.state.debugDrifting
            else -> false
        }
        val driftBoostCharge = when (vehicle) {
            is KartVehicle -> vehicle.kartState.driftBoostCharge
            is WheeledVehicle -> vehicle.wheeledState.driftBoostCharge
            is IBike -> vehicle.state.driftBoostCharge
            else -> 0.0
        }
        val driftBoostLevel = when (vehicle) {
            is KartVehicle -> vehicle.kartState.driftBoostLevel
            is WheeledVehicle -> vehicle.wheeledState.driftBoostLevel
            is IBike -> vehicle.state.driftBoostLevel
            else -> 0
        }
        val forwardSpeed = when (vehicle) {
            is KartVehicle -> vehicle.kartState.debugForwardSpeed
            is WheeledVehicle -> vehicle.wheeledState.debugForwardSpeed
            is IBike -> vehicle.state.debugSpeed
            else -> speed
        }
        val driftBoostTimeRemaining = when (vehicle) {
            is KartVehicle -> vehicle.kartState.driftBoostTimeRemaining
            is WheeledVehicle -> vehicle.wheeledState.driftBoostTimeRemaining
            is IBike -> vehicle.state.driftBoostTimeRemaining
            else -> 0.0
        }
        val transmissionGear = when (vehicle) {
            is KartVehicle -> vehicle.kartState.debugTransmissionGear
            is WheeledVehicle -> vehicle.wheeledState.debugTransmissionGear
            else -> 0
        }
        val parkingBrakeEngaged = when (vehicle) {
            is WheeledVehicle -> vehicle.wheeledState.debugParkingBrake
            else -> false
        }
        val engineRpm = when (vehicle) {
            is KartVehicle -> vehicle.kartState.debugEngineRpm
            is WheeledVehicle -> vehicle.wheeledState.debugEngineRpm
            else -> 0.0
        }
        val engineStalled = when (vehicle) {
            is WheeledVehicle -> vehicle.wheeledState.debugEngineStalled
            else -> false
        }
        val jumpCharge = when (vehicle) {
            is IBike -> vehicle.state.jumpCharge
            else -> 0.0
        }
        val hasTransmission = when (vehicle) {
            is KartVehicle -> (vehicle.vehicleDefinition.behavior as? KartVehicleBehaviorDefinition)?.physics?.transmission != null
            is WheeledVehicle -> true
            else -> false
        }
        val hasJump = vehicle.vehicleDefinition.behavior is BikeVehicleBehaviorDefinition

        val packet = VehicleTelemetryPacket(
            bodyId = vehicle.bodyId,
            maxSpeed = maxSpeed,
            speed = speed,
            engineOn = vehicle.vehicleState.engineOn,
            fuel = VehicleFuel.fraction(vehicle),
            throttle = input.throttle,
            steer = input.steer,
            groundedCount = groundedCount,
            hasJump = hasJump,
            jumpCharge = jumpCharge,
            drifting = drifting,
            driftBoostCharge = driftBoostCharge,
            driftBoostLevel = driftBoostLevel,
            forwardSpeed = forwardSpeed,
            driftBoostTimeRemaining = driftBoostTimeRemaining,
            hasTransmission = hasTransmission,
            transmissionGear = transmissionGear,
            parkingBrakeEngaged = parkingBrakeEngaged,
            engineRpm = engineRpm,
            engineStalled = engineStalled
        )
        SkyridersNetworkStats.record("S2C.VehicleTelemetry", estimateVehicleTelemetryBytes(packet))
        CHANNEL.send(
            PacketDistributor.PLAYER.with { player },
            packet
        )
    }

    fun sendVehicleDebug(player: ServerPlayer, vehicle: org.valkyrienskies.skyriders.content.IVehicle) {
        if (!VEHICLE_DEBUG_SYNC_ENABLED) return

        val input = VehicleManager.getInput(player.level().dimensionId, vehicle.bodyId)
        val speed = try {
            player.level().shipWorld?.allBodies?.getById(vehicle.bodyId)?.kinematics?.velocity?.length() ?: 0.0
        } catch (_: IllegalStateException) {
            0.0
        }
        val groundedCount = when (vehicle) {
            is KartVehicle -> vehicle.kartState.debugGroundedWheels
            is WheeledVehicle -> vehicle.wheeledState.debugGroundedWheels
            is IBike -> listOf(vehicle.state.debugFrontWheelGrounded, vehicle.state.debugRearWheelGrounded).count { it }
            else -> -1
        }
        val drifting = when (vehicle) {
            is KartVehicle -> vehicle.kartState.drifting
            is WheeledVehicle -> vehicle.wheeledState.drifting
            is IBike -> vehicle.state.debugDrifting
            else -> false
        }
        val driftBoostCharge = when (vehicle) {
            is KartVehicle -> vehicle.kartState.driftBoostCharge
            is WheeledVehicle -> vehicle.wheeledState.driftBoostCharge
            is IBike -> vehicle.state.driftBoostCharge
            else -> 0.0
        }
        val driftBoostLevel = when (vehicle) {
            is KartVehicle -> vehicle.kartState.driftBoostLevel
            is WheeledVehicle -> vehicle.wheeledState.driftBoostLevel
            is IBike -> vehicle.state.driftBoostLevel
            else -> 0
        }
        val lateralSlip = when (vehicle) {
            is KartVehicle -> vehicle.kartState.debugLateralSlip
            is WheeledVehicle -> vehicle.wheeledState.debugLateralSlip
            else -> 0.0
        }
        val forwardSpeed = when (vehicle) {
            is KartVehicle -> vehicle.kartState.debugForwardSpeed
            is WheeledVehicle -> vehicle.wheeledState.debugForwardSpeed
            is IBike -> vehicle.state.debugSpeed
            else -> speed
        }
        val steerAngleRad = when (vehicle) {
            is KartVehicle -> vehicle.kartState.debugSteerRad
            is WheeledVehicle -> vehicle.wheeledState.debugSteerRad
            is IBike -> vehicle.state.debugSteeringAngleRad
            else -> 0.0
        }
        val driftBoostTimeRemaining = when (vehicle) {
            is KartVehicle -> vehicle.kartState.driftBoostTimeRemaining
            is WheeledVehicle -> vehicle.wheeledState.driftBoostTimeRemaining
            is IBike -> vehicle.state.driftBoostTimeRemaining
            else -> 0.0
        }
        val transmissionGear = when (vehicle) {
            is KartVehicle -> vehicle.kartState.debugTransmissionGear
            is WheeledVehicle -> vehicle.wheeledState.debugTransmissionGear
            else -> 0
        }
        val parkingBrakeEngaged = when (vehicle) {
            is WheeledVehicle -> vehicle.wheeledState.debugParkingBrake
            else -> false
        }
        val engineRpm = when (vehicle) {
            is KartVehicle -> vehicle.kartState.debugEngineRpm
            is WheeledVehicle -> vehicle.wheeledState.debugEngineRpm
            else -> 0.0
        }
        val clutchEngagement = when (vehicle) {
            is WheeledVehicle -> vehicle.wheeledState.debugClutchEngagement
            else -> 0.0
        }
        val engineStalled = when (vehicle) {
            is WheeledVehicle -> vehicle.wheeledState.debugEngineStalled
            else -> false
        }
        val hasTransmission = when (vehicle) {
            is KartVehicle -> (vehicle.vehicleDefinition.behavior as? KartVehicleBehaviorDefinition)?.physics?.transmission != null
            is WheeledVehicle -> true
            else -> false
        }

        val packet = VehicleDebugPacket(
            bodyId = vehicle.bodyId,
            vehicleId = vehicle.id,
            vehicleName = vehicle.vehicleDefinition.displayName,
            speed = speed,
            engineOn = vehicle.vehicleState.engineOn,
            throttle = input.throttle,
            steer = input.steer,
            groundedCount = groundedCount,
            drifting = drifting,
            driftBoostCharge = driftBoostCharge,
            driftBoostLevel = driftBoostLevel,
            lateralSlip = lateralSlip,
            forwardSpeed = forwardSpeed,
            steerAngleRad = steerAngleRad,
            driftBoostTimeRemaining = driftBoostTimeRemaining,
            hasTransmission = hasTransmission,
            transmissionGear = transmissionGear,
            parkingBrakeEngaged = parkingBrakeEngaged,
            engineRpm = engineRpm,
            clutchEngagement = clutchEngagement,
            engineStalled = engineStalled
        )
        SkyridersNetworkStats.record("S2C.VehicleDebug", estimateVehicleDebugBytes(packet))
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, packet)
    }

    fun sendBikeVisualState(player: ServerPlayer, bikes: List<IBike>) {
        SkyridersNetworkStats.record("S2C.BikeVisualState", estimateBikeVisualStateBytes(bikes), bikes.size)
        CHANNEL.send(
            PacketDistributor.PLAYER.with { player },
            BikeVisualStatePacket(
                bikes.map { bike ->
                    val state = bike.state
                    BikeVisualState(
                        bodyId = bike.bodyId,
                        engineOn = state.engineOn,
                        visualLeanRad = state.visualLeanRad,
                        visualSteerRad = state.visualSteerRad,
                        frontWheelSpin = state.frontWheelSpin,
                        rearWheelSpin = state.rearWheelSpin,
                        frontWheelAngularVelocity = state.frontWheelAngularVelocity,
                        rearWheelAngularVelocity = state.rearWheelAngularVelocity,
                        frontWheelSuspensionOffset = state.frontWheelSuspensionOffset,
                        rearWheelSuspensionOffset = state.rearWheelSuspensionOffset
                    )
                }
            )
        )
    }

    fun sendVehicleVisualState(player: ServerPlayer, vehicles: List<org.valkyrienskies.skyriders.content.IVehicle>) {
        val states = vehicles.mapNotNull { vehicle ->
            when (vehicle) {
                is IBike -> {
                    val state = vehicle.state
                    VehicleVisualState(
                        bodyId = vehicle.bodyId,
                        engineOn = state.engineOn,
                        visualLeanRad = state.visualLeanRad,
                        frontSteerRad = state.visualSteerRad,
                        frontWheelSpin = state.frontWheelSpin,
                        rearWheelSpin = state.rearWheelSpin,
                        frontWheelAngularVelocity = state.frontWheelAngularVelocity,
                        rearWheelAngularVelocity = state.rearWheelAngularVelocity,
                        frontWheelSuspensionOffset = state.frontWheelSuspensionOffset,
                        rearWheelSuspensionOffset = state.rearWheelSuspensionOffset,
                        moondropActive = VehicleStatusEffects.isMoondropActive(vehicle)
                    )
                }
                is KartVehicle -> {
                    val state = vehicle.kartState
                    VehicleVisualState(
                        bodyId = vehicle.bodyId,
                        engineOn = state.engineOn,
                        frontSteerRad = state.debugSteerRad,
                        frontWheelSpin = state.frontWheelSpin,
                        rearWheelSpin = state.rearWheelSpin,
                        frontWheelAngularVelocity = state.frontWheelAngularVelocity,
                        rearWheelAngularVelocity = state.rearWheelAngularVelocity,
                        frontWheelSuspensionOffset = state.frontWheelSuspensionOffset,
                        rearWheelSuspensionOffset = state.rearWheelSuspensionOffset,
                        moondropActive = VehicleStatusEffects.isMoondropActive(vehicle)
                    )
                }
                is WheeledVehicle -> {
                    val state = vehicle.wheeledState
                    VehicleVisualState(
                        bodyId = vehicle.bodyId,
                        engineOn = state.engineOn,
                        frontSteerRad = state.debugSteerRad,
                        frontWheelSpin = state.frontWheelSpin,
                        rearWheelSpin = state.rearWheelSpin,
                        frontWheelAngularVelocity = state.frontWheelAngularVelocity,
                        rearWheelAngularVelocity = state.rearWheelAngularVelocity,
                        frontWheelSuspensionOffset = state.frontWheelSuspensionOffset,
                        rearWheelSuspensionOffset = state.rearWheelSuspensionOffset,
                        moondropActive = VehicleStatusEffects.isMoondropActive(vehicle)
                    )
                }
                else -> null
            }
        }
        if (states.isEmpty()) return
        SkyridersNetworkStats.record("S2C.VehicleVisualState", estimateVehicleVisualStateBytes(states), states.size)
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, VehicleVisualStatePacket(states))
    }

    fun sendBikeHoistState(player: ServerPlayer, hoisting: Boolean, bodyId: Long = -1L) {
        SkyridersNetworkStats.record("S2C.BikeHoistState", 9)
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, BikeHoistStatePacket(hoisting, bodyId))
    }

    fun sendRaceCompassTarget(player: ServerPlayer, target: Vec3?) {
        SkyridersNetworkStats.record("S2C.RaceCompassTarget", if (target != null) 25 else 1)
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, RaceCompassTargetPacket(target != null, target ?: Vec3.ZERO))
    }

    fun sendRaceMusicStart(player: ServerPlayer, track: ResourceLocation) {
        SkyridersNetworkStats.record("S2C.RaceMusic", 1 + estimateResourceLocationBytes(track))
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, RaceMusicPacket(true, track))
    }

    fun sendRaceMusicStop(player: ServerPlayer) {
        SkyridersNetworkStats.record("S2C.RaceMusic", 1)
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, RaceMusicPacket(false, EMPTY_SOUND))
    }

    fun sendMoondropMusic(player: ServerPlayer, durationTicks: Int) {
        SkyridersNetworkStats.record("S2C.MoondropMusic", 4)
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, MoondropMusicPacket(durationTicks.coerceAtLeast(1)))
    }

    fun sendRaceHudPosition(
        player: ServerPlayer,
        bodyId: Long,
        place: Int,
        total: Int,
        lap: Int,
        totalLaps: Int,
        lapElapsedTicks: Long
    ) {
        SkyridersNetworkStats.record("S2C.RaceHud", 33)
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, RaceHudPacket(true, bodyId, place, total, lap, totalLaps, lapElapsedTicks))
    }

    fun sendRaceHudClear(player: ServerPlayer) {
        SkyridersNetworkStats.record("S2C.RaceHud", 1)
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, RaceHudPacket(false, -1L, 0, 0, 0, 0, 0L))
    }

    fun sendRaceResults(player: ServerPlayer, results: List<RaceResultEntry>) {
        val capped = results.take(MAX_RACE_RESULT_ENTRIES)
        val bytes = 1 + capped.sumOf { 32 + estimateUtfBytes(it.playerName) + estimateUtfBytes(it.vehicleType) }
        SkyridersNetworkStats.record("S2C.RaceResults", bytes)
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, RaceResultsPacket(capped))
    }

    fun sendRocketExplosionSound(player: ServerPlayer, position: Vec3, volume: Float, pitch: Float) {
        SkyridersNetworkStats.record("S2C.RocketExplosionSound", 32)
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, RocketExplosionSoundPacket(position, volume, pitch))
    }

    data class VehicleInputPacket(val input: VehicleInput) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeDouble(input.steer)
            buf.writeDouble(input.throttle)
            buf.writeDouble(input.brake)
            buf.writeDouble(input.jump)
            buf.writeDouble(input.pitch)
            buf.writeDouble(input.handbrake)
            buf.writeDouble(input.clutch)
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            SkyridersNetworkStats.record("C2S.VehicleInput", 56)
            val context = contextSupplier.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                val seat = player.vehicle as? BikeSeatEntity ?: return@enqueueWork
                if (!seat.isDriverSeat()) return@enqueueWork
                VehicleImpairmentEffects.handleDriverInput(player, seat, input)
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: VehicleInputPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): VehicleInputPacket {
                return VehicleInputPacket(
                    VehicleInput(
                        steer = buf.readDouble(),
                        throttle = buf.readDouble(),
                        brake = buf.readDouble(),
                        jump = buf.readDouble(),
                        pitch = buf.readDouble(),
                        handbrake = buf.readDouble(),
                        clutch = buf.readDouble()
                    ).clamped()
                )
            }

            fun handle(packet: VehicleInputPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    class BikeDismountPacket {
        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            SkyridersNetworkStats.record("C2S.BikeDismount", 0)
            val context = contextSupplier.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                val seat = player.vehicle as? BikeSeatEntity
                if (seat != null) {
                    player.stopRiding()
                    seat.removeIfEmpty()
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: BikeDismountPacket, buf: FriendlyByteBuf) {
            }

            fun decode(buf: FriendlyByteBuf): BikeDismountPacket {
                return BikeDismountPacket()
            }

            fun handle(packet: BikeDismountPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    class BikeEngineTogglePacket {
        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            SkyridersNetworkStats.record("C2S.BikeEngineToggle", 0)
            val context = contextSupplier.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                val seat = player.vehicle as? BikeSeatEntity ?: return@enqueueWork
                if (!seat.isDriverSeat()) return@enqueueWork
                VehicleManager.toggleEngine(player.level() as net.minecraft.server.level.ServerLevel, seat.bodyId)
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: BikeEngineTogglePacket, buf: FriendlyByteBuf) {
            }

            fun decode(buf: FriendlyByteBuf): BikeEngineTogglePacket {
                return BikeEngineTogglePacket()
            }

            fun handle(packet: BikeEngineTogglePacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class VehicleControlActionPacket(val action: ResourceLocation) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeResourceLocation(action)
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            SkyridersNetworkStats.record("C2S.VehicleControlAction", estimateResourceLocationBytes(action))
            val context = contextSupplier.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                val seat = player.vehicle as? BikeSeatEntity ?: return@enqueueWork
                if (!seat.isDriverSeat()) return@enqueueWork
                VehicleManager.applyControlAction(player.level() as net.minecraft.server.level.ServerLevel, seat.bodyId, action)
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: VehicleControlActionPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): VehicleControlActionPacket {
                return VehicleControlActionPacket(buf.readResourceLocation())
            }

            fun handle(packet: VehicleControlActionPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class BikeUsePacket(val shiftDown: Boolean = false) {
        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            SkyridersNetworkStats.record("C2S.BikeUse", 1)
            val context = contextSupplier.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                BikeInteractionHandler.handleUse(player, shiftDown)
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: BikeUsePacket, buf: FriendlyByteBuf) {
                buf.writeBoolean(packet.shiftDown)
            }

            fun decode(buf: FriendlyByteBuf): BikeUsePacket {
                return BikeUsePacket(buf.readBoolean())
            }

            fun handle(packet: BikeUsePacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    class VehicleMeleeAttackPacket {
        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            SkyridersNetworkStats.record("C2S.VehicleMeleeAttack", 0)
            val context = contextSupplier.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                VehicleDamageEvents.handleClientMeleeAttack(player)
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: VehicleMeleeAttackPacket, buf: FriendlyByteBuf) {
            }

            fun decode(buf: FriendlyByteBuf): VehicleMeleeAttackPacket {
                return VehicleMeleeAttackPacket()
            }

            fun handle(packet: VehicleMeleeAttackPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class BikeHoistStatePacket(val hoisting: Boolean, val bodyId: Long) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeBoolean(hoisting)
            buf.writeLong(bodyId)
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable {
                        BikeClientHoistState.hoisting = hoisting
                        BikeClientHoistState.bodyId = if (hoisting) bodyId else -1L
                    }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: BikeHoistStatePacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): BikeHoistStatePacket {
                return BikeHoistStatePacket(buf.readBoolean(), buf.readLong())
            }

            fun handle(packet: BikeHoistStatePacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class BikeSyncPacket(val records: List<BikeSaveRecord>) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeVarInt(records.size)
            records.forEach { record ->
                buf.writeLong(record.bodyId)
                buf.writeUtf(record.bikeType)
                buf.writeBoolean(record.engineOn)
                buf.writeDouble(record.visualLeanRad)
                buf.writeDouble(record.frontWheelSpin)
                buf.writeDouble(record.rearWheelSpin)
                buf.writeDouble(record.frontWheelAngularVelocity)
                buf.writeDouble(record.rearWheelAngularVelocity)
            }
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable { ClientBikeSyncHandler.handleBikeSync(records) }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: BikeSyncPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): BikeSyncPacket {
                val count = buf.readVarInt()
                val records = (0 until count).map {
                    BikeSaveRecord(
                        bodyId = buf.readLong(),
                        bikeType = buf.readUtf(),
                        engineOn = buf.readBoolean(),
                        visualLeanRad = buf.readDouble(),
                        frontWheelSpin = buf.readDouble(),
                        rearWheelSpin = buf.readDouble(),
                        frontWheelAngularVelocity = buf.readDouble(),
                        rearWheelAngularVelocity = buf.readDouble()
                    )
                }
                return BikeSyncPacket(records)
            }

            fun handle(packet: BikeSyncPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class VehicleSyncPacket(val records: List<VehicleSaveRecord>) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeVarInt(records.size)
            records.forEach { record ->
                buf.writeNbt(record.save())
            }
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable { ClientBikeSyncHandler.handleVehicleSync(records) }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: VehicleSyncPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): VehicleSyncPacket {
                val count = buf.readVarInt()
                val records = (0 until count).map {
                    VehicleSaveRecord.load(buf.readNbt() ?: CompoundTag())
                }
                return VehicleSyncPacket(records)
            }

            fun handle(packet: VehicleSyncPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class BikeDebugPacket(
        val bodyId: Long,
        val bikeId: String,
        val bikeName: String,
        val speed: Double,
        val frontGrounded: Boolean,
        val rearGrounded: Boolean,
        val throttle: Double,
        val steeringAngleRad: Double,
        val drifting: Boolean,
        val engineOn: Boolean,
        val driftBoostCharge: Double,
        val driftBoostLevel: Int,
        val jumpCharge: Double
    ) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeLong(bodyId)
            buf.writeUtf(bikeId)
            buf.writeUtf(bikeName)
            buf.writeDouble(speed)
            buf.writeBoolean(frontGrounded)
            buf.writeBoolean(rearGrounded)
            buf.writeDouble(throttle)
            buf.writeDouble(steeringAngleRad)
            buf.writeBoolean(drifting)
            buf.writeBoolean(engineOn)
            buf.writeDouble(driftBoostCharge)
            buf.writeInt(driftBoostLevel)
            buf.writeDouble(jumpCharge)
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable {
                        BikeDebugOverlay.update(this)
                        BikeClientEffects.updateTelemetry(this)
                    }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: BikeDebugPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): BikeDebugPacket {
                return BikeDebugPacket(
                    bodyId = buf.readLong(),
                    bikeId = buf.readUtf(),
                    bikeName = buf.readUtf(),
                    speed = buf.readDouble(),
                    frontGrounded = buf.readBoolean(),
                    rearGrounded = buf.readBoolean(),
                    throttle = buf.readDouble(),
                    steeringAngleRad = buf.readDouble(),
                    drifting = buf.readBoolean(),
                    engineOn = buf.readBoolean(),
                    driftBoostCharge = buf.readDouble(),
                    driftBoostLevel = buf.readInt(),
                    jumpCharge = buf.readDouble()
                )
            }

            fun handle(packet: BikeDebugPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class VehicleTelemetryPacket(
        val bodyId: Long,
        val maxSpeed: Double,
        val speed: Double,
        val engineOn: Boolean,
        val fuel: Double,
        val throttle: Double,
        val steer: Double,
        val groundedCount: Int,
        val hasJump: Boolean,
        val jumpCharge: Double,
        val drifting: Boolean,
        val driftBoostCharge: Double,
        val driftBoostLevel: Int,
        val forwardSpeed: Double,
        val driftBoostTimeRemaining: Double,
        val hasTransmission: Boolean,
        val transmissionGear: Int,
        val parkingBrakeEngaged: Boolean,
        val engineRpm: Double,
        val engineStalled: Boolean
    ) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeLong(bodyId)
            buf.writeDouble(maxSpeed)
            buf.writeDouble(speed)
            buf.writeBoolean(engineOn)
            buf.writeDouble(fuel)
            buf.writeDouble(throttle)
            buf.writeDouble(steer)
            buf.writeInt(groundedCount)
            buf.writeBoolean(hasJump)
            buf.writeDouble(jumpCharge)
            buf.writeBoolean(drifting)
            buf.writeDouble(driftBoostCharge)
            buf.writeInt(driftBoostLevel)
            buf.writeDouble(forwardSpeed)
            buf.writeDouble(driftBoostTimeRemaining)
            buf.writeBoolean(hasTransmission)
            buf.writeInt(transmissionGear)
            buf.writeBoolean(parkingBrakeEngaged)
            buf.writeDouble(engineRpm)
            buf.writeBoolean(engineStalled)
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable {
                        VehicleHudOverlay.updateVehicle(this)
                        BikeClientEffects.updateVehicleTelemetry(this)
                    }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: VehicleTelemetryPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): VehicleTelemetryPacket {
                return VehicleTelemetryPacket(
                    bodyId = buf.readLong(),
                    maxSpeed = buf.readDouble(),
                    speed = buf.readDouble(),
                    engineOn = buf.readBoolean(),
                    fuel = buf.readDouble(),
                    throttle = buf.readDouble(),
                    steer = buf.readDouble(),
                    groundedCount = buf.readInt(),
                    hasJump = buf.readBoolean(),
                    jumpCharge = buf.readDouble(),
                    drifting = buf.readBoolean(),
                    driftBoostCharge = buf.readDouble(),
                    driftBoostLevel = buf.readInt(),
                    forwardSpeed = buf.readDouble(),
                    driftBoostTimeRemaining = buf.readDouble(),
                    hasTransmission = buf.readBoolean(),
                    transmissionGear = buf.readInt(),
                    parkingBrakeEngaged = buf.readBoolean(),
                    engineRpm = buf.readDouble(),
                    engineStalled = buf.readBoolean()
                )
            }

            fun handle(packet: VehicleTelemetryPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class VehicleDebugPacket(
        val bodyId: Long,
        val vehicleId: String,
        val vehicleName: String,
        val speed: Double,
        val engineOn: Boolean,
        val throttle: Double,
        val steer: Double,
        val groundedCount: Int,
        val drifting: Boolean,
        val driftBoostCharge: Double,
        val driftBoostLevel: Int,
        val lateralSlip: Double,
        val forwardSpeed: Double,
        val steerAngleRad: Double,
        val driftBoostTimeRemaining: Double,
        val hasTransmission: Boolean,
        val transmissionGear: Int,
        val parkingBrakeEngaged: Boolean,
        val engineRpm: Double,
        val clutchEngagement: Double,
        val engineStalled: Boolean
    ) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeLong(bodyId)
            buf.writeUtf(vehicleId)
            buf.writeUtf(vehicleName)
            buf.writeDouble(speed)
            buf.writeBoolean(engineOn)
            buf.writeDouble(throttle)
            buf.writeDouble(steer)
            buf.writeInt(groundedCount)
            buf.writeBoolean(drifting)
            buf.writeDouble(driftBoostCharge)
            buf.writeInt(driftBoostLevel)
            buf.writeDouble(lateralSlip)
            buf.writeDouble(forwardSpeed)
            buf.writeDouble(steerAngleRad)
            buf.writeDouble(driftBoostTimeRemaining)
            buf.writeBoolean(hasTransmission)
            buf.writeInt(transmissionGear)
            buf.writeBoolean(parkingBrakeEngaged)
            buf.writeDouble(engineRpm)
            buf.writeDouble(clutchEngagement)
            buf.writeBoolean(engineStalled)
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable { BikeDebugOverlay.updateVehicle(this) }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: VehicleDebugPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): VehicleDebugPacket {
                return VehicleDebugPacket(
                    bodyId = buf.readLong(),
                    vehicleId = buf.readUtf(),
                    vehicleName = buf.readUtf(),
                    speed = buf.readDouble(),
                    engineOn = buf.readBoolean(),
                    throttle = buf.readDouble(),
                    steer = buf.readDouble(),
                    groundedCount = buf.readInt(),
                    drifting = buf.readBoolean(),
                    driftBoostCharge = buf.readDouble(),
                    driftBoostLevel = buf.readInt(),
                    lateralSlip = buf.readDouble(),
                    forwardSpeed = buf.readDouble(),
                    steerAngleRad = buf.readDouble(),
                    driftBoostTimeRemaining = buf.readDouble(),
                    hasTransmission = buf.readBoolean(),
                    transmissionGear = buf.readInt(),
                    parkingBrakeEngaged = buf.readBoolean(),
                    engineRpm = buf.readDouble(),
                    clutchEngagement = buf.readDouble(),
                    engineStalled = buf.readBoolean()
                )
            }

            fun handle(packet: VehicleDebugPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class BikeVisualStatePacket(val states: List<BikeVisualState>) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeVarInt(states.size)
            states.forEach { state ->
                buf.writeLong(state.bodyId)
                buf.writeBoolean(state.engineOn)
                buf.writeDouble(state.visualLeanRad)
                buf.writeDouble(state.visualSteerRad)
                buf.writeDouble(state.frontWheelSpin)
                buf.writeDouble(state.rearWheelSpin)
                buf.writeDouble(state.frontWheelAngularVelocity)
                buf.writeDouble(state.rearWheelAngularVelocity)
                buf.writeDouble(state.frontWheelSuspensionOffset)
                buf.writeDouble(state.rearWheelSuspensionOffset)
            }
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable {
                        val level = net.minecraft.client.Minecraft.getInstance().level ?: return@Runnable
                        states.forEach { state ->
                            BikeManager.applyVisualState(
                                level = level,
                                bodyId = state.bodyId,
                                engineOn = state.engineOn,
                                visualLeanRad = state.visualLeanRad,
                                visualSteerRad = state.visualSteerRad,
                                frontWheelSpin = state.frontWheelSpin,
                                rearWheelSpin = state.rearWheelSpin,
                                frontWheelAngularVelocity = state.frontWheelAngularVelocity,
                                rearWheelAngularVelocity = state.rearWheelAngularVelocity,
                                frontWheelSuspensionOffset = state.frontWheelSuspensionOffset,
                                rearWheelSuspensionOffset = state.rearWheelSuspensionOffset
                            )
                        }
                    }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: BikeVisualStatePacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): BikeVisualStatePacket {
                val count = buf.readVarInt()
                val states = (0 until count).map {
                    BikeVisualState(
                        bodyId = buf.readLong(),
                        engineOn = buf.readBoolean(),
                        visualLeanRad = buf.readDouble(),
                        visualSteerRad = buf.readDouble(),
                        frontWheelSpin = buf.readDouble(),
                        rearWheelSpin = buf.readDouble(),
                        frontWheelAngularVelocity = buf.readDouble(),
                        rearWheelAngularVelocity = buf.readDouble(),
                        frontWheelSuspensionOffset = buf.readDouble(),
                        rearWheelSuspensionOffset = buf.readDouble()
                    )
                }
                return BikeVisualStatePacket(states)
            }

            fun handle(packet: BikeVisualStatePacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class BikeVisualState(
        val bodyId: Long,
        val engineOn: Boolean,
        val visualLeanRad: Double,
        val visualSteerRad: Double,
        val frontWheelSpin: Double,
        val rearWheelSpin: Double,
        val frontWheelAngularVelocity: Double,
        val rearWheelAngularVelocity: Double,
        val frontWheelSuspensionOffset: Double,
        val rearWheelSuspensionOffset: Double
    )

    data class VehicleVisualStatePacket(val states: List<VehicleVisualState>) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeVarInt(states.size)
            states.forEach { state ->
                buf.writeLong(state.bodyId)
                val flags =
                    (if (state.engineOn) VEHICLE_VISUAL_ENGINE_ON_FLAG else 0) or
                        (if (state.visualLeanRad.isFinite()) VEHICLE_VISUAL_LEAN_FLAG else 0) or
                        (if (state.moondropActive) VEHICLE_VISUAL_MOONDROP_FLAG else 0)
                buf.writeByte(flags)
                if (state.visualLeanRad.isFinite()) {
                    buf.writeDouble(state.visualLeanRad)
                }
                buf.writeDouble(state.frontSteerRad)
                buf.writeDouble(state.frontWheelSpin)
                buf.writeDouble(state.rearWheelSpin)
                buf.writeDouble(state.frontWheelAngularVelocity)
                buf.writeDouble(state.rearWheelAngularVelocity)
                buf.writeDouble(state.frontWheelSuspensionOffset)
                buf.writeDouble(state.rearWheelSuspensionOffset)
            }
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable {
                        val level = net.minecraft.client.Minecraft.getInstance().level ?: return@Runnable
                        states.forEach { state ->
                            VehicleStatusEffects.setClientMoondropVisual(level.dimensionId, state.bodyId, state.moondropActive)
                            VehicleManager.applyVisualWheelState(
                                level = level,
                                bodyId = state.bodyId,
                                engineOn = state.engineOn,
                                visualLeanRad = state.visualLeanRad,
                                frontSteerRad = state.frontSteerRad,
                                frontWheelSpin = state.frontWheelSpin,
                                rearWheelSpin = state.rearWheelSpin,
                                frontWheelAngularVelocity = state.frontWheelAngularVelocity,
                                rearWheelAngularVelocity = state.rearWheelAngularVelocity,
                                frontWheelSuspensionOffset = state.frontWheelSuspensionOffset,
                                rearWheelSuspensionOffset = state.rearWheelSuspensionOffset
                            )
                        }
                    }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: VehicleVisualStatePacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): VehicleVisualStatePacket {
                val count = buf.readVarInt()
                val states = (0 until count).map {
                    val bodyId = buf.readLong()
                    val flags = buf.readByte().toInt() and 0xFF
                    val visualLeanRad = if ((flags and VEHICLE_VISUAL_LEAN_FLAG) != 0) {
                        buf.readDouble()
                    } else {
                        Double.NaN
                    }
                    VehicleVisualState(
                        bodyId = bodyId,
                        engineOn = (flags and VEHICLE_VISUAL_ENGINE_ON_FLAG) != 0,
                        visualLeanRad = visualLeanRad,
                        frontSteerRad = buf.readDouble(),
                        frontWheelSpin = buf.readDouble(),
                        rearWheelSpin = buf.readDouble(),
                        frontWheelAngularVelocity = buf.readDouble(),
                        rearWheelAngularVelocity = buf.readDouble(),
                        frontWheelSuspensionOffset = buf.readDouble(),
                        rearWheelSuspensionOffset = buf.readDouble(),
                        moondropActive = (flags and VEHICLE_VISUAL_MOONDROP_FLAG) != 0
                    )
                }
                return VehicleVisualStatePacket(states)
            }

            fun handle(packet: VehicleVisualStatePacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class VehicleVisualState(
        val bodyId: Long,
        val engineOn: Boolean = false,
        val visualLeanRad: Double = Double.NaN,
        val frontSteerRad: Double,
        val frontWheelSpin: Double,
        val rearWheelSpin: Double,
        val frontWheelAngularVelocity: Double,
        val rearWheelAngularVelocity: Double,
        val frontWheelSuspensionOffset: Double,
        val rearWheelSuspensionOffset: Double,
        val moondropActive: Boolean = false
    )

    data class RaceCompassTargetPacket(val active: Boolean, val target: Vec3) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeBoolean(active)
            buf.writeDouble(target.x)
            buf.writeDouble(target.y)
            buf.writeDouble(target.z)
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable { RaceCompassClientState.update(active, if (active) target else null) }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: RaceCompassTargetPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): RaceCompassTargetPacket {
                val active = buf.readBoolean()
                val target = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
                return RaceCompassTargetPacket(active, target)
            }

            fun handle(packet: RaceCompassTargetPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class MoondropMusicPacket(val durationTicks: Int) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeVarInt(durationTicks.coerceAtLeast(1))
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable { RaceMusicClientState.startMoondrop(durationTicks.coerceAtLeast(1)) }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: MoondropMusicPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): MoondropMusicPacket {
                return MoondropMusicPacket(buf.readVarInt())
            }

            fun handle(packet: MoondropMusicPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class RaceMusicPacket(val active: Boolean, val track: ResourceLocation) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeBoolean(active)
            if (active) {
                buf.writeResourceLocation(track)
            }
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable {
                        if (active) {
                            RaceMusicClientState.start(track)
                        } else {
                            RaceMusicClientState.stop()
                        }
                    }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: RaceMusicPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): RaceMusicPacket {
                val active = buf.readBoolean()
                val track = if (active) buf.readResourceLocation() else EMPTY_SOUND
                return RaceMusicPacket(active, track)
            }

            fun handle(packet: RaceMusicPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class RaceHudPacket(
        val active: Boolean,
        val bodyId: Long,
        val place: Int,
        val total: Int,
        val lap: Int,
        val totalLaps: Int,
        val lapElapsedTicks: Long
    ) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeBoolean(active)
            if (active) {
                buf.writeLong(bodyId)
                buf.writeInt(place)
                buf.writeInt(total)
                buf.writeInt(lap)
                buf.writeInt(totalLaps)
                buf.writeLong(lapElapsedTicks)
            }
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable {
                        RaceHudClientState.update(active, bodyId, place, total, lap, totalLaps, lapElapsedTicks)
                    }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: RaceHudPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): RaceHudPacket {
                val active = buf.readBoolean()
                return if (active) {
                    RaceHudPacket(active, buf.readLong(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readLong())
                } else {
                    RaceHudPacket(active, -1L, 0, 0, 0, 0, 0L)
                }
            }

            fun handle(packet: RaceHudPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class RaceResultEntry(
        val place: Int,
        val playerUuid: UUID,
        val playerName: String,
        val vehicleType: String,
        val elapsedTicks: Long
    )

    data class RaceResultsPacket(val results: List<RaceResultEntry>) {
        fun encode(buf: FriendlyByteBuf) {
            val capped = results.take(MAX_RACE_RESULT_ENTRIES)
            buf.writeVarInt(capped.size)
            capped.forEach { result ->
                buf.writeVarInt(result.place.coerceAtLeast(1))
                buf.writeUUID(result.playerUuid)
                buf.writeUtf(result.playerName, MAX_RACE_RESULT_TEXT_LENGTH)
                buf.writeUtf(result.vehicleType, MAX_RACE_RESULT_TEXT_LENGTH)
                buf.writeLong(result.elapsedTicks.coerceAtLeast(0L))
            }
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable {
                        RaceResultsClientState.show(results)
                    }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: RaceResultsPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): RaceResultsPacket {
                val count = buf.readVarInt().coerceIn(0, MAX_RACE_RESULT_ENTRIES)
                val results = ArrayList<RaceResultEntry>(count)
                repeat(count) {
                    results += RaceResultEntry(
                        place = buf.readVarInt().coerceAtLeast(1),
                        playerUuid = buf.readUUID(),
                        playerName = buf.readUtf(MAX_RACE_RESULT_TEXT_LENGTH),
                        vehicleType = buf.readUtf(MAX_RACE_RESULT_TEXT_LENGTH),
                        elapsedTicks = buf.readLong().coerceAtLeast(0L)
                    )
                }
                return RaceResultsPacket(results)
            }

            fun handle(packet: RaceResultsPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    data class RocketExplosionSoundPacket(val position: Vec3, val volume: Float, val pitch: Float) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeDouble(position.x)
            buf.writeDouble(position.y)
            buf.writeDouble(position.z)
            buf.writeFloat(volume)
            buf.writeFloat(pitch)
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable { RacingClientSounds.playRocketExplosion(position, volume, pitch) }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: RocketExplosionSoundPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): RocketExplosionSoundPacket {
                val position = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
                val volume = buf.readFloat()
                val pitch = buf.readFloat()
                return RocketExplosionSoundPacket(position, volume, pitch)
            }

            fun handle(packet: RocketExplosionSoundPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }

    private fun estimateResourceLocationBytes(id: ResourceLocation): Int {
        return 1 + id.namespace.length + id.path.length
    }

    private fun estimateUtfBytes(value: String): Int {
        return 1 + value.toByteArray(Charsets.UTF_8).size
    }

    private fun estimateBikeSyncBytes(records: List<BikeSaveRecord>): Int {
        return 1 + records.sumOf { record ->
            8 + 1 + record.bikeType.length + 1 + 5 * 8
        }
    }

    private fun estimateVehicleSyncBytes(records: List<VehicleSaveRecord>): Int {
        return 1 + records.sumOf { record ->
            48 +
                record.vehicleType.length +
                record.behaviorTag.allKeys.size * 24 +
                record.partStates.entries.sumOf { (id, state) -> id.length + state.data.allKeys.size * 24 }
        }
    }

    private fun estimateBikeDebugBytes(bikeId: String, bikeName: String): Int {
        return 8 + bikeId.length + bikeName.length + 5 * 8 + 4 + 4
    }

    private fun estimateVehicleTelemetryBytes(packet: VehicleTelemetryPacket): Int {
        return 8 +
            10 * 8 +
            3 * 4 +
            6
    }

    private fun estimateVehicleDebugBytes(packet: VehicleDebugPacket): Int {
        return 8 +
            packet.vehicleId.length +
            packet.vehicleName.length +
            10 * 8 +
            3 * 4 +
            5
    }

    private fun estimateBikeVisualStateBytes(bikes: List<IBike>): Int {
        return 1 + bikes.size * (8 + 1 + 8 * 8)
    }

    private fun estimateVehicleVisualStateBytes(states: List<VehicleVisualState>): Int {
        return 1 + states.sumOf { state ->
            8 +
                1 +
                (if (state.visualLeanRad.isFinite()) 8 else 0) +
                7 * 8
        }
    }

    private val EMPTY_SOUND = ResourceLocation(SkyridersMod.MOD_ID, "empty")
}
