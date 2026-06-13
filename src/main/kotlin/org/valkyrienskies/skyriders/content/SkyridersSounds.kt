package org.valkyrienskies.skyriders.content

import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject
import org.valkyrienskies.skyriders.SkyridersMod.MOD_ID
import org.valkyrienskies.skyriders.content.item.BergenDisc

object SkyridersSounds {
    private val SOUND_EVENTS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MOD_ID)

    //group engine

    val BIKE_ENGINE_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("bike_engine") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "bike_engine"))
    }
    val BIKE_ENGINE_START_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("bike_engine_start") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "bike_engine_start"))
    }
    val BIKE_ENGINE_STOP_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("bike_engine_stop") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "bike_engine_stop"))
    }
    val BIKE_HORN_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("bike_horn") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "bike_horn"))
    }

    val KART_ENGINE_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("kart_engine") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "karts/kart_engine"))
    }
    val KART_ENGINE_DRIFT_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("kart_engine_drift") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "karts/kart_drift"))
    }
    val GENERIC_ENGINE_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("engine") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "engine"))
    }
    val GENERIC_ENGINE_START_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("engine_start") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "engine_start"))
    }
    val GENERIC_ENGINE_START_FAIL_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("engine_start_fail") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "engine_start_fail"))
    }
    val GENERIC_ENGINE_STOP_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("engine_stop") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "engine_stop"))
    }
    val GENERIC_HORN_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("horn") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "horn"))
    }
    val TRUCK_ENGINE_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("truck_engine") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "truck_engine"))
    }
    val TRUCK_ENGINE_START_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("truck_engine_start") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "truck_engine_start"))
    }
    val TRUCK_ENGINE_STOP_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("truck_engine_stop") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "truck_engine_stop"))
    }
    val TRUCK_HORN_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("truck_horn") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "truck_horn"))
    }

    //group mechanical
    val GEARSHIFT_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("gearshift") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "gearshift"))
    }
    val HANDBRAKE_ENGAGE_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("handbrake_engage") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "handbrake_engage"))
    }
    val HANDBRAKE_DISENGAGE_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("handbrake_disengage") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "handbrake_disengage"))
    }
    val TRUCK_DOOR_OPEN_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("truck_door_open") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "truck_door_open"))
    }
    val TRUCK_DOOR_CLOSE_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("truck_door_close") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "truck_door_close"))
    }

    //group damage
    val CRASH_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("crash") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "crash"))
    }
    val TIRE_POP_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("tire_pop") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "tire_pop"))
    }
    val TIRE_LEAK_1_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("tire_leak_1") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "tire_leak_1"))
    }
    val TIRE_LEAK_2_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("tire_leak_2") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "tire_leak_2"))
    }
    val ENGINE_BREAK_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("engine_break") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "engine_break"))
    }
    val CRT_CRITICAL_START_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("crt_critical_start") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "crt_critical_start"))
    }
    val CRT_WARNING_BEEP_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("crt_warning_beep") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "crt_warning_beep"))
    }

    //group racing
    val BOOST_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("boost") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "boost"))
    }
    val SPINOUT_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("spinout") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "spinout"))
    }

    val RACING_ITEM_GET_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("racing_item_get") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "racing_item_get"))
    }
    val RACING_ITEM_USE_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("racing_item_use") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "racing_item_use"))
    }
    val ROULETTE_SPIN_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("roulette_spin") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "roulette_spin"))
    }
    val ROULETTE_WIN_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("roulette_win") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "roulette_win"))
    }

    val THUNDERBOLT_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("thunderbolt") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "thunderbolt"))
    }
    val SUGAR_ROCKET_EXPLODE_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("sugar_rocket_explode") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "sugar_rocket_explode"))
    }
    val EXTENDING_SPRING_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("extending_spring") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "extending_spring"))
    }
    val BOXING_GLOVE_HIT_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("boxing_glove_hit") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "boxing_glove_hit"))
    }
    val GRABBY_HAND_GRAB_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("grabby_hand_grab") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "grabby_hand_grab"))
    }
    val RACE_START_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("race_start") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "race_start"))
    }
    val RACE_FINISH_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("finish") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "finish"))
    }
    val RACE_MUSIC_STORMCHASING: RegistryObject<SoundEvent> = SOUND_EVENTS.register("stormchasing") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "stormchasing"))
    }
    val RACE_MUSIC_FTAV: RegistryObject<SoundEvent> = SOUND_EVENTS.register("fasterthanavalkyrie") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "fasterthanavalkyrie"))
    }
    val UNDER_PALE_MOONLIGHT_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register("underpalemoonlight") {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, "underpalemoonlight"))
    }
    val RACE_MUSIC_TRACKS: List<RegistryObject<SoundEvent>> = listOf(
        RACE_MUSIC_STORMCHASING,
        RACE_MUSIC_FTAV
    )

    val BERGEN_DISC_SOUND: RegistryObject<SoundEvent> = SOUND_EVENTS.register(BergenDisc.BURGEN_TRUCK.path) {
        SoundEvent.createVariableRangeEvent(ResourceLocation(MOD_ID, BergenDisc.BURGEN_TRUCK.path))
    }

    @JvmStatic
    fun register(eventBus: IEventBus) {
        SOUND_EVENTS.register(eventBus)
    }
}
