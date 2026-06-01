package org.valkyrienskies.skyriders.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.EntityRenderers
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import org.lwjgl.glfw.GLFW
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import net.minecraftforge.common.MinecraftForge

object SkyridersModClient {
    private var lastSentInput = BikeInput.EMPTY
    private val bikeDismountKey = KeyMapping(
        "key.skyriders.bike_dismount",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_X,
        "key.categories.skyriders"
    )

    @JvmStatic
    fun clientInit(event: FMLClientSetupEvent) {
        event.enqueueWork {
            EntityRenderers.register(SkyridersMod.BIKE_SEAT_ENTITY.get(), ::BikeSeatRenderer)
            MinecraftForge.EVENT_BUS.register(ClientEvents)
        }
    }

    @JvmStatic
    fun registerKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(bikeDismountKey)
    }

    object ClientEvents {
        @SubscribeEvent
        fun onClientTick(event: TickEvent.ClientTickEvent) {
            if (event.phase != TickEvent.Phase.END) return

            val minecraft = Minecraft.getInstance()
            val player = minecraft.player ?: return
            if (player.vehicle !is BikeSeatEntity) return

            while (bikeDismountKey.consumeClick()) {
                SkyridersNetwork.sendBikeDismount()
            }

            val options = minecraft.options
            val forward = options.keyUp.isDown
            val backward = options.keyDown.isDown
            val left = options.keyLeft.isDown
            val right = options.keyRight.isDown
            val leanForward = options.keyShift.isDown
            val leanBack = options.keySprint.isDown
            val input = BikeInput(
                steer = if (left == right) 0.0 else if (left) 1.0 else -1.0,
                throttle = if (forward == backward) 0.0 else if (forward) 1.0 else -1.0,
                brake = if (backward) 1.0 else 0.0,
                jump = if (options.keyJump.isDown) 1.0 else 0.0,
                pitch = if (leanForward == leanBack) 0.0 else if (leanForward) 1.0 else -1.0
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
