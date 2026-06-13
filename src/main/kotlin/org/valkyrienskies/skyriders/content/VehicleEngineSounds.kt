package org.valkyrienskies.skyriders.content

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraftforge.registries.ForgeRegistries
import org.valkyrienskies.skyriders.SkyridersMod

object VehicleEngineSounds {
    private val DEFAULT_START_FAIL = ResourceLocation(SkyridersMod.MOD_ID, "engine_start_fail")

    fun playStartFail(level: ServerLevel, vehicle: IVehicle) {
        val soundId = vehicle.vehicleDefinition.sounds.engineStartFail ?: DEFAULT_START_FAIL
        val sound = ForgeRegistries.SOUND_EVENTS.getValue(soundId) ?: return
        val position = VehicleSoundPositions.engineWorldPosition(vehicle) ?: return
        level.playSound(
            null,
            position.x,
            position.y,
            position.z,
            sound,
            SoundSource.NEUTRAL,
            0.7f,
            1.0f
        )
    }
}
