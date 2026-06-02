package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.resources.sounds.TickableSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.valkyrienskies.skyriders.content.BikeSoundDefinition
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.IBike
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import kotlin.math.abs
import kotlin.math.min

object BikeClientEffects {
    private val telemetryByBodyId = HashMap<Long, TimedTelemetry>()
    private val engineSoundsByBodyId = HashMap<Long, BikeEngineSound>()

    fun updateTelemetry(packet: SkyridersNetwork.BikeDebugPacket) {
        telemetryByBodyId[packet.bodyId] = TimedTelemetry(
            packet = packet,
            expireTick = Minecraft.getInstance().level?.gameTime?.plus(8L) ?: 0L
        )
    }

    fun getTelemetry(bodyId: Long): SkyridersNetwork.BikeDebugPacket? {
        val level = Minecraft.getInstance().level ?: return null
        val telemetry = telemetryByBodyId[bodyId] ?: return null
        return if (telemetry.expireTick >= level.gameTime) telemetry.packet else null
    }

    fun tick() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val gameTime = level.gameTime
        telemetryByBodyId.entries.removeIf { it.value.expireTick < gameTime }

        val bikes = BikeManager.getBikes(level)
        val activeBodyIds = bikes.mapTo(HashSet()) { it.bodyId }
        engineSoundsByBodyId.entries.removeIf { entry ->
            if (entry.key in activeBodyIds) {
                false
            } else {
                entry.value.stopNow()
                true
            }
        }
        bikes.forEach { bike ->
            tickEngineSound(minecraft, bike, telemetryByBodyId[bike.bodyId]?.packet)
        }

