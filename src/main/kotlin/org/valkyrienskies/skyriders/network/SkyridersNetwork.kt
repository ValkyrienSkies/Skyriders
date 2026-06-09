package org.valkyrienskies.skyriders.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
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
import org.valkyrienskies.skyriders.client.VehicleHudOverlay
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.BikeInteractionHandler
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.BikeSaveRecord
import org.valkyrienskies.skyriders.content.IBike
import org.valkyrienskies.skyriders.content.VehicleInput
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleSaveRecord
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.content.toVehicleInput
import java.util.function.Supplier
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle

object SkyridersNetwork {
    private const val PROTOCOL_VERSION = "1"
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
            BikeHoistStatePacket::class.java,
            BikeHoistStatePacket::encode,
            BikeHoistStatePacket::decode,
            BikeHoistStatePacket::handle
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

    fun sendBikeUse() {
        CHANNEL.sendToServer(BikeUsePacket())
    }

    fun sendBikeSync(player: ServerPlayer, records: List<BikeSaveRecord>) {
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, BikeSyncPacket(records))
    }

    fun sendVehicleSync(player: ServerPlayer, records: List<VehicleSaveRecord>) {
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, VehicleSyncPacket(records))
    }

    fun sendBikeDebug(player: ServerPlayer, bike: IBike) {
        val state = bike.state
        val bikeId = bike.id
        val bikeName = bike.definition.displayName
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

    fun sendVehicleDebug(player: ServerPlayer, vehicle: org.valkyrienskies.skyriders.content.IVehicle) {
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
            is IBike -> vehicle.state.debugDrifting
            else -> false
        }
        val driftBoostCharge = when (vehicle) {
            is KartVehicle -> vehicle.kartState.driftBoostCharge
            is IBike -> vehicle.state.driftBoostCharge
            else -> 0.0
        }
        val driftBoostLevel = when (vehicle) {
            is KartVehicle -> vehicle.kartState.driftBoostLevel
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
            is IBike -> vehicle.state.driftBoostTimeRemaining
            else -> 0.0
        }
        val frontWheelSpin = when (vehicle) {
            is KartVehicle -> vehicle.kartState.frontWheelSpin
            is WheeledVehicle -> vehicle.wheeledState.frontWheelSpin
            is IBike -> vehicle.state.frontWheelSpin
            else -> 0.0
        }
        val rearWheelSpin = when (vehicle) {
            is KartVehicle -> vehicle.kartState.rearWheelSpin
            is WheeledVehicle -> vehicle.wheeledState.rearWheelSpin
            is IBike -> vehicle.state.rearWheelSpin
            else -> 0.0
        }
        val frontWheelAngularVelocity = when (vehicle) {
            is KartVehicle -> vehicle.kartState.frontWheelAngularVelocity
            is WheeledVehicle -> vehicle.wheeledState.frontWheelAngularVelocity
            is IBike -> vehicle.state.frontWheelAngularVelocity
            else -> 0.0
        }
        val rearWheelAngularVelocity = when (vehicle) {
            is KartVehicle -> vehicle.kartState.rearWheelAngularVelocity
            is WheeledVehicle -> vehicle.wheeledState.rearWheelAngularVelocity
            is IBike -> vehicle.state.rearWheelAngularVelocity
            else -> 0.0
        }
        val frontWheelSuspensionOffset = when (vehicle) {
            is KartVehicle -> vehicle.kartState.frontWheelSuspensionOffset
            is WheeledVehicle -> vehicle.wheeledState.frontWheelSuspensionOffset
            is IBike -> vehicle.state.frontWheelSuspensionOffset
            else -> 0.0
        }
        val rearWheelSuspensionOffset = when (vehicle) {
            is KartVehicle -> vehicle.kartState.rearWheelSuspensionOffset
            is WheeledVehicle -> vehicle.wheeledState.rearWheelSuspensionOffset
            is IBike -> vehicle.state.rearWheelSuspensionOffset
            else -> 0.0
        }
        val transmissionGear = when (vehicle) {
            is WheeledVehicle -> vehicle.wheeledState.debugTransmissionGear
            else -> 0
        }
        val parkingBrakeEngaged = when (vehicle) {
            is WheeledVehicle -> vehicle.wheeledState.debugParkingBrake
            else -> false
        }
        val engineRpm = when (vehicle) {
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
        val hasTransmission = vehicle is WheeledVehicle
        CHANNEL.send(
            PacketDistributor.PLAYER.with { player },
            VehicleDebugPacket(
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
                frontWheelSpin = frontWheelSpin,
                rearWheelSpin = rearWheelSpin,
                frontWheelAngularVelocity = frontWheelAngularVelocity,
                rearWheelAngularVelocity = rearWheelAngularVelocity,
                frontWheelSuspensionOffset = frontWheelSuspensionOffset,
                rearWheelSuspensionOffset = rearWheelSuspensionOffset,
                hasTransmission = hasTransmission,
                transmissionGear = transmissionGear,
                parkingBrakeEngaged = parkingBrakeEngaged,
                engineRpm = engineRpm,
                clutchEngagement = clutchEngagement,
                engineStalled = engineStalled
            )
        )
    }

    fun sendBikeVisualState(player: ServerPlayer, bikes: List<IBike>) {
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
                is KartVehicle -> {
                    val state = vehicle.kartState
                    VehicleVisualState(
                        bodyId = vehicle.bodyId,
                        frontWheelSpin = state.frontWheelSpin,
                        rearWheelSpin = state.rearWheelSpin,
                        frontWheelAngularVelocity = state.frontWheelAngularVelocity,
                        rearWheelAngularVelocity = state.rearWheelAngularVelocity,
                        frontWheelSuspensionOffset = state.frontWheelSuspensionOffset,
                        rearWheelSuspensionOffset = state.rearWheelSuspensionOffset
                    )
                }
                is WheeledVehicle -> {
                    val state = vehicle.wheeledState
                    VehicleVisualState(
                        bodyId = vehicle.bodyId,
                        frontWheelSpin = state.frontWheelSpin,
                        rearWheelSpin = state.rearWheelSpin,
                        frontWheelAngularVelocity = state.frontWheelAngularVelocity,
                        rearWheelAngularVelocity = state.rearWheelAngularVelocity,
                        frontWheelSuspensionOffset = state.frontWheelSuspensionOffset,
                        rearWheelSuspensionOffset = state.rearWheelSuspensionOffset
                    )
                }
                else -> null
            }
        }
        if (states.isEmpty()) return
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, VehicleVisualStatePacket(states))
    }

    fun sendBikeHoistState(player: ServerPlayer, hoisting: Boolean, bodyId: Long = -1L) {
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, BikeHoistStatePacket(hoisting, bodyId))
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
            val context = contextSupplier.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                val seat = player.vehicle as? BikeSeatEntity ?: return@enqueueWork
                if (!seat.isDriverSeat()) return@enqueueWork
                VehicleManager.updateInput(player.level().dimensionId, seat.bodyId) { input.copy(riderPresent = true) }
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
            val context = contextSupplier.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                if (player.vehicle is BikeSeatEntity) {
                    player.stopRiding()
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

    class BikeUsePacket {
        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                BikeInteractionHandler.handleUse(player)
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: BikeUsePacket, buf: FriendlyByteBuf) {
            }

            fun decode(buf: FriendlyByteBuf): BikeUsePacket {
                return BikeUsePacket()
            }

            fun handle(packet: BikeUsePacket, contextSupplier: Supplier<NetworkEvent.Context>) {
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
        val frontWheelSpin: Double,
        val rearWheelSpin: Double,
        val frontWheelAngularVelocity: Double,
        val rearWheelAngularVelocity: Double,
        val frontWheelSuspensionOffset: Double,
        val rearWheelSuspensionOffset: Double,
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
            buf.writeDouble(frontWheelSpin)
            buf.writeDouble(rearWheelSpin)
            buf.writeDouble(frontWheelAngularVelocity)
            buf.writeDouble(rearWheelAngularVelocity)
            buf.writeDouble(frontWheelSuspensionOffset)
            buf.writeDouble(rearWheelSuspensionOffset)
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
                    Runnable {
                        BikeDebugOverlay.updateVehicle(this)
                        VehicleHudOverlay.updateVehicle(this)
                        BikeClientEffects.updateVehicleTelemetry(this)
                        val level = net.minecraft.client.Minecraft.getInstance().level ?: return@Runnable
                        VehicleManager.applyVisualWheelState(
                            level = level,
                            bodyId = bodyId,
                            frontWheelSpin = frontWheelSpin,
                            rearWheelSpin = rearWheelSpin,
                            frontWheelAngularVelocity = frontWheelAngularVelocity,
                            rearWheelAngularVelocity = rearWheelAngularVelocity,
                            frontWheelSuspensionOffset = frontWheelSuspensionOffset,
                            rearWheelSuspensionOffset = rearWheelSuspensionOffset
                        )
                    }
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
                    frontWheelSpin = buf.readDouble(),
                    rearWheelSpin = buf.readDouble(),
                    frontWheelAngularVelocity = buf.readDouble(),
                    rearWheelAngularVelocity = buf.readDouble(),
                    frontWheelSuspensionOffset = buf.readDouble(),
                    rearWheelSuspensionOffset = buf.readDouble(),
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
                            VehicleManager.applyVisualWheelState(
                                level = level,
                                bodyId = state.bodyId,
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
                    VehicleVisualState(
                        bodyId = buf.readLong(),
                        frontWheelSpin = buf.readDouble(),
                        rearWheelSpin = buf.readDouble(),
                        frontWheelAngularVelocity = buf.readDouble(),
                        rearWheelAngularVelocity = buf.readDouble(),
                        frontWheelSuspensionOffset = buf.readDouble(),
                        rearWheelSuspensionOffset = buf.readDouble()
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
        val frontWheelSpin: Double,
        val rearWheelSpin: Double,
        val frontWheelAngularVelocity: Double,
        val rearWheelAngularVelocity: Double,
        val frontWheelSuspensionOffset: Double,
        val rearWheelSuspensionOffset: Double
    )
}
