package org.valkyrienskies.skyriders

import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.sounds.SoundEvent
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.skyriders.client.SkyridersModClient
import org.valkyrienskies.skyriders.command.SkyridersCommands
import org.valkyrienskies.skyriders.content.BikeLifecycle
import org.valkyrienskies.skyriders.content.VehicleInteractionHandler
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.block.BoostPadBlock
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import thedarkcolour.kotlinforforge.forge.MOD_BUS

@Mod("skyriders")
object SkyridersMod {

    const val MOD_ID = "skyriders"

    //Deferred Registries
    private val BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID)
    private val ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID)
    private val ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID)
    private val BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID)
    private val SOUND_EVENTS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MOD_ID)

    // Put RegistryObjects here:
    val BOOST_PAD_BLOCK: RegistryObject<Block> = registerBlockAndItem("boost_pad") {
        BoostPadBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_CYAN)
                .strength(0.8f)
                .sound(SoundType.METAL)
                .noOcclusion()
        )
    }
    val BOOST_PAD_FLOOR_BLOCK: RegistryObject<Block> = registerBlockAndItem("boost_pad_floor") {
        BoostPadBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_CYAN)
                .strength(1.2f)
                .sound(SoundType.METAL),
            fullBlock = true
        )
    }

    val BIKE_SEAT_ENTITY: RegistryObject<EntityType<BikeSeatEntity>> = ENTITIES.register("bike_seat") {
        EntityType.Builder.of(::BikeSeatEntity, MobCategory.MISC)
            .sized(0.05f, 0.05f)
            .clientTrackingRange(10)
            .updateInterval(1)
            .build("$MOD_ID:bike_seat")
    }
    val BIKE_ENGINE_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("bike_engine") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "bike_engine"))
    }
    val BIKE_ENGINE_START_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("bike_engine_start") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "bike_engine_start"))
    }
    val BIKE_ENGINE_STOP_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("bike_engine_stop") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "bike_engine_stop"))
    }
    val BOOST_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("boost") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "boost"))
    }

    // end of RegistryObjects

    init {
        val modEventBus = MOD_BUS

        BLOCKS.register(modEventBus)
        ITEMS.register(modEventBus)
        ENTITIES.register(modEventBus)
        BLOCK_ENTITIES.register(modEventBus)
        SOUND_EVENTS.register(modEventBus)

        modEventBus.addListener(::init)
        MinecraftForge.EVENT_BUS.register(BikeLifecycle)
        MinecraftForge.EVENT_BUS.register(VehicleInteractionHandler)
        MinecraftForge.EVENT_BUS.addListener { event: RegisterCommandsEvent -> SkyridersCommands.register(event) }
        if (FMLEnvironment.dist.isClient) {
            modEventBus.addListener(SkyridersModClient::clientInit)
            modEventBus.addListener(SkyridersModClient::registerKeyMappings)
            modEventBus.addListener(SkyridersModClient::registerAdditionalModels)
        }
    }

    @JvmStatic
    @OptIn(VsBeta::class)
    fun init (event: FMLCommonSetupEvent) {
        event.enqueueWork {
            SkyridersNetwork.register()
            vsApi.physTickEvent.on { physTickEvent ->
                VehicleManager.physTick(physTickEvent.world, physTickEvent.delta)
                VehicleInteractionHandler.physTick(physTickEvent.world, physTickEvent.delta)
            }
        }
    }

    // Helper function, taken from VS2.
    private fun registerBlockAndItem(registryName: String, blockSupplier: () -> Block): RegistryObject<Block> {
        val blockRegistry = BLOCKS.register(registryName, blockSupplier)
        ITEMS.register(registryName) { BlockItem(blockRegistry.get(), Item.Properties()) }
        return blockRegistry
    }
}
