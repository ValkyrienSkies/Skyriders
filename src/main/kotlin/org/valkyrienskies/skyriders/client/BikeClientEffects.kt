package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.IBike
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import kotlin.math.abs
import kotlin.math.min

object BikeClientEffects {
    private val telemetryByBodyId = HashMap<Long, TimedTelemetry>()

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

        BikeManager.getBikes(level).forEach { bike ->
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
        if (speed < 0.6) return

        val render = bike.definition.render
        val exhaustPos = transform.toWorld.transformPosition(Vector3d(render.exhaustLocalPos))
        if (exhaustPos.isFinite() && level.random.nextDouble() < exhaustChance(speed, telemetry)) {
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
            spawnTireParticles(level, transform.toWorld.transformPosition(Vector3d(bike.config.frontWheelLocalPos).add(0.0, render.tireParticleLocalYOffset, 0.0)), speed, drifting)
        }
        if (rearGrounded) {
            spawnTireParticles(level, transform.toWorld.transformPosition(Vector3d(bike.config.rearWheelLocalPos).add(0.0, render.tireParticleLocalYOffset, 0.0)), speed, drifting)
        }
    }

    private fun spawnTireParticles(level: ClientLevel, position: Vector3d, speed: Double, drifting: Boolean) {
        if (!position.isFinite()) return

        val chance = if (drifting) {
            0.65
        } else {
            ((speed - 7.0) / 18.0).coerceIn(0.0, 0.28)
        }
        if (level.random.nextDouble() >= chance) return

        if (drifting && level.random.nextDouble() < 0.55) {
            level.addParticle(
                ParticleTypes.SMOKE,
                position.x,
                position.y + 0.05,
                position.z,
                randomSpread(level, 0.035),
                0.035 + level.random.nextDouble() * 0.025,
                randomSpread(level, 0.035)
            )
        }

        val blockState = groundState(level, position) ?: return
        val particle = BlockParticleOption(ParticleTypes.BLOCK, blockState)
        level.addParticle(
            particle,
            position.x,
            position.y + 0.025,
            position.z,
            randomSpread(level, 0.045),
            0.025 + level.random.nextDouble() * 0.025,
            randomSpread(level, 0.045)
        )
    }

    private fun exhaustParticle(telemetry: SkyridersNetwork.BikeDebugPacket?): ParticleOptions {
        if (telemetry?.drifting != true) return ParticleTypes.CAMPFIRE_COSY_SMOKE
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
}
