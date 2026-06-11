package org.valkyrienskies.skyriders.content

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraftforge.registries.ForgeRegistries
import org.joml.Vector3d

object VehicleInteractionSounds {
    fun play(
        player: ServerPlayer,
        vehicle: IVehicle,
        zone: VehicleInteractionZone,
        action: ResourceLocation,
        fallbackAction: ResourceLocation? = null
    ): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        val definition = zone.sounds[action]
            ?: fallbackAction?.let(zone.sounds::get)
            ?: return false
        val sound = ForgeRegistries.SOUND_EVENTS.getValue(definition.sound) ?: return false
        val position = try {
            vehicle.getRenderTransform()?.toWorld?.transformPosition(Vector3d(zone.center))
                ?: Vector3d(player.x, player.y, player.z)
        } catch (_: IllegalStateException) {
            Vector3d(player.x, player.y, player.z)
        }
        level.playSound(
            null,
            position.x,
            position.y,
            position.z,
            sound,
            SoundSource.NEUTRAL,
            definition.volume,
            definition.pitch
        )
        return true
    }
}
