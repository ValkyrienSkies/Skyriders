package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.skyriders.content.SkyridersSounds

object RacingClientSounds {
    fun playRocketExplosion(position: Vec3, volume: Float, pitch: Float) {
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance(
                SkyridersSounds.SUGAR_ROCKET_EXPLODE_SOUND.get().location,
                SoundSource.NEUTRAL,
                volume,
                pitch,
                SoundInstance.createUnseededRandom(),
                false,
                0,
                SoundInstance.Attenuation.NONE,
                position.x,
                position.y,
                position.z,
                false
            )
        )
    }
}
