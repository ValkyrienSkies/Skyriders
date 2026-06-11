package org.valkyrienskies.skyriders.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.item.ItemProperties
import net.minecraft.client.renderer.entity.EntityRenderers
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent
import net.minecraftforge.client.event.ModelEvent
import net.minecraftforge.client.event.RegisterColorHandlersEvent
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import org.lwjgl.glfw.GLFW
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.VehicleInteractionHandler
import org.valkyrienskies.skyriders.content.VehicleControlActions
import org.valkyrienskies.skyriders.content.VehicleInput
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.content.item.RaceFlagItem
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import net.minecraftforge.common.MinecraftForge
import org.valkyrienskies.skyriders.content.VehicleDefinitions

object SkyridersModClient {
    private var lastSentInput = VehicleInput.EMPTY
    private val bikeDismountKey = KeyMapping(
        "key.skyriders.bike_dismount",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_X,
        "key.categories.skyriders"
    )
    private val bikeBrakeKey = KeyMapping(
        "key.skyriders.bike_brake",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_LEFT_ALT,
        "key.categories.skyriders"
    )
    private val bikeEngineToggleKey = KeyMapping(
        "key.skyriders.bike_engine_toggle",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_R,
        "key.categories.skyriders"
    )
    private val vehicleParkingBrakeToggleKey = KeyMapping(
        "key.skyriders.vehicle_parking_brake_toggle",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_P,
        "key.categories.skyriders"
    )
    private val vehicleGearUpKey = KeyMapping(
        "key.skyriders.vehicle_gear_up",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_PAGE_UP,
        "key.categories.skyriders"
    )
    private val vehicleGearDownKey = KeyMapping(
        "key.skyriders.vehicle_gear_down",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_PAGE_DOWN,
        "key.categories.skyriders"
    )
    private val vehicleClutchKey = KeyMapping(
        "key.skyriders.vehicle_clutch",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_C,
        "key.categories.skyriders"
    )

    @JvmStatic
    fun clientInit(event: FMLClientSetupEvent) {
        event.enqueueWork {
            EntityRenderers.register(SkyridersMod.BIKE_SEAT_ENTITY.get(), ::BikeSeatRenderer)
            EntityRenderers.register(SkyridersMod.BAD_EXPLOSION_ENTITY.get(), ::BadExplosionRenderer)
            EntityRenderers.register(SkyridersMod.CAVENDISH_ENTITY.get()) { context ->
                RacingOpenModelEntityRenderer.cavendish(context)
            }
            EntityRenderers.register(SkyridersMod.ITEM_BOX_ENTITY.get(), ::ItemBoxRenderer)
            EntityRenderers.register(SkyridersMod.FAKE_ITEM_BOX_ENTITY.get(), ::FakeItemBoxRenderer)
            EntityRenderers.register(SkyridersMod.SUGAR_ROCKET_ENTITY.get()) { context ->
                RacingOpenModelEntityRenderer.sugarRocket(context)
            }
            EntityRenderers.register(SkyridersMod.GLASSO_ENTITY.get(), ::GlassoRenderer)
            EntityRenderers.register(SkyridersMod.HONEY_HEISTER_ENTITY.get()) { context ->
                RacingOpenModelEntityRenderer.honeyHeister(context)
            }
            EntityRenderers.register(SkyridersMod.EXTENDING_ARM_ENTITY.get(), ::ExtendingArmRenderer)
            ItemProperties.register(SkyridersMod.RACE_COMPASS.get(), ResourceLocation("angle")) { _, _, entity, seed ->
                RaceCompassClientState.angle(entity, seed)
            }
            MinecraftForge.EVENT_BUS.register(ClientEvents)
            MinecraftForge.EVENT_BUS.register(BikeDebugOverlay)
            MinecraftForge.EVENT_BUS.register(VehicleWorldRenderer)
        }
    }

