package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundSource

object RaceMusicClientState {
    private var activeTrack: ResourceLocation? = null
    private var sound: SimpleSoundInstance? = null

    fun start(track: ResourceLocation) {
        val minecraft = Minecraft.getInstance()
        if (activeTrack == track && sound?.let { minecraft.soundManager.isActive(it) } == true) return
        stopRaceSound(minecraft)
        activeTrack = track
        minecraft.musicManager.stopPlaying()
        sound = SimpleSoundInstance(
            track,
            SoundSource.MUSIC,
            1.0f,
            1.0f,
            SoundInstance.createUnseededRandom(),
            true,
            0,
            SoundInstance.Attenuation.NONE,
            0.0,
            0.0,
            0.0,
            true
        ).also { minecraft.soundManager.play(it) }
    }

    fun stop() {
        stopRaceSound(Minecraft.getInstance())
        activeTrack = null
    }

    fun tick() {
        val track = activeTrack ?: return
        val minecraft = Minecraft.getInstance()
        minecraft.musicManager.stopPlaying()
        val currentSound = sound
        if (currentSound == null || !minecraft.soundManager.isActive(currentSound)) {
            sound = null
            start(track)
        }
    }

    private fun stopRaceSound(minecraft: Minecraft) {
        sound?.let { minecraft.soundManager.stop(it) }
        sound = null
    }
}
