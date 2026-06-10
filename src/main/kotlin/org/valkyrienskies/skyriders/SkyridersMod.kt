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
import org.valkyrienskies.skyriders.content.SkyridersSounds
import org.valkyrienskies.skyriders.content.VehicleInteractionHandler
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.block.BoostPadBlock
import org.valkyrienskies.skyriders.content.entity.BadExplosionEntity
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.content.entity.CavendishEntity
import org.valkyrienskies.skyriders.content.entity.SugarRocketEntity
import org.valkyrienskies.skyriders.content.item.CavendishItem
import org.valkyrienskies.skyriders.content.item.CreativeJerryCanItem
import org.valkyrienskies.skyriders.content.item.HoneyCanisterItem
import org.valkyrienskies.skyriders.content.item.SugarRocketItem
import org.valkyrienskies.skyriders.content.item.ThunderboltItem
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
    val CREATIVE_JERRY_CAN: RegistryObject<Item> = ITEMS.register("creative_jerry_can") {
        CreativeJerryCanItem(Item.Properties().stacksTo(1))
    }
    val HONEY_CANISTER: RegistryObject<Item> = ITEMS.register("honey_canister") {
        HoneyCanisterItem(Item.Properties().stacksTo(16))
    }
    val THUNDERBOLT: RegistryObject<Item> = ITEMS.register("thunderbolt") {
        ThunderboltItem(Item.Properties().stacksTo(16))
    }
    val CAVENDISH: RegistryObject<Item> = ITEMS.register("cavendish") {
        CavendishItem(Item.Properties().stacksTo(16))
    }
    val SUGAR_ROCKET: RegistryObject<Item> = ITEMS.register("sugar_rocket") {
        SugarRocketItem(Item.Properties().stacksTo(16), homing = false)
    }
    val HOMING_SUGAR_ROCKET: RegistryObject<Item> = ITEMS.register("homing_sugar_rocket") {
        SugarRocketItem(Item.Properties().stacksTo(16), homing = true)
    }

    val BIKE_SEAT_ENTITY: RegistryObject<EntityType<BikeSeatEntity>> = ENTITIES.register("bike_seat") {
        EntityType.Builder.of(::BikeSeatEntity, MobCategory.MISC)
            .sized(0.05f, 0.05f)
            .clientTrackingRange(10)
            .updateInterval(1)
            .build("$MOD_ID:bike_seat")
    }
    val CAVENDISH_ENTITY: RegistryObject<EntityType<CavendishEntity>> = ENTITIES.register("cavendish") {
        EntityType.Builder.of(::CavendishEntity, MobCategory.MISC)
            .sized(0.35f, 0.18f)
            .clientTrackingRange(48)
            .updateInterval(2)
            .build("$MOD_ID:cavendish")
    }
    val SUGAR_ROCKET_ENTITY: RegistryObject<EntityType<SugarRocketEntity>> = ENTITIES.register("sugar_rocket") {
        EntityType.Builder.of(::SugarRocketEntity, MobCategory.MISC)
            .sized(0.35f, 0.35f)
            .clientTrackingRange(96)
            .updateInterval(1)
            .build("$MOD_ID:sugar_rocket")
    }
    val BAD_EXPLOSION_ENTITY: RegistryObject<EntityType<BadExplosionEntity>> = ENTITIES.register("bad_explosion") {
        EntityType.Builder.of(::BadExplosionEntity, MobCategory.MISC)
            .sized(0.1f, 0.1f)
            .clientTrackingRange(96)
            .updateInterval(1)
            .build("$MOD_ID:bad_explosion")
    }

    // end of RegistryObjects

    init {
        val modEventBus = MOD_BUS

        BLOCKS.register(modEventBus)
        ITEMS.register(modEventBus)
        ENTITIES.register(modEventBus)
        BLOCK_ENTITIES.register(modEventBus)
        SkyridersSounds.register(modEventBus)

        modEventBus.addListener(::init)
        MinecraftForge.EVENT_BUS.register(BikeLifecycle)
        MinecraftForge.EVENT_BUS.register(VehicleInteractionHandler)
        MinecraftForge.EVENT_BUS.addListener { event: RegisterCommandsEvent -> SkyridersCommands.register(event) }
        if (FMLEnvironment.dist.isClient) {
            modEventBus.addListener(SkyridersModClient::clientInit)
            modEventBus.addListener(SkyridersModClient::registerKeyMappings)
            modEventBus.addListener(SkyridersModClient::registerAdditionalModels)
            modEventBus.addListener(SkyridersModClient::registerGuiOverlays)
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
