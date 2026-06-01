package org.valkyrienskies.skyriders

import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
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
import org.valkyrienskies.skyriders.content.BikeManager

@Mod("skyriders")
object SkyridersMod {

    const val MOD_ID = "skyriders"

    //Deferred Registries
    private val BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID)
    private val ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID)
    private val ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID)
    private val BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID)

    // Put RegistryObjects here:

    // end of RegistryObjects

    init {
        val modEventBus = FMLJavaModLoadingContext.get().modEventBus

        BLOCKS.register(modEventBus)
        ITEMS.register(modEventBus)
        ENTITIES.register(modEventBus)
        BLOCK_ENTITIES.register(modEventBus)

        modEventBus.addListener(::init)
        MinecraftForge.EVENT_BUS.addListener { event: RegisterCommandsEvent -> SkyridersCommands.register(event) }
        if (FMLEnvironment.dist.isClient) {
            modEventBus.addListener(SkyridersModClient::clientInit)
        }
    }

    @JvmStatic
    @OptIn(VsBeta::class)
    fun init (event: FMLCommonSetupEvent) {
        event.enqueueWork {
            vsApi.physTickEvent.on { physTickEvent ->
                BikeManager.physTick(physTickEvent.world, physTickEvent.delta)
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
