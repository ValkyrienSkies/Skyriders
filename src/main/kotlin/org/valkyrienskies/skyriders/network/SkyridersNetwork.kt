package org.valkyrienskies.skyriders.network

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.simple.SimpleChannel
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.BikeManager
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
    }

    fun sendBikeInput(input: BikeInput) {
        CHANNEL.sendToServer(BikeInputPacket(input))
    }

    data class BikeInputPacket(val input: BikeInput) {
        fun encode(buf: FriendlyByteBuf) {
            buf.writeDouble(input.steer)
            buf.writeDouble(input.throttle)
            buf.writeDouble(input.brake)
            buf.writeDouble(input.jump)
        }

        fun handle(contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            context.enqueueWork {
                val player = context.sender ?: return@enqueueWork
                val seat = player.vehicle as? BikeSeatEntity ?: return@enqueueWork
                BikeManager.updateInput(player.level().dimensionId, seat.bodyId) { input }
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
                        jump = buf.readDouble()
                    ).clamped()
                )
            }

            fun handle(packet: BikeInputPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
                packet.handle(contextSupplier)
            }
        }
    }
}
