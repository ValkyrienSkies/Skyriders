package org.valkyrienskies.skyriders.client

import net.minecraft.client.renderer.entity.EntityRenderers
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import net.minecraftforge.common.MinecraftForge
import net.minecraft.client.Minecraft

object SkyridersModClient {
    private var lastSentInput = BikeInput.EMPTY

    @JvmStatic
    fun clientInit(event: FMLClientSetupEvent) {
        event.enqueueWork {
            EntityRenderers.register(SkyridersMod.BIKE_SEAT_ENTITY.get(), ::BikeSeatRenderer)
            MinecraftForge.EVENT_BUS.register(ClientEvents)
        }
    }

    object ClientEvents {
        @SubscribeEvent
        fun onClientTick(event: TickEvent.ClientTickEvent) {
            if (event.phase != TickEvent.Phase.END) return

            val minecraft = Minecraft.getInstance()
            val player = minecraft.player ?: return
            if (player.vehicle !is BikeSeatEntity) return

            val options = minecraft.options
            val forward = options.keyUp.isDown
            val backward = options.keyDown.isDown
            val left = options.keyLeft.isDown
            val right = options.keyRight.isDown
            val input = BikeInput(
                steer = if (left == right) 0.0 else if (left) 1.0 else -1.0,
                throttle = if (forward == backward) 0.0 else if (forward) 1.0 else -1.0,
                brake = if (backward) 1.0 else 0.0,
                jump = if (options.keyJump.isDown) 1.0 else 0.0
            )

            if (input != lastSentInput) {
                SkyridersNetwork.sendBikeInput(input)
                lastSentInput = input
            }
        }

        @SubscribeEvent
        fun onLoggedOut(event: ClientPlayerNetworkEvent.LoggingOut) {
            lastSentInput = BikeInput.EMPTY
        }
    }
}
