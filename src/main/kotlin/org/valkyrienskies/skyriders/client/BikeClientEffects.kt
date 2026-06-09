package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractSoundInstance
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.resources.sounds.TickableSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.valkyrienskies.skyriders.content.BikeManager
import org.valkyrienskies.skyriders.content.IBike
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.KartVehicleBehaviorDefinition
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleSoundDefinition
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.network.SkyridersNetwork
import org.valkyrienskies.mod.api.shipWorld
import kotlin.math.abs
import kotlin.math.min

object BikeClientEffects {
    private val telemetryByBodyId = HashMap<Long, TimedTelemetry>()
    private val vehicleTelemetryByBodyId = HashMap<Long, TimedVehicleTelemetry>()
    private val engineSoundsByBodyId = HashMap<Long, VehicleEngineSound>()
    private val lastEngineStateByBodyId = HashMap<Long, Boolean>()
    private val lastGearByBodyId = HashMap<Long, Int>()
    private val lastParkingBrakeByBodyId = HashMap<Long, Boolean>()

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

    fun updateVehicleTelemetry(packet: SkyridersNetwork.VehicleDebugPacket) {
        vehicleTelemetryByBodyId[packet.bodyId] = TimedVehicleTelemetry(
            packet = packet,
            expireTick = Minecraft.getInstance().level?.gameTime?.plus(8L) ?: 0L
        )
    }

    fun tick() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val gameTime = level.gameTime
        telemetryByBodyId.entries.removeIf { it.value.expireTick < gameTime }
        vehicleTelemetryByBodyId.entries.removeIf { it.value.expireTick < gameTime }

