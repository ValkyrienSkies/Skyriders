package org.valkyrienskies.skyriders.network

import net.minecraft.network.FriendlyByteBuf
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
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
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
            BikeInputPacket::class.java,
            BikeInputPacket::encode,
            BikeInputPacket::decode,
            BikeInputPacket::handle
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
            BikeSyncPacket::class.java,
            BikeSyncPacket::encode,
            BikeSyncPacket::decode,
            BikeSyncPacket::handle
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
        CHANNEL.sendToServer(BikeInputPacket(input))
    }

    fun sendBikeDismount() {
        CHANNEL.sendToServer(BikeDismountPacket())
    }

    fun sendBikeUse() {
        CHANNEL.sendToServer(BikeUsePacket())
    }

    fun sendBikeSync(player: ServerPlayer, records: List<BikeSaveRecord>) {
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, BikeSyncPacket(records))
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
                        visualLeanRad = state.visualLeanRad,
                        visualSteerRad = state.visualSteerRad,
                        frontWheelSpin = state.frontWheelSpin,
                        rearWheelSpin = state.rearWheelSpin
                    )
                }
            )
        )
    }

    fun sendBikeHoistState(player: ServerPlayer, hoisting: Boolean) {
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, BikeHoistStatePacket(hoisting))
    }

    data class BikeInputPacket(val input: BikeInput) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeDouble(input.steer)
            buf.writeDouble(input.throttle)
            buf.writeDouble(input.brake)
            buf.writeDouble(input.jump)
            buf.writeDouble(input.pitch)
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                val seat = player.vehicle as? BikeSeatEntity ?: return@enqueueWork
                BikeManager.updateInput(player.level().dimensionId, seat.bodyId) { input.copy(riderPresent = true) }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: BikeInputPacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): BikeInputPacket {
                return BikeInputPacket(
                    BikeInput(
                        steer = buf.readDouble(),
                        throttle = buf.readDouble(),
                        brake = buf.readDouble(),
                        jump = buf.readDouble(),
                        pitch = buf.readDouble()
                    ).clamped()
                )
            }

            fun handle(packet: BikeInputPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
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

    data class BikeHoistStatePacket(val hoisting: Boolean) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeBoolean(hoisting)
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT) {
                    Runnable { BikeClientHoistState.hoisting = hoisting }
                }
            }
            context.packetHandled = true
        }

        companion object {
            fun encode(packet: BikeHoistStatePacket, buf: FriendlyByteBuf) {
                packet.encode(buf)
            }

            fun decode(buf: FriendlyByteBuf): BikeHoistStatePacket {
                return BikeHoistStatePacket(buf.readBoolean())
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
        val visualLeanRad: Double,
        val visualSteerRad: Double,
        val frontWheelSpin: Double,
        val rearWheelSpin: Double
    )
}