        bikes.forEach { bike ->
            spawnBikeEffects(level, bike, telemetryByBodyId[bike.bodyId]?.packet)
        }
    }

    private fun spawnBikeEffects(level: ClientLevel, bike: IBike, telemetry: SkyridersNetwork.BikeDebugPacket?) {
        val transform = try {
            bike.getRenderTransform()
        } catch (_: IllegalStateException) {
            return
        }
        val velocity = try {
            bike.getKinematics().velocity
        } catch (_: IllegalStateException) {
            return
        }
        val speed = if (velocity.isFinite()) velocity.length() else 0.0
        val render = bike.definition.render
        val exhaustPos = transform.toWorld.transformPosition(Vector3d(render.exhaustLocalPos))
        if (bike.state.engineOn && speed > 0.6 && exhaustPos.isFinite() && level.random.nextDouble() < exhaustChance(speed, telemetry)) {
            val exhaustVelocity = transform.rotation.transform(Vector3d(0.0, 0.025, -0.075 - min(speed, 20.0) * 0.003))
            level.addParticle(
                exhaustParticle(telemetry),
                exhaustPos.x,
                exhaustPos.y,
                exhaustPos.z,
                exhaustVelocity.x + randomSpread(level, 0.012),
                exhaustVelocity.y + randomSpread(level, 0.008),
                exhaustVelocity.z + randomSpread(level, 0.012)
            )
        }

        val drifting = telemetry?.drifting == true
        val frontGrounded = telemetry?.frontGrounded ?: (speed > 4.0)
        val rearGrounded = telemetry?.rearGrounded ?: (speed > 4.0)
        if (frontGrounded) {
            spawnTireParticles(level, wheelGroundParticlePos(bike, true, transform), speed, drifting)
        }
        if (rearGrounded) {
            spawnTireParticles(level, wheelGroundParticlePos(bike, false, transform), speed, drifting)
        }
    }

    private fun spawnTireParticles(level: ClientLevel, position: Vector3d, speed: Double, drifting: Boolean) {
        if (!position.isFinite()) return

        val chance = if (drifting) {
            0.9
        } else {
            ((speed - 2.5) / 13.0).coerceIn(0.0, 0.45)
        }
        if (level.random.nextDouble() >= chance) return

        if (drifting && level.random.nextDouble() < 0.35) {
            level.addParticle(
                ParticleTypes.CLOUD,
                position.x,
                position.y + 0.015,
                position.z,
                randomSpread(level, 0.045),
                0.012 + level.random.nextDouble() * 0.012,
                randomSpread(level, 0.045)
            )
        }

        val blockState = groundState(level, position) ?: return
        val particle = BlockParticleOption(ParticleTypes.BLOCK, blockState)
        level.addParticle(
            particle,
            position.x,
            position.y + 0.01,
            position.z,
            randomSpread(level, 0.055),
            0.012 + level.random.nextDouble() * 0.02,
            randomSpread(level, 0.055)
        )
    }

    private fun exhaustParticle(telemetry: SkyridersNetwork.BikeDebugPacket?): ParticleOptions {
        if (telemetry?.drifting != true) return ParticleTypes.SMOKE
        return when {
            telemetry.driftBoostLevel >= 3 -> ParticleTypes.DRAGON_BREATH
            telemetry.driftBoostLevel >= 2 -> ParticleTypes.SOUL_FIRE_FLAME
            telemetry.driftBoostCharge > 0.25 -> ParticleTypes.FLAME
            else -> ParticleTypes.SMOKE
        }
    }

    private fun exhaustChance(speed: Double, telemetry: SkyridersNetwork.BikeDebugPacket?): Double {
        val speedChance = (0.12 + speed / 42.0).coerceIn(0.12, 0.55)
        return if (telemetry?.drifting == true) 0.85 else speedChance
    }

    private fun tickEngineSound(minecraft: Minecraft, bike: IBike, telemetry: SkyridersNetwork.BikeDebugPacket?) {
        val soundId = bike.definition.sounds.engineLoop
        if (soundId == null || !bike.state.engineOn) {
            engineSoundsByBodyId.remove(bike.bodyId)?.stopNow()
            return
        }
        val speed = try {
            bike.getKinematics().velocity.length()
        } catch (_: IllegalStateException) {
            0.0
        }
        val throttle = abs(telemetry?.throttle ?: 0.0)
        val existingSound = engineSoundsByBodyId[bike.bodyId]
        val sound = if (existingSound?.soundId == soundId) {
            existingSound
        } else {
            existingSound?.stopNow()
            BikeEngineSound(bike, soundId, bike.definition.sounds).also {
                engineSoundsByBodyId[bike.bodyId] = it
            }
        }
        sound.update(speed, throttle)
        if (!minecraft.soundManager.isActive(sound)) {
            minecraft.soundManager.play(sound)
        }
    }

    private fun wheelGroundParticlePos(
        bike: IBike,
        front: Boolean,
        transform: org.valkyrienskies.core.api.bodies.properties.BodyTransform
    ): Vector3d {
        val local = Vector3d(if (front) bike.config.frontWheelLocalPos else bike.config.rearWheelLocalPos)
        local.y -= bike.config.wheelRadius + 0.035
        return transform.toWorld.transformPosition(local)
    }

    private fun groundState(level: ClientLevel, position: Vector3d): BlockState? {
        val pos = BlockPos.containing(position.x, position.y - 0.08, position.z)
        val state = level.getBlockState(pos)
        return if (state.isAir) null else state
    }

    private fun randomSpread(level: ClientLevel, magnitude: Double): Double {
        return (level.random.nextDouble() - 0.5) * 2.0 * magnitude
    }

    private fun Vector3d.isFinite(): Boolean {
        return x.isFinite() && y.isFinite() && z.isFinite()
    }

    private data class TimedTelemetry(
        val packet: SkyridersNetwork.BikeDebugPacket,
        val expireTick: Long
    )

    private class BikeEngineSound(
        private val bike: IBike,
        val soundId: net.minecraft.resources.ResourceLocation,
        private val soundDefinition: BikeSoundDefinition
    ) : AbstractSoundInstance(
        soundId,
        SoundSource.NEUTRAL,
        SoundInstance.createUnseededRandom()
    ), TickableSoundInstance {
        private var stopped = false

        init {
            looping = true
            attenuation = SoundInstance.Attenuation.LINEAR
            volume = 0.0f
            pitch = 0.75f
        }

        fun update(speed: Double, throttle: Double) {
            val transform = try {
                bike.getRenderTransform()
            } catch (_: IllegalStateException) {
                volume = 0.0f
                return
            }
            val position = transform.toWorld.transformPosition(Vector3d())
            x = position.x
            y = position.y
            z = position.z

            val speedT = (speed / 18.0).coerceIn(0.0, 1.0)
            val soundSpeedT = (speed / soundDefinition.referenceSpeed).coerceIn(0.0, 1.0)
            val accelT = throttle.coerceIn(0.0, 1.0)
            volume = (
                soundDefinition.idleVolume +
                    soundSpeedT * soundDefinition.speedVolume +
                    accelT * soundDefinition.throttleVolume
                ).coerceAtLeast(0.0).toFloat()
            pitch = (
                soundDefinition.idlePitch +
                    soundSpeedT * soundDefinition.speedPitch +
                    accelT * soundDefinition.throttlePitch
                ).coerceIn(soundDefinition.minPitch, soundDefinition.maxPitch).toFloat()
        }

        override fun tick() {
            if (bike.level.isClientSide && BikeManager.getBike(bike.level, bike.bodyId) != null) {
                return
            }
            stopNow()
        }

        override fun isStopped(): Boolean {
            return stopped
        }

        fun stopNow() {
            stopped = true
        }
    }
}