        val vehicles = VehicleManager.getVehicles(level)
        val bikes = vehicles.filterIsInstance<IBike>()
        val activeBodyIds = vehicles.mapTo(HashSet()) { it.bodyId }
        lastEngineStateByBodyId.keys.retainAll(activeBodyIds)
        lastGearByBodyId.keys.retainAll(activeBodyIds)
        lastParkingBrakeByBodyId.keys.retainAll(activeBodyIds)
        engineSoundsByBodyId.entries.removeIf { entry ->
            if (entry.value.isStopped() || entry.key !in activeBodyIds) {
                entry.value.stopNow()
                true
            } else {
                false
            }
        }
        vehicles.forEach { vehicle ->
            val bikeTelemetry = telemetryByBodyId[vehicle.bodyId]?.packet
            val vehicleTelemetry = vehicleTelemetryByBodyId[vehicle.bodyId]?.packet
            tickEngineSound(minecraft, vehicle, bikeTelemetry, vehicleTelemetry)
            playVehicleControlTransitionSounds(minecraft, vehicle, vehicleTelemetry)
            if (vehicle !is IBike) {
                spawnGenericVehicleEffects(level, vehicle, vehicleTelemetry)
            }
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
        val render = bike.vehicleDefinition.render
        if (bike.state.engineOn && speed > 0.6 && level.random.nextDouble() < exhaustChance(speed, telemetry)) {
            render.resolvedExhaustPoints().forEach { exhaustPoint ->
                val exhaustPos = transform.toWorld.transformPosition(Vector3d(exhaustPoint.localPos))
                if (exhaustPos.isFinite()) {
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
            }
        }

        val drifting = telemetry?.drifting == true
        val frontGrounded = telemetry?.frontGrounded ?: (speed > 4.0)
        val rearGrounded = telemetry?.rearGrounded ?: (speed > 4.0)
        val frontFallback = wheelGroundParticleLocalPos(bike, true)
        val rearFallback = wheelGroundParticleLocalPos(bike, false)
        render.resolvedTireParticlePoints(frontFallback, rearFallback).forEach { point ->
            val grounded = when {
                "front" in point.id -> frontGrounded
                "rear" in point.id -> rearGrounded
                else -> frontGrounded || rearGrounded
            }
            if (grounded) {
                spawnTireParticles(level, transform.toWorld.transformPosition(Vector3d(point.localPos)), speed, drifting)
            }
        }
    }

    private fun spawnGenericVehicleEffects(
        level: ClientLevel,
        vehicle: IVehicle,
        telemetry: SkyridersNetwork.VehicleDebugPacket?
    ) {
        val transform = try {
            vehicle.getRenderTransform()
        } catch (_: IllegalStateException) {
            return
        }
        val velocity = try {
            vehicle.level.shipWorld?.allBodies?.getById(vehicle.bodyId)?.kinematics?.velocity ?: return
        } catch (_: IllegalStateException) {
            return
        }
        val speed = if (velocity.isFinite()) velocity.length() else 0.0
        val render = vehicle.vehicleDefinition.render
        val drifting = telemetry?.drifting == true
        val boostActive = (telemetry?.driftBoostTimeRemaining ?: 0.0) > 0.0

        if (vehicle.vehicleState.engineOn && speed > 0.8 && level.random.nextDouble() < vehicleExhaustChance(speed, drifting, boostActive)) {
            render.resolvedExhaustPoints().forEach { exhaustPoint ->
                val exhaustPos = transform.toWorld.transformPosition(Vector3d(exhaustPoint.localPos))
                if (exhaustPos.isFinite()) {
                    val exhaustVelocity = transform.rotation.transform(Vector3d(0.0, 0.025, -0.08 - min(speed, 24.0) * 0.003))
                    level.addParticle(
                        vehicleExhaustParticle(telemetry),
                        exhaustPos.x,
                        exhaustPos.y,
                        exhaustPos.z,
                        exhaustVelocity.x + randomSpread(level, 0.014),
                        exhaustVelocity.y + randomSpread(level, 0.01),
                        exhaustVelocity.z + randomSpread(level, 0.014)
                    )
                }
            }
        }

        val tirePoints = resolvedVehicleTireParticlePoints(vehicle)
        val grounded = (telemetry?.groundedCount ?: 0) > 0
        if (grounded || speed > 3.5) {
            tirePoints.forEach { localPos ->
                val position = transform.toWorld.transformPosition(Vector3d(localPos))
                spawnTireParticles(level, position, speed, drifting)
                if (boostActive && level.random.nextDouble() < 0.4) {
                    level.addParticle(
                        ParticleTypes.FLAME,
                        position.x,
                        position.y + 0.03,
                        position.z,
                        randomSpread(level, 0.03),
                        0.02,
                        randomSpread(level, 0.03)
                    )
                }
            }
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
                ParticleTypes.CAMPFIRE_COSY_SMOKE,
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

    private fun vehicleExhaustParticle(telemetry: SkyridersNetwork.VehicleDebugPacket?): ParticleOptions {
        if (telemetry?.drifting != true) return ParticleTypes.SMOKE
        return when {
            telemetry.driftBoostLevel >= 3 -> ParticleTypes.DRAGON_BREATH
            telemetry.driftBoostLevel >= 2 -> ParticleTypes.SOUL_FIRE_FLAME
            telemetry.driftBoostCharge > 0.25 -> ParticleTypes.FLAME
            else -> ParticleTypes.CLOUD
        }
    }

    private fun vehicleExhaustChance(speed: Double, drifting: Boolean, boostActive: Boolean): Double {
        if (boostActive) return 0.95
        if (drifting) return 0.8
        return (0.1 + speed / 48.0).coerceIn(0.1, 0.45)
    }

    private fun exhaustChance(speed: Double, telemetry: SkyridersNetwork.BikeDebugPacket?): Double {
        val speedChance = (0.12 + speed / 42.0).coerceIn(0.12, 0.55)
        return if (telemetry?.drifting == true) 0.85 else speedChance
    }

    private fun tickEngineSound(
        minecraft: Minecraft,
        vehicle: IVehicle,
        bikeTelemetry: SkyridersNetwork.BikeDebugPacket?,
        vehicleTelemetry: SkyridersNetwork.VehicleDebugPacket?
    ) {
        playEngineTransitionSound(minecraft, vehicle)

        val soundDefinition = vehicle.vehicleDefinition.sounds
        val soundId = if (vehicleTelemetry?.drifting == true || bikeTelemetry?.drifting == true) soundDefinition.driftLoop else soundDefinition.engineLoop
        if (soundId == null || !vehicle.vehicleState.engineOn) {
            engineSoundsByBodyId.remove(vehicle.bodyId)?.stopNow()
            return
        }
        val speed = try {
            vehicle.level.shipWorld?.allBodies?.getById(vehicle.bodyId)?.kinematics?.velocity?.length() ?: 0.0
        } catch (_: IllegalStateException) {
            0.0
        }
        val throttle = abs(bikeTelemetry?.throttle ?: vehicleTelemetry?.throttle ?: 0.0)
        val existingSound = engineSoundsByBodyId[vehicle.bodyId]
        val sound = if (existingSound?.soundId == soundId) {
            existingSound
        } else {
            existingSound?.stopNow()
            VehicleEngineSound(vehicle, soundId, soundDefinition).also {
                engineSoundsByBodyId[vehicle.bodyId] = it
            }
        }
        sound.update(speed, throttle)
        if (!minecraft.soundManager.isActive(sound)) {
            minecraft.soundManager.play(sound)
        }
    }

    private fun playEngineTransitionSound(minecraft: Minecraft, vehicle: IVehicle) {
        val engineOn = vehicle.vehicleState.engineOn
        val previous = lastEngineStateByBodyId.put(vehicle.bodyId, engineOn)
        if (previous == null || previous == engineOn) return

        val soundId = if (engineOn) vehicle.vehicleDefinition.sounds.engineStart else vehicle.vehicleDefinition.sounds.engineStop
        soundId ?: return
        val position = try {
            vehicle.getRenderTransform().toWorld.transformPosition(Vector3d())
        } catch (_: IllegalStateException) {
            return
        }
        minecraft.soundManager.play(
            SimpleSoundInstance(
                soundId,
                SoundSource.NEUTRAL,
                0.48f,
                1.0f,
                SoundInstance.createUnseededRandom(),
                false,
                0,
                SoundInstance.Attenuation.LINEAR,
                position.x,
                position.y,
                position.z,
                false
            )
        )
    }

    private fun playVehicleControlTransitionSounds(
        minecraft: Minecraft,
        vehicle: IVehicle,
        telemetry: SkyridersNetwork.VehicleDebugPacket?
    ) {
        telemetry ?: return
        if (!telemetry.hasTransmission) return
        val soundDefinition = vehicle.vehicleDefinition.sounds
        val previousGear = lastGearByBodyId.put(vehicle.bodyId, telemetry.transmissionGear)
        if (previousGear != null && previousGear != telemetry.transmissionGear) {
            val randomPitch = 0.96f + (minecraft.level?.random?.nextFloat() ?: 0.5f) * 0.08f
            playVehicleOneShot(minecraft, vehicle, soundDefinition.gearShift, volume = 0.52f, pitch = randomPitch)
        }

        val previousParkingBrake = lastParkingBrakeByBodyId.put(vehicle.bodyId, telemetry.parkingBrakeEngaged)
        if (previousParkingBrake != null && previousParkingBrake != telemetry.parkingBrakeEngaged) {
            val soundId = if (telemetry.parkingBrakeEngaged) soundDefinition.handbrakeEngage else soundDefinition.handbrakeDisengage
            playVehicleOneShot(minecraft, vehicle, soundId, volume = 0.58f, pitch = 1.0f)
        }
    }

    private fun playVehicleOneShot(
        minecraft: Minecraft,
        vehicle: IVehicle,
        soundId: net.minecraft.resources.ResourceLocation?,
        volume: Float,
        pitch: Float
    ) {
        soundId ?: return
        val position = try {
            vehicle.getRenderTransform().toWorld.transformPosition(Vector3d())
        } catch (_: IllegalStateException) {
            return
        }
        minecraft.soundManager.play(
            SimpleSoundInstance(
                soundId,
                SoundSource.NEUTRAL,
                volume,
                pitch,
                SoundInstance.createUnseededRandom(),
                false,
                0,
                SoundInstance.Attenuation.LINEAR,
                position.x,
                position.y,
                position.z,
                false
            )
        )
    }

    private fun wheelGroundParticleLocalPos(
        bike: IBike,
        front: Boolean
    ): Vector3d {
        val local = Vector3d(if (front) bike.config.frontWheelLocalPos else bike.config.rearWheelLocalPos)
        local.y -= bike.config.wheelRadius + 0.035
        local.y += bike.vehicleDefinition.render.tireParticleLocalYOffset
        return local
    }

    private fun resolvedVehicleTireParticlePoints(vehicle: IVehicle): List<Vector3d> {
        val render = vehicle.vehicleDefinition.render
        if (vehicle is KartVehicle) {
            val behavior = vehicle.vehicleDefinition.behavior as? KartVehicleBehaviorDefinition
            val config = behavior?.physics
            if (config != null) {
                val fallback = config.wheelLocalPositions.mapIndexed { index, wheel ->
                    Vector3d(wheel).apply {
                        y -= config.wheelRadius + 0.04
                    }
                }
                if (render.tireParticlePoints.isNotEmpty()) {
                    return render.tireParticlePoints.map { Vector3d(it.localPos) }
                }
                return fallback
            }
        }
        return render.tireParticlePoints.map { Vector3d(it.localPos) }
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

    private data class TimedVehicleTelemetry(
        val packet: SkyridersNetwork.VehicleDebugPacket,
        val expireTick: Long
    )

    private class VehicleEngineSound(
        private val vehicle: IVehicle,
        val soundId: net.minecraft.resources.ResourceLocation,
        private val soundDefinition: VehicleSoundDefinition
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
                vehicle.getRenderTransform()
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
            if (vehicle.level.isClientSide) {
                val currentVehicle = VehicleManager.getVehicle(vehicle.level, vehicle.bodyId)
                if (currentVehicle != null && currentVehicle.vehicleState.engineOn) {
                    return
                }
                volume = 0.0f
                stopNow()
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