    @JvmStatic
    fun registerKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(bikeDismountKey)
        event.register(bikeBrakeKey)
        event.register(bikeEngineToggleKey)
        event.register(vehicleParkingBrakeToggleKey)
        event.register(vehicleGearUpKey)
        event.register(vehicleGearDownKey)
        event.register(vehicleClutchKey)
    }

    @JvmStatic
    fun registerItemColors(event: RegisterColorHandlersEvent.Item) {
        event.register(
            { stack, tintIndex ->
                if (tintIndex == RaceFlagItem.FLAG_CLOTH_TINT_INDEX) RaceFlagItem.getColorRgb(stack) else -1
            },
            SkyridersMod.RACE_FLAG.get()
        )
    }

    @JvmStatic
    fun registerAdditionalModels(event: ModelEvent.RegisterAdditional) {
        VehicleDefinitions.ids
            .mapNotNull(VehicleDefinitions::get)
            .flatMap { definition ->
                listOfNotNull(
                    definition.render.model
                ) + definition.render.resolvedWheelParts().map { it.model }
            }
            .distinct()
            .forEach(event::register)
        event.register(RacingOpenModelEntityRenderer.CAVENDISH_MODEL)
        event.register(RacingOpenModelEntityRenderer.SUGAR_ROCKET_MODEL)
        event.register(RacingOpenModelEntityRenderer.HOMING_SUGAR_ROCKET_MODEL)
        event.register(RacingOpenModelEntityRenderer.HONEY_HEISTER_MODEL)
    }

    @JvmStatic
    fun registerGuiOverlays(event: RegisterGuiOverlaysEvent) {
        event.registerAboveAll("vehicle_hud") { _, guiGraphics, _, screenWidth, screenHeight ->
            VehicleHudOverlay.render(guiGraphics, screenWidth, screenHeight)
        }
    }

    object ClientEvents {
        @SubscribeEvent
        fun onClientTick(event: TickEvent.ClientTickEvent) {
            if (event.phase != TickEvent.Phase.END) return

            val minecraft = Minecraft.getInstance()
            ClientVehicleSyncHandler.tick()
            VehicleClientEffects.tick()
            RaceCompassClientState.tick()
            RaceHudClientState.tick()
            RaceMusicClientState.tick()
            val player = minecraft.player ?: return
            if (player.vehicle !is BikeSeatEntity) return

            while (bikeDismountKey.consumeClick()) {
                SkyridersNetwork.sendBikeDismount()
            }
            while (bikeEngineToggleKey.consumeClick()) {
                SkyridersNetwork.sendBikeEngineToggle()
            }
            while (vehicleParkingBrakeToggleKey.consumeClick()) {
                SkyridersNetwork.sendVehicleControlAction(VehicleControlActions.TOGGLE_PARKING_BRAKE)
            }
            while (vehicleGearUpKey.consumeClick()) {
                SkyridersNetwork.sendVehicleControlAction(VehicleControlActions.GEAR_UP)
            }
            while (vehicleGearDownKey.consumeClick()) {
                SkyridersNetwork.sendVehicleControlAction(VehicleControlActions.GEAR_DOWN)
            }

            val options = minecraft.options
            val forward = options.keyUp.isDown
            val backward = options.keyDown.isDown
            val left = options.keyLeft.isDown
            val right = options.keyRight.isDown
            val leanForward = options.keyShift.isDown
            val leanBack = options.keySprint.isDown
            val input = VehicleInput(
                steer = if (left == right) 0.0 else if (left) 1.0 else -1.0,
                throttle = if (forward == backward) 0.0 else if (forward) 1.0 else -1.0,
                brake = if (bikeBrakeKey.isDown) 1.0 else 0.0,
                jump = if (options.keyJump.isDown) 1.0 else 0.0,
                pitch = if (leanForward == leanBack) 0.0 else if (leanForward) -1.0 else 1.0,
                clutch = if (vehicleClutchKey.isDown) 1.0 else 0.0
            )
            player.isShiftKeyDown = false

            if (input != lastSentInput) {
                SkyridersNetwork.sendVehicleInput(input)
                lastSentInput = input
            }
        }

        @SubscribeEvent
        fun onInteractionKey(event: InputEvent.InteractionKeyMappingTriggered) {
            if (!event.isUseItem) return

            val minecraft = Minecraft.getInstance()
            val player = minecraft.player ?: return
            val level = minecraft.level ?: return
            if (player.vehicle is BikeSeatEntity) return

            val eye = player.getEyePosition(1.0f)
            val end = eye.add(player.lookAngle.scale(5.0))
            val hitBike = VehicleInteractionHandler.findVehicleOnRay(level, eye, end) != null
            if (!hitBike && !BikeClientHoistState.hoisting) return

            SkyridersNetwork.sendBikeUse()
            event.isCanceled = true
            event.setSwingHand(false)
        }

        @SubscribeEvent
        fun onLoggedOut(event: ClientPlayerNetworkEvent.LoggingOut) {
            lastSentInput = VehicleInput.EMPTY
            BikeClientHoistState.hoisting = false
            RaceHudClientState.clear()
            RaceMusicClientState.stop()
        }
    }
}
