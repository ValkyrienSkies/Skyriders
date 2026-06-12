package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.skyriders.content.VehicleJukebox
import org.valkyrienskies.skyriders.content.VehicleManager

object VehicleJukeboxClientSounds {
    private val activeSounds = HashMap<BodyId, TruckDiscSound>()

    fun tick() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return clear(minecraft)
        val seenVehicles = HashSet<BodyId>()

        VehicleManager.getVehicles(level).forEach { vehicle ->
            if (!VehicleJukebox.hasJukebox(vehicle)) return@forEach
            seenVehicles.add(vehicle.bodyId)
            val disc = VehicleJukebox.discStack(vehicle)
            if (disc.isEmpty) {
                stop(vehicle.bodyId, minecraft)
                return@forEach
            }

            val sound = VehicleJukebox.discSound(disc) ?: return@forEach
            val discKey = VehicleJukebox.discIdentity(disc)
            val active = activeSounds[vehicle.bodyId]
            if (active?.discKey == discKey) return@forEach

            stop(vehicle.bodyId, minecraft)
            val instance = TruckDiscSound(vehicle.bodyId, discKey, sound)
            activeSounds[vehicle.bodyId] = instance
            minecraft.soundManager.play(instance)
        }

        activeSounds.keys.toList()
            .filterNot(seenVehicles::contains)
            .forEach { bodyId -> stop(bodyId, minecraft) }
    }

    fun clear() {
        clear(Minecraft.getInstance())
    }

    private fun clear(minecraft: Minecraft) {
        activeSounds.values.forEach(minecraft.soundManager::stop)
        activeSounds.clear()
    }

    private fun stop(bodyId: BodyId, minecraft: Minecraft) {
        activeSounds.remove(bodyId)?.let(minecraft.soundManager::stop)
    }

    private class TruckDiscSound(
        private val bodyId: BodyId,
        val discKey: String,
        sound: SoundEvent
    ) : AbstractTickableSoundInstance(sound, SoundSource.RECORDS, RandomSource.create()) {
        init {
            volume = 4.0f
            pitch = 1.0f
            looping = false
            delay = 0
            attenuation = SoundInstance.Attenuation.LINEAR
            relative = false
            updatePosition()
        }

        override fun tick() {
            val level = Minecraft.getInstance().level
            val vehicle = level?.let { VehicleManager.getVehicle(it, bodyId) }
            if (vehicle == null || VehicleJukebox.discIdentity(VehicleJukebox.discStack(vehicle)) != discKey) {
                stop()
                return
            }
            updatePosition()
        }

        private fun updatePosition() {
            val level = Minecraft.getInstance().level ?: return
            val vehicle = VehicleManager.getVehicle(level, bodyId) ?: return
            val position = try {
                vehicle.getRenderTransform()?.toWorld?.transformPosition(Vector3d(0.0, 0.75, 0.0))
            } catch (_: IllegalStateException) {
                null
            } ?: return
            x = position.x
            y = position.y
            z = position.z
        }
    }
}
