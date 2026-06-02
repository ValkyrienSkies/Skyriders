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
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.client.BikeClientEffects
import org.valkyrienskies.skyriders.client.BikeClientHoistState
import org.valkyrienskies.skyriders.client.BikeDebugOverlay
import org.valkyrienskies.skyriders.client.ClientBikeSyncHandler
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.BikeInteractionHandler
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.BikeSaveRecord
import org.valkyrienskies.skyriders.content.IBike
import org.valkyrienskies.skyriders.content.VehicleInput
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleSaveRecord
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.content.toVehicleInput
import java.util.function.Supplier

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
            BikeVisualStatePacket::class.java,
            BikeVisualStatePacket::encode,
            BikeVisualStatePacket::decode,
            BikeVisualStatePacket::handle
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
                        rearWheelSpin = state.rearWheelSpin
                    )
                }
            )
        )
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
                        handbrake = buf.readDouble()
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
                        rearWheelSpin = buf.readDouble()
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
                buf.writeLong(record.bodyId)
                buf.writeUtf(record.vehicleType)
                buf.writeBoolean(record.engineOn)
                buf.writeNbt(record.behaviorTag)
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
                    VehicleSaveRecord(
                        bodyId = buf.readLong(),
                        vehicleType = buf.readUtf(),
                        engineOn = buf.readBoolean(),
                        behaviorTag = buf.readNbt() ?: CompoundTag()
                    )
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
                                rearWheelSpin = state.rearWheelSpin
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
                        rearWheelSpin = buf.readDouble()
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
        val rearWheelSpin: Double
    )
}
