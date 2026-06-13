package org.valkyrienskies.skyriders

import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.animal.horse.Horse
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Rarity
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.entity.living.LivingDropsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.skyriders.client.SkyridersModClient
import org.valkyrienskies.skyriders.command.SkyridersCommands
import org.valkyrienskies.skyriders.content.BikeLifecycle
import org.valkyrienskies.skyriders.content.SkyridersDamageTypes
import org.valkyrienskies.skyriders.content.SkyridersSounds
import org.valkyrienskies.skyriders.content.VehicleImpairmentEffects
import org.valkyrienskies.skyriders.content.VehicleImpactDamageHandler
import org.valkyrienskies.skyriders.content.VehicleInteractionHandler
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleDamageEvents
import org.valkyrienskies.skyriders.content.block.BoostPadBlock
import org.valkyrienskies.skyriders.content.entity.BadExplosionEntity
import org.valkyrienskies.skyriders.content.entity.BikeSeatEntity
import org.valkyrienskies.skyriders.content.entity.CavendishEntity
import org.valkyrienskies.skyriders.content.entity.ExtendingArmEntity
import org.valkyrienskies.skyriders.content.entity.FakeItemBoxEntity
import org.valkyrienskies.skyriders.content.entity.GlassoEntity
import org.valkyrienskies.skyriders.content.entity.HoneyHeisterEntity
import org.valkyrienskies.skyriders.content.entity.ItemBoxEntity
import org.valkyrienskies.skyriders.content.entity.SugarRocketEntity
import org.valkyrienskies.skyriders.content.item.CavendishItem
import org.valkyrienskies.skyriders.content.item.BergenDisc
import org.valkyrienskies.skyriders.content.item.CreativeJerryCanItem
import org.valkyrienskies.skyriders.content.item.ExtendingArmItem
import org.valkyrienskies.skyriders.content.item.FakeItemBoxItem
import org.valkyrienskies.skyriders.content.item.FasterThanValkyrieDisc
import org.valkyrienskies.skyriders.content.item.GlassoItem
import org.valkyrienskies.skyriders.content.item.HoneyHeisterItem
import org.valkyrienskies.skyriders.content.item.HoneyCanisterItem
import org.valkyrienskies.skyriders.content.item.HoneyTankItem
import org.valkyrienskies.skyriders.content.item.ItemBoxItem
import org.valkyrienskies.skyriders.content.item.OpenModelComponentItem
import org.valkyrienskies.skyriders.content.item.RaceFlagItem
import org.valkyrienskies.skyriders.content.item.RacingDispenserBehaviors
import org.valkyrienskies.skyriders.content.item.RacingRouletteItem
import org.valkyrienskies.skyriders.content.item.RoyalJellyCanisterItem
import org.valkyrienskies.skyriders.content.item.StormchasingDisc
import org.valkyrienskies.skyriders.content.item.SugarRocketItem
import org.valkyrienskies.skyriders.content.item.ThunderboltItem
import org.valkyrienskies.skyriders.content.item.VehicleSpawnItem
import org.valkyrienskies.skyriders.content.BikeDefinitions
import org.valkyrienskies.skyriders.content.KartDefinitions
import org.valkyrienskies.skyriders.content.WheeledVehicleDefinitions
import org.valkyrienskies.skyriders.content.racing.RaceCompassItem
import org.valkyrienskies.skyriders.content.racing.RaceDangerBlock
import org.valkyrienskies.skyriders.content.racing.RaceDangerBlockEntity
import org.valkyrienskies.skyriders.content.racing.RaceEndpointBlock
import org.valkyrienskies.skyriders.content.racing.RaceEndpointBlockEntity
import org.valkyrienskies.skyriders.content.racing.RaceEndpointBlockItem
import org.valkyrienskies.skyriders.content.racing.RaceFlagColoringRecipe
import org.valkyrienskies.skyriders.content.racing.RaceMarkerBlock
import org.valkyrienskies.skyriders.content.racing.RaceMarkerBlockEntity
import org.valkyrienskies.skyriders.content.racing.RaceMarkerBlockItem
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
    private val RECIPE_SERIALIZERS = DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, MOD_ID)
    private val CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID)
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
    val RACE_MARKER_BLOCK: RegistryObject<Block> = BLOCKS.register("race_marker") {
        RaceMarkerBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_RED)
                .strength(1.0f)
                .sound(SoundType.METAL)
                .noOcclusion()
        )
    }
    val RACE_MARKER_ITEM: RegistryObject<Item> = ITEMS.register("race_marker") {
        RaceMarkerBlockItem(RACE_MARKER_BLOCK.get(), Item.Properties())
    }
    val RACE_ENDPOINT_BLOCK: RegistryObject<Block> = BLOCKS.register("race_endpoint") {
        RaceEndpointBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_GRAY)
                .strength(1.0f)
                .sound(SoundType.METAL)
                .noOcclusion()
        )
    }
    val RACE_ENDPOINT_ITEM: RegistryObject<Item> = ITEMS.register("race_endpoint") {
        RaceEndpointBlockItem(RACE_ENDPOINT_BLOCK.get(), Item.Properties())
    }
    val RACE_DANGER_BLOCK: RegistryObject<Block> = registerBlockAndItem("race_danger") {
        RaceDangerBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .strength(1.0f)
                .sound(SoundType.METAL)
                .noOcclusion()
        )
    }
    val RACE_MARKER_BLOCK_ENTITY: RegistryObject<BlockEntityType<RaceMarkerBlockEntity>> =
        BLOCK_ENTITIES.register("race_marker") {
            BlockEntityType.Builder.of(::RaceMarkerBlockEntity, RACE_MARKER_BLOCK.get()).build(null)
        }
    val RACE_ENDPOINT_BLOCK_ENTITY: RegistryObject<BlockEntityType<RaceEndpointBlockEntity>> =
        BLOCK_ENTITIES.register("race_endpoint") {
            BlockEntityType.Builder.of(::RaceEndpointBlockEntity, RACE_ENDPOINT_BLOCK.get()).build(null)
        }
    val RACE_DANGER_BLOCK_ENTITY: RegistryObject<BlockEntityType<RaceDangerBlockEntity>> =
        BLOCK_ENTITIES.register("race_danger") {
            BlockEntityType.Builder.of(::RaceDangerBlockEntity, RACE_DANGER_BLOCK.get()).build(null)
        }
    val CREATIVE_JERRY_CAN: RegistryObject<Item> = ITEMS.register("creative_jerry_can") {
        CreativeJerryCanItem(Item.Properties().stacksTo(1))
    }
    val RACE_FLAG: RegistryObject<Item> = ITEMS.register("flag") {
        RaceFlagItem(Item.Properties().stacksTo(16))
    }
    val ITEM_BOX: RegistryObject<Item> = ITEMS.register("item_box") {
        ItemBoxItem(Item.Properties().stacksTo(16))
    }
    val RACING_ROULETTE: RegistryObject<Item> = ITEMS.register("racing_roulette") {
        RacingRouletteItem(Item.Properties().stacksTo(1))
    }
    val HONEY_CANISTER: RegistryObject<Item> = ITEMS.register("honey_canister") {
        HoneyCanisterItem(Item.Properties().stacksTo(16))
    }
    val ROYAL_JELLY_CANISTER: RegistryObject<Item> = ITEMS.register("royal_jelly_canister") {
        RoyalJellyCanisterItem(Item.Properties().durability(4).rarity(Rarity.RARE))
    }
    val HONEY_TANK: RegistryObject<Item> = ITEMS.register("honey_tank") {
        HoneyTankItem(Item.Properties().stacksTo(16))
    }
    val FAKE_ITEM_BOX: RegistryObject<Item> = ITEMS.register("fake_item_box") {
        FakeItemBoxItem(Item.Properties().stacksTo(16))
    }
    val THUNDERBOLT: RegistryObject<Item> = ITEMS.register("thunderbolt") {
        ThunderboltItem(Item.Properties().stacksTo(16))
    }
    val CAVENDISH: RegistryObject<Item> = ITEMS.register("cavendish") {
        CavendishItem(Item.Properties().stacksTo(16))
    }
    val GLASSO: RegistryObject<Item> = ITEMS.register("glasso") {
        GlassoItem(Item.Properties().stacksTo(16))
    }
    val SUGAR_ROCKET: RegistryObject<Item> = ITEMS.register("sugar_rocket") {
        SugarRocketItem(Item.Properties().stacksTo(16), homing = false)
    }
    val HOMING_SUGAR_ROCKET: RegistryObject<Item> = ITEMS.register("homing_sugar_rocket") {
        SugarRocketItem(Item.Properties().stacksTo(16), homing = true)
    }
    val HONEY_HEISTER: RegistryObject<Item> = ITEMS.register("honey_heister") {
        HoneyHeisterItem(Item.Properties().stacksTo(16))
    }
    val BOXING_GLOVE: RegistryObject<Item> = ITEMS.register("boxing_glove") {
        ExtendingArmItem(Item.Properties().stacksTo(16), ExtendingArmEntity.BOXING_GLOVE)
    }
    val GRABBY_HAND: RegistryObject<Item> = ITEMS.register("grabby_hand") {
        ExtendingArmItem(Item.Properties().stacksTo(16), ExtendingArmEntity.GRABBY_HAND)
    }
    val RACE_COMPASS: RegistryObject<Item> = ITEMS.register("race_compass") {
        RaceCompassItem(Item.Properties().stacksTo(1))
    }
    val BIKE_WHEEL: RegistryObject<Item> = ITEMS.register("bike_wheel") {
        OpenModelComponentItem(
            Item.Properties().stacksTo(64),
            ResourceLocation(MOD_ID, "bikes/dirt_bike/dirt_bike_bwheel"),
            previewScale = 1.05f
        )
    }
    val TRUCK_WHEEL: RegistryObject<Item> = ITEMS.register("truck_wheel") {
        OpenModelComponentItem(
            Item.Properties().stacksTo(64),
            ResourceLocation(MOD_ID, "vehicles/truck/bergentruck_brw"),
            previewScale = 0.55f,
            noCull = true
        )
    }
    val ENGINE_COMPONENT: RegistryObject<Item> = ITEMS.register("engine_component") {
        Item(Item.Properties().stacksTo(64))
    }
    val ENGINE: RegistryObject<Item> = ITEMS.register("engine") {
        Item(Item.Properties().stacksTo(16))
    }
    val BIKE_ENGINE: RegistryObject<Item> = ITEMS.register("bike_engine") {
        Item(Item.Properties().stacksTo(16))
    }
    val SPRING: RegistryObject<Item> = ITEMS.register("spring") {
        Item(Item.Properties().stacksTo(64))
    }
    val GEAR: RegistryObject<Item> = ITEMS.register("gear") {
        Item(Item.Properties().stacksTo(64))
    }
    val TRANSMISSION: RegistryObject<Item> = ITEMS.register("transmission") {
        Item(Item.Properties().stacksTo(16))
    }
    val SUSPENSION: RegistryObject<Item> = ITEMS.register("suspension") {
        Item(Item.Properties().stacksTo(16))
    }
    val DIRT_BIKE_ITEM: RegistryObject<Item> = ITEMS.register(BikeDefinitions.DIRT_BIKE.id.path) {
        VehicleSpawnItem(Item.Properties().stacksTo(16), BikeDefinitions.DIRT_BIKE.id)
    }
    val SPEEDSTER_ITEM: RegistryObject<Item> = ITEMS.register(KartDefinitions.SPEEDSTER.id.path) {
        VehicleSpawnItem(Item.Properties().stacksTo(16), KartDefinitions.SPEEDSTER.id)
    }
    val ATV_ITEM: RegistryObject<Item> = ITEMS.register(WheeledVehicleDefinitions.ATV.id.path) {
        VehicleSpawnItem(Item.Properties().stacksTo(16), WheeledVehicleDefinitions.ATV.id)
    }
    val PICKUP_TRUCK_ITEM: RegistryObject<Item> = ITEMS.register(WheeledVehicleDefinitions.PICKUP_TRUCK.id.path) {
        VehicleSpawnItem(Item.Properties().stacksTo(16), WheeledVehicleDefinitions.PICKUP_TRUCK.id)
    }
    val RACE_FLAG_COLORING_RECIPE_SERIALIZER: RegistryObject<RecipeSerializer<RaceFlagColoringRecipe>> =
        RECIPE_SERIALIZERS.register("crafting_special_race_flag_coloring") {
            SimpleCraftingRecipeSerializer(::RaceFlagColoringRecipe)
        }

    val BERGEN_DISC: RegistryObject<Item> = ITEMS.register(BergenDisc.BURGEN_TRUCK.path) {
        BergenDisc(Item.Properties().stacksTo(1).rarity(Rarity.RARE))
    }

    val STORMCHASING_DISC: RegistryObject<Item> = ITEMS.register("stormchasing_disc") {
        StormchasingDisc(Item.Properties().stacksTo(1).rarity(Rarity.RARE))
    }

    val FTV_DISC: RegistryObject<Item> = ITEMS.register("fasterthanavalkyrie_disc") {
        FasterThanValkyrieDisc(Item.Properties().stacksTo(1).rarity(Rarity.RARE))
    }

    val SKYRIDERS_TAB: RegistryObject<CreativeModeTab> = CREATIVE_TABS.register("skyriders") {
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.skyriders"))
            .icon { ItemStack(HONEY_CANISTER.get()) }
            .displayItems { _, output ->
                output.accept(BOOST_PAD_BLOCK.get())
                output.accept(BOOST_PAD_FLOOR_BLOCK.get())
                output.accept(RACE_MARKER_ITEM.get())
                output.accept(RACE_ENDPOINT_ITEM.get())
                output.accept(RACE_DANGER_BLOCK.get())
                output.accept(CREATIVE_JERRY_CAN.get())
                output.accept(RACE_FLAG.get())
                output.accept(ITEM_BOX.get())
                output.accept(RACING_ROULETTE.get())
                output.accept(RACE_COMPASS.get())
                output.accept(BIKE_WHEEL.get())
                output.accept(TRUCK_WHEEL.get())
                output.accept(ENGINE_COMPONENT.get())
                output.accept(ENGINE.get())
                output.accept(BIKE_ENGINE.get())
                output.accept(SPRING.get())
                output.accept(GEAR.get())
                output.accept(TRANSMISSION.get())
                output.accept(SUSPENSION.get())
                output.accept(DIRT_BIKE_ITEM.get())
                output.accept(SPEEDSTER_ITEM.get())
                output.accept(ATV_ITEM.get())
                output.accept(PICKUP_TRUCK_ITEM.get())
                output.accept(HONEY_CANISTER.get())
                output.accept(ROYAL_JELLY_CANISTER.get())
                output.accept(HONEY_TANK.get())
                output.accept(FAKE_ITEM_BOX.get())
                output.accept(CAVENDISH.get())
                output.accept(SUGAR_ROCKET.get())
                output.accept(HOMING_SUGAR_ROCKET.get())
                output.accept(GLASSO.get())
                output.accept(HONEY_HEISTER.get())
                output.accept(BOXING_GLOVE.get())
                output.accept(GRABBY_HAND.get())
                output.accept(THUNDERBOLT.get())
                output.accept(STORMCHASING_DISC.get())
                output.accept(FTV_DISC.get())
            }
            .build()
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
            .clientTrackingRange(96)
            .updateInterval(1)
            .build("$MOD_ID:cavendish")
    }
    val ITEM_BOX_ENTITY: RegistryObject<EntityType<ItemBoxEntity>> = ENTITIES.register("item_box") {
        EntityType.Builder.of(::ItemBoxEntity, MobCategory.MISC)
            .sized(1.25f, 1.65f)
            .clientTrackingRange(96)
            .updateInterval(1)
            .build("$MOD_ID:item_box")
    }
    val FAKE_ITEM_BOX_ENTITY: RegistryObject<EntityType<FakeItemBoxEntity>> = ENTITIES.register("fake_item_box") {
        EntityType.Builder.of(::FakeItemBoxEntity, MobCategory.MISC)
            .sized(0.9f, 0.9f)
            .clientTrackingRange(96)
            .updateInterval(1)
            .build("$MOD_ID:fake_item_box")
    }
    val SUGAR_ROCKET_ENTITY: RegistryObject<EntityType<SugarRocketEntity>> = ENTITIES.register("sugar_rocket") {
        EntityType.Builder.of(::SugarRocketEntity, MobCategory.MISC)
            .sized(1.0f, 1.0f)
            .clientTrackingRange(16)
            .updateInterval(1)
            .build("$MOD_ID:sugar_rocket")
    }
    val GLASSO_ENTITY: RegistryObject<EntityType<GlassoEntity>> = ENTITIES.register("glasso") {
        EntityType.Builder.of(::GlassoEntity, MobCategory.MISC)
            .sized(0.35f, 0.35f)
            .clientTrackingRange(16)
            .updateInterval(1)
            .build("$MOD_ID:glasso")
    }
    val HONEY_HEISTER_ENTITY: RegistryObject<EntityType<HoneyHeisterEntity>> = ENTITIES.register("honey_heister") {
        EntityType.Builder.of(::HoneyHeisterEntity, MobCategory.MISC)
            .sized(0.65f, 0.65f)
            .clientTrackingRange(16)
            .updateInterval(1)
            .build("$MOD_ID:honey_heister")
    }
    val EXTENDING_ARM_ENTITY: RegistryObject<EntityType<ExtendingArmEntity>> = ENTITIES.register("extending_arm") {
        EntityType.Builder.of(::ExtendingArmEntity, MobCategory.MISC)
            .sized(0.75f, 0.75f)
            .clientTrackingRange(16)
            .updateInterval(1)
            .build("$MOD_ID:extending_arm")
    }
    val BAD_EXPLOSION_ENTITY: RegistryObject<EntityType<BadExplosionEntity>> = ENTITIES.register("bad_explosion") {
        EntityType.Builder.of(::BadExplosionEntity, MobCategory.MISC)
            .sized(9.0f, 9.0f)
            .clientTrackingRange(16)
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
        RECIPE_SERIALIZERS.register(modEventBus)
        CREATIVE_TABS.register(modEventBus)
        SkyridersSounds.register(modEventBus)

        modEventBus.addListener(::init)
        MinecraftForge.EVENT_BUS.register(BikeLifecycle)
        MinecraftForge.EVENT_BUS.register(VehicleInteractionHandler)
        MinecraftForge.EVENT_BUS.register(VehicleDamageEvents)
        MinecraftForge.EVENT_BUS.addListener { event: RegisterCommandsEvent -> SkyridersCommands.register(event) }
        MinecraftForge.EVENT_BUS.addListener(::onLivingDrops)
        if (FMLEnvironment.dist.isClient) {
            modEventBus.addListener(SkyridersModClient::clientInit)
            modEventBus.addListener(SkyridersModClient::registerKeyMappings)
            modEventBus.addListener(SkyridersModClient::registerItemColors)
            modEventBus.addListener(SkyridersModClient::registerAdditionalModels)
            modEventBus.addListener(SkyridersModClient::registerGuiOverlays)
        }
    }

    @JvmStatic
    @OptIn(VsBeta::class)
    fun init (event: FMLCommonSetupEvent) {
        event.enqueueWork {
            SkyridersNetwork.register()
            RacingDispenserBehaviors.register()
            vsApi.collisionStartEvent.on(VehicleImpactDamageHandler::onCollisionStart)
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

    fun onLivingDrops(event: LivingDropsEvent) {
        val entity = event.entity

        if (entity !is Horse) return

        val source = event.source
        if (!source.`is`(SkyridersDamageTypes.VEHICLE_IMPACT)) return

        // 5%
        var chance = 0.05f
        if (VehicleImpairmentEffects.hasTipsy(source.entity as? LivingEntity)) {
            // 100%
            chance = 1.0f
        }

        if (entity.level().random.nextFloat() <= chance) {
            val stack = ItemStack(BERGEN_DISC.get())

            event.drops.add(
                ItemEntity(
                    entity.level(),
                    entity.x,
                    entity.y,
                    entity.z,
                    stack
                )
            )
        }
    }
}
