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
import org.valkyrienskies.skyriders.client.ClientBikeSyncHandler
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.BikeSaveRecord
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
    }

    fun sendBikeInput(input: BikeInput) {
        CHANNEL.sendToServer(BikeInputPacket(input))
    }

    fun sendBikeDismount() {
        CHANNEL.sendToServer(BikeDismountPacket())
    }

    fun sendBikeSync(player: ServerPlayer, records: List<BikeSaveRecord>) {
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, BikeSyncPacket(records))
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
}
