package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.SkyridersSounds

object RacingRouletteClientSounds {
    private var spinSound: RouletteSpinSound? = null

    fun ensureSpinLoop() {
        val minecraft = Minecraft.getInstance()
        val current = spinSound
        if (current != null && minecraft.soundManager.isActive(current)) return
        spinSound = RouletteSpinSound().also { minecraft.soundManager.play(it) }
    }

    private class RouletteSpinSound : AbstractTickableSoundInstance(
        SkyridersSounds.ROULETTE_SPIN_SOUND.get(),
        SoundSource.PLAYERS,
        RandomSource.create()
    ) {
        init {
            looping = true
            attenuation = SoundInstance.Attenuation.NONE
            relative = true
            volume = 0.42f
            pitch = 1.0f
        }

        override fun tick() {
            val player = Minecraft.getInstance().player
            val hasRoulette = player?.inventory?.items?.any { stack ->
                stack.`is`(SkyridersMod.RACING_ROULETTE.get())
            } == true
            if (!hasRoulette) {
                stop()
            }
        }
    }
}
