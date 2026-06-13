package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import org.valkyrienskies.skyriders.content.SkyridersSounds
import kotlin.math.abs
import kotlin.math.max

object RaceMusicClientState {
    private var activeTrack: ResourceLocation? = null
    private var sound: FadingRaceMusicSound? = null
    private var moondropSound: MoondropThemeSound? = null

    fun start(track: ResourceLocation) {
        val minecraft = Minecraft.getInstance()
        activeTrack = track
        minecraft.musicManager.stopPlaying()

        val current = sound
        if (current?.track == track && minecraft.soundManager.isActive(current)) {
            current.targetVolume = if (isMoondropEffectActive()) 0.0f else 1.0f
            return
        }

        current?.fadeOutAndStop()
        sound = FadingRaceMusicSound(track).also {
            it.targetVolume = if (isMoondropEffectActive()) 0.0f else 1.0f
            minecraft.soundManager.play(it)
        }
    }

    fun startMoondrop(durationTicks: Int) {
        val minecraft = Minecraft.getInstance()
        minecraft.musicManager.stopPlaying()
        sound?.targetVolume = 0.0f

        val current = moondropSound
        if (current != null && minecraft.soundManager.isActive(current)) {
            current.extend(durationTicks.coerceAtLeast(1))
            return
        }
        moondropSound = MoondropThemeSound(durationTicks.coerceAtLeast(1)).also { minecraft.soundManager.play(it) }
    }

    fun stop() {
        activeTrack = null
        sound?.fadeOutAndStop()
        moondropSound?.fadeOutAndStop()
    }

    fun stopMoondrop() {
        moondropSound?.fadeOutAndStop()
    }

    fun tick() {
        val minecraft = Minecraft.getInstance()
        minecraft.musicManager.stopPlaying()

        val currentMoondrop = moondropSound
        if (currentMoondrop != null && !minecraft.soundManager.isActive(currentMoondrop)) {
            moondropSound = null
        }

        val track = activeTrack ?: return
        val currentSound = sound
        if (currentSound == null || !minecraft.soundManager.isActive(currentSound)) {
            sound = null
            start(track)
        } else {
            currentSound.targetVolume = if (isMoondropEffectActive()) 0.0f else 1.0f
        }
    }

    private fun isMoondropEffectActive(): Boolean {
        return moondropSound?.effectActive == true
    }

    private class FadingRaceMusicSound(val track: ResourceLocation) : AbstractTickableSoundInstance(
        SoundEvent.createVariableRangeEvent(track),
        SoundSource.MUSIC,
        RandomSource.create()
    ) {
        var targetVolume: Float = 1.0f
        private var stopping = false

        init {
            looping = true
            attenuation = SoundInstance.Attenuation.NONE
            relative = true
            volume = 1.0f
            pitch = 1.0f
        }

        fun fadeOutAndStop() {
            stopping = true
            targetVolume = 0.0f
        }

        override fun tick() {
            volume = approach(volume, targetVolume, RACE_FADE_STEP)
            if (stopping && volume <= 0.01f) {
                stop()
            }
        }
    }

    private class MoondropThemeSound(durationTicks: Int) : AbstractTickableSoundInstance(
        SkyridersSounds.UNDER_PALE_MOONLIGHT_SOUND.get(),
        SoundSource.MUSIC,
        RandomSource.create()
    ) {
        private var age = 0
        private var endTick = durationTicks.coerceAtLeast(1)
        private var stopping = false
        val effectActive: Boolean
            get() = !stopping && age < endTick

        init {
            looping = true
            attenuation = SoundInstance.Attenuation.NONE
            relative = true
            volume = MIN_START_VOLUME
            pitch = 1.0f
        }

        fun extend(durationTicks: Int) {
            stopping = false
            endTick = max(endTick, age + durationTicks.coerceAtLeast(1))
        }

        fun fadeOutAndStop() {
            stopping = true
        }

        override fun tick() {
            age++
            val target = if (effectActive) 1.0f else 0.0f
            val step = if (target > volume) MOONDROP_FADE_IN_STEP else MOONDROP_FADE_OUT_STEP
            volume = approach(volume, target, step)
            if (!effectActive && volume <= 0.01f) {
                stop()
            }
        }
    }

    private fun approach(current: Float, target: Float, step: Float): Float {
        if (abs(target - current) <= step) return target
        return if (target > current) current + step else current - step
    }

    private const val RACE_FADE_STEP = 0.035f
    private const val MOONDROP_FADE_IN_STEP = 0.055f
    private const val MOONDROP_FADE_OUT_STEP = 0.028f
    private const val MIN_START_VOLUME = 0.01f
}
