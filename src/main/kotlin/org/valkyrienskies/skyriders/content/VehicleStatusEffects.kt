package org.valkyrienskies.skyriders.content

import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import org.joml.Vector3d
import org.joml.Vector3f
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.VsBody
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle
import org.valkyrienskies.skyriders.util.VehiclePhysicsMath
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max

object VehicleStatusEffects {
    private const val DEFAULT_SPIN_OUT_DURATION = 2.0
    private const val DEFAULT_SPIN_OUT_YAW_SPEED = 5.5
    private const val SPIN_OUT_IMMUNITY_SECONDS = 0.4
    private const val SPIN_OUT_YAW_RESPONSE = 34.0
    private const val SPIN_OUT_PLANAR_DAMPING = 2.1
    private const val SPIN_OUT_MAX_DAMPING_ACCELERATION = 18.0
    private const val SPIN_OUT_MAX_TORQUE_PER_MASS = 4200.0
    private const val DEFAULT_BOOST_DURATION = 0.75
    private const val DEFAULT_BOOST_ACCELERATION = 30.0
    private const val DEFAULT_BOOST_TARGET_SPEED = 32.0
    private const val DEFAULT_BOOST_FADE_RANGE = 8.0
    private const val DEFAULT_PULL_DURATION = 0.55
    private const val DEFAULT_PULL_ACCELERATION = 42.0
    private const val DEFAULT_PULL_MAX_SPEED = 24.0
    private const val DEFAULT_CARRY_DURATION = 0.3
    private const val DEFAULT_CARRY_STIFFNESS = 10.0
    private const val DEFAULT_CARRY_DAMPING = 7.0
    private const val DEFAULT_CARRY_MAX_ACCELERATION = 85.0
    private const val DEFAULT_SLIPPERY_DURATION = 2.35
    private const val DEFAULT_SLIPPERY_TRACTION_SCALE = 0.18
    private const val MOONDROP_DURATION = 25.0
    private const val MOONDROP_TOP_SPEED_MULTIPLIER = 1.3
    private const val MOONDROP_REPAIR_INTERVAL_TICKS = 20L
    private const val MOONDROP_REPAIR_FRACTION = 0.04
    private const val MOONDROP_TRAIL_INTERVAL_TICKS = 2L
    private const val MOONDROP_TRAIL_MIN_SPEED = 0.45

    private val WORLD_UP = Vector3d(0.0, 1.0, 0.0)
    private val LOCAL_FORWARD = Vector3d(0.0, 0.0, 1.0)
    private val LOCAL_UP = Vector3d(0.0, 1.0, 0.0)

    private val states = ConcurrentHashMap<VehicleKey, RuntimeState>()

    fun applySpinOut(
        vehicle: IVehicle,
        duration: Double = DEFAULT_SPIN_OUT_DURATION,
        yawSpeed: Double = DEFAULT_SPIN_OUT_YAW_SPEED,
        preserveMomentum: Boolean = false
    ) {
        val state = states.getOrPut(key(vehicle)) { RuntimeState() }
        if (state.spinOutImmunityRemaining > 0.0 && state.spinOutTimeRemaining <= 0.0) return

        state.spinOutDuration = duration.coerceAtLeast(0.05)
        state.spinOutTimeRemaining = state.spinOutDuration
        state.spinOutYawSpeed = yawSpeed.coerceAtLeast(0.0)
        state.spinOutDirection = if (ThreadLocalRandom.current().nextBoolean()) 1.0 else -1.0
        state.spinOutPreserveMomentum = preserveMomentum
        state.spinOutEngineWasOn = state.spinOutEngineWasOn || isEngineOn(vehicle)
        setEngineOn(vehicle, false)
        playOneShot(vehicle, SkyridersSounds.SPINOUT_SOUND.get(), 0.85f, randomPitch(0.92f, 1.08f))
    }

    fun applyBoost(
        vehicle: IVehicle,
        duration: Double = DEFAULT_BOOST_DURATION,
        acceleration: Double = DEFAULT_BOOST_ACCELERATION,
        targetSpeed: Double = DEFAULT_BOOST_TARGET_SPEED,
        fadeRange: Double = DEFAULT_BOOST_FADE_RANGE
    ) {
        val state = states.getOrPut(key(vehicle)) { RuntimeState() }
        state.boostDuration = duration.coerceAtLeast(0.05)
        state.boostTimeRemaining = max(state.boostTimeRemaining, state.boostDuration)
        state.boostAcceleration = acceleration.coerceAtLeast(0.0)
        state.boostTargetSpeed = targetSpeed.coerceAtLeast(0.0)
        state.boostFadeRange = fadeRange.coerceAtLeast(0.001)
        playOneShot(vehicle, SkyridersSounds.BOOST_SOUND.get(), 0.75f, randomPitch(0.94f, 1.04f))
    }

    fun applyPullToPoint(
        vehicle: IVehicle,
        target: Vector3d,
        duration: Double = DEFAULT_PULL_DURATION,
        acceleration: Double = DEFAULT_PULL_ACCELERATION,
        maxSpeed: Double = DEFAULT_PULL_MAX_SPEED
    ) {
        if (!VehiclePhysicsMath.isFinite(target)) return
        val state = states.getOrPut(key(vehicle)) { RuntimeState() }
        state.pullTarget = Vector3d(target)
        state.pullDuration = duration.coerceAtLeast(0.05)
        state.pullTimeRemaining = max(state.pullTimeRemaining, state.pullDuration)
        state.pullAcceleration = acceleration.coerceAtLeast(0.0)
        state.pullMaxSpeed = maxSpeed.coerceAtLeast(0.0)
    }

    fun applyCarryToPoint(
        vehicle: IVehicle,
        target: Vector3d,
        duration: Double = DEFAULT_CARRY_DURATION,
        stiffness: Double = DEFAULT_CARRY_STIFFNESS,
        damping: Double = DEFAULT_CARRY_DAMPING,
        maxAcceleration: Double = DEFAULT_CARRY_MAX_ACCELERATION
    ) {
        if (!VehiclePhysicsMath.isFinite(target)) return
        val state = states.getOrPut(key(vehicle)) { RuntimeState() }
        state.carryTarget = Vector3d(target)
        state.carryTimeRemaining = max(state.carryTimeRemaining, duration.coerceAtLeast(0.05))
        state.carryStiffness = stiffness.coerceAtLeast(0.0)
        state.carryDamping = damping.coerceAtLeast(0.0)
        state.carryMaxAcceleration = maxAcceleration.coerceAtLeast(0.0)
    }

    fun applySlippery(
        vehicle: IVehicle,
        duration: Double = DEFAULT_SLIPPERY_DURATION,
        tractionScale: Double = DEFAULT_SLIPPERY_TRACTION_SCALE
    ) {
        val state = states.getOrPut(key(vehicle)) { RuntimeState() }
        state.slipperyTimeRemaining = max(state.slipperyTimeRemaining, duration.coerceAtLeast(0.05))
        state.slipperyTractionScale = tractionScale.coerceIn(0.02, 1.0)
    }

    fun applyMoondrop(vehicle: IVehicle, duration: Double = MOONDROP_DURATION) {
        val state = states.getOrPut(key(vehicle)) { RuntimeState() }
        state.moondropDuration = duration.coerceAtLeast(0.05)
        state.moondropTimeRemaining = max(state.moondropTimeRemaining, state.moondropDuration)
        val level = vehicle.level as? ServerLevel
        if (level != null) {
            val endTick = level.gameTime + (state.moondropDuration * 20.0).toLong().coerceAtLeast(1L)
            state.moondropEndGameTick = max(state.moondropEndGameTick, endTick)
        }
    }

    fun modifyInput(vehicle: IVehicle, input: VehicleInput): VehicleInput {
        val state = states[key(vehicle)]
        return if ((state?.spinOutTimeRemaining ?: 0.0) > 0.0 && state?.spinOutPreserveMomentum != true) {
            VehicleInput.EMPTY.copy(
                brake = 1.0,
                handbrake = 1.0,
                riderPresent = input.riderPresent
            )
        } else {
            input
        }
    }

    fun isSpinningOut(vehicle: IVehicle): Boolean {
        return (states[key(vehicle)]?.spinOutTimeRemaining ?: 0.0) > 0.0
    }

    fun isBoosting(vehicle: IVehicle): Boolean {
        return (states[key(vehicle)]?.boostTimeRemaining ?: 0.0) > 0.0
    }

    fun isMoondropActive(vehicle: IVehicle): Boolean {
        val state = states[key(vehicle)] ?: return false
        return state.moondropTimeRemaining > 0.0 || state.moondropVisualActive
    }

    fun isMoondropActive(dimensionId: DimensionId, bodyId: BodyId): Boolean {
        val state = states[VehicleKey(dimensionId, bodyId)] ?: return false
        return state.moondropTimeRemaining > 0.0 || state.moondropVisualActive
    }

    fun topSpeedMultiplier(dimensionId: DimensionId, bodyId: BodyId): Double {
        return if (isMoondropActive(dimensionId, bodyId)) MOONDROP_TOP_SPEED_MULTIPLIER else 1.0
    }

    fun setClientMoondropVisual(dimensionId: DimensionId, bodyId: BodyId, active: Boolean) {
        val key = VehicleKey(dimensionId, bodyId)
        val state = states.getOrPut(key) { RuntimeState() }
        state.moondropVisualActive = active
        if (!active && state.isIdle()) {
            states.remove(key, state)
        }
    }

    fun tractionScale(dimensionId: DimensionId, bodyId: BodyId): Double {
        val state = states[VehicleKey(dimensionId, bodyId)] ?: return 1.0
        return if (state.slipperyTimeRemaining > 0.0) state.slipperyTractionScale else 1.0
    }

    fun gameTick(level: ServerLevel, vehicles: Iterable<IVehicle>) {
        val now = level.gameTime
        val shipWorld = level.shipWorld
        vehicles.forEach { vehicle ->
            val state = states[key(vehicle)] ?: return@forEach
            if (state.moondropEndGameTick != Long.MIN_VALUE) {
                state.moondropTimeRemaining = ((state.moondropEndGameTick - now).coerceAtLeast(0L) / 20.0)
            } else if (state.moondropTimeRemaining > 0.0) {
                state.moondropTimeRemaining = (state.moondropTimeRemaining - 0.05).coerceAtLeast(0.0)
            }

            if (state.moondropTimeRemaining > 0.0) {
                if (now - state.moondropLastRepairTick >= MOONDROP_REPAIR_INTERVAL_TICKS) {
                    VehicleDamage.repairAllParts(level, vehicle, MOONDROP_REPAIR_FRACTION)
                    state.moondropLastRepairTick = now
                }

                if (now - state.moondropLastTrailTick >= MOONDROP_TRAIL_INTERVAL_TICKS) {
                    val body = shipWorld?.allBodies?.getById(vehicle.bodyId) ?: return@forEach
                    spawnMoondropTrail(level, body, now)
                    state.moondropLastTrailTick = now
                }
            } else if (state.isIdle()) {
                states.remove(key(vehicle), state)
            }
        }
    }

    fun physTick(vehicle: IVehicle, body: PhysVsBody, dt: Double) {
        val state = states[key(vehicle)] ?: return
        val safeDt = dt.coerceIn(0.0, 0.25)

        if (state.spinOutImmunityRemaining > 0.0) {
            state.spinOutImmunityRemaining = (state.spinOutImmunityRemaining - safeDt).coerceAtLeast(0.0)
        }
        if (state.spinOutTimeRemaining > 0.0) {
            applySpinOutForces(vehicle, body, state)
            state.spinOutTimeRemaining = (state.spinOutTimeRemaining - safeDt).coerceAtLeast(0.0)
            if (state.spinOutTimeRemaining <= 0.0) {
                finishSpinOut(vehicle, state)
            }
        }
        if (state.boostTimeRemaining > 0.0) {
            applyBoostForce(vehicle, body, state)
            state.boostTimeRemaining = (state.boostTimeRemaining - safeDt).coerceAtLeast(0.0)
        }
        if (state.pullTimeRemaining > 0.0) {
            applyPullForce(vehicle, body, state)
            state.pullTimeRemaining = (state.pullTimeRemaining - safeDt).coerceAtLeast(0.0)
            if (state.pullTimeRemaining <= 0.0) {
                state.pullTarget = null
            }
        }
        if (state.carryTimeRemaining > 0.0) {
            applyCarryForce(vehicle, body, state)
            state.carryTimeRemaining = (state.carryTimeRemaining - safeDt).coerceAtLeast(0.0)
            if (state.carryTimeRemaining <= 0.0) {
                state.carryTarget = null
            }
        }
        if (state.slipperyTimeRemaining > 0.0) {
            state.slipperyTimeRemaining = (state.slipperyTimeRemaining - safeDt).coerceAtLeast(0.0)
        }
        if (state.isIdle()) {
            states.remove(key(vehicle), state)
        }
    }

    private fun spawnMoondropTrail(level: ServerLevel, body: VsBody, now: Long) {
        val velocity = body.kinematics.velocity
        val speed = velocity.length()
        if (speed < MOONDROP_TRAIL_MIN_SPEED || !VehiclePhysicsMath.isFinite(velocity)) return

        val center = body.kinematics.position
        val backwards = Vector3d(velocity).normalize().negate()
        repeat(3) { index ->
            val phase = now * 0.035 + index * 0.21 + level.random.nextDouble() * 0.18
            val color = pastelRainbowVector(phase)
            val offset = Vector3d(backwards).mul(0.35 + index * 0.18)
                .add(
                    randomBetween(level, -0.35, 0.35),
                    randomBetween(level, 0.15, 0.85),
                    randomBetween(level, -0.35, 0.35)
                )
            level.sendParticles(
                DustParticleOptions(color, 1.05f),
                center.x() + offset.x,
                center.y() + offset.y,
                center.z() + offset.z,
                1,
                0.04,
                0.04,
                0.04,
                0.02
            )
        }
    }

    private fun applySpinOutForces(vehicle: IVehicle, body: PhysVsBody, state: RuntimeState) {
        val up = VehiclePhysicsMath.transformDirection(body, LOCAL_UP, WORLD_UP)
        val velocity = body.kinematics.velocity
        val planarVelocity = Vector3d(velocity).fma(-VehiclePhysicsMath.safeDot(velocity, up), up)
        if (!state.spinOutPreserveMomentum && VehiclePhysicsMath.isFinite(planarVelocity)) {
            val dampingAcceleration = planarVelocity.length().coerceAtMost(SPIN_OUT_MAX_DAMPING_ACCELERATION)
            val dampingForce = if (planarVelocity.lengthSquared() > 1.0e-8) {
                planarVelocity.normalize().mul(-vehicle.vehicleDefinition.body.mass * dampingAcceleration * SPIN_OUT_PLANAR_DAMPING)
            } else {
                Vector3d()
            }
            VehiclePhysicsMath.safeApplyWorldForce(body, dampingForce, body.kinematics.position)
        }

        val currentYawVelocity = VehiclePhysicsMath.safeDot(body.kinematics.angularVelocity, up)
        val targetYawVelocity = state.spinOutYawSpeed * state.spinOutDirection
        val yawTorque = (targetYawVelocity - currentYawVelocity) *
            vehicle.vehicleDefinition.body.mass *
            SPIN_OUT_YAW_RESPONSE
        val maxTorque = vehicle.vehicleDefinition.body.mass * SPIN_OUT_MAX_TORQUE_PER_MASS
        VehiclePhysicsMath.safeApplyWorldTorque(body, Vector3d(up).mul(yawTorque.coerceIn(-maxTorque, maxTorque)))
    }

    private fun applyBoostForce(vehicle: IVehicle, body: PhysVsBody, state: RuntimeState) {
        val up = VehiclePhysicsMath.transformDirection(body, LOCAL_UP, WORLD_UP)
        val forward = VehiclePhysicsMath.transformDirection(body, LOCAL_FORWARD, LOCAL_FORWARD)
        val driveForward = VehiclePhysicsMath.projectOntoPlane(forward, up, forward)
        val forwardSpeed = max(0.0, VehiclePhysicsMath.safeDot(body.kinematics.velocity, driveForward))
        val speedScale = 1.0 - smoothstep(state.boostTargetSpeed, state.boostTargetSpeed + state.boostFadeRange, forwardSpeed)
        if (speedScale <= 0.0) return

        val force = driveForward.mul(vehicle.vehicleDefinition.body.mass * state.boostAcceleration * speedScale)
        VehiclePhysicsMath.safeApplyWorldForce(body, force, body.kinematics.position)
    }

    private fun applyPullForce(vehicle: IVehicle, body: PhysVsBody, state: RuntimeState) {
        val target = state.pullTarget ?: return
        val offset = Vector3d(target).sub(body.kinematics.position)
        if (!VehiclePhysicsMath.isFinite(offset) || offset.lengthSquared() < 1.0e-6) return
        val direction = offset.normalize()
        val speedTowardTarget = max(0.0, VehiclePhysicsMath.safeDot(body.kinematics.velocity, direction))
        val speedScale = if (state.pullMaxSpeed <= 0.0) {
            1.0
        } else {
            (1.0 - speedTowardTarget / state.pullMaxSpeed).coerceIn(0.0, 1.0)
        }
        if (speedScale <= 0.0) return
        val ramp = (state.pullTimeRemaining / state.pullDuration.coerceAtLeast(0.001)).coerceIn(0.15, 1.0)
        val force = direction.mul(vehicle.vehicleDefinition.body.mass * state.pullAcceleration * speedScale * ramp)
        VehiclePhysicsMath.safeApplyWorldForce(body, force, body.kinematics.position)
    }

    private fun applyCarryForce(vehicle: IVehicle, body: PhysVsBody, state: RuntimeState) {
        val target = state.carryTarget ?: return
        val offset = Vector3d(target).sub(body.kinematics.position)
        if (!VehiclePhysicsMath.isFinite(offset)) return
        val velocity = body.kinematics.velocity
        val acceleration = Vector3d(offset)
            .mul(state.carryStiffness)
            .fma(-state.carryDamping, velocity)
        if (!VehiclePhysicsMath.isFinite(acceleration) || acceleration.lengthSquared() < 1.0e-8) return
        val accelerationMagnitude = acceleration.length()
        val maxAcceleration = state.carryMaxAcceleration
        if (maxAcceleration > 0.0 && accelerationMagnitude > maxAcceleration) {
            acceleration.mul(maxAcceleration / accelerationMagnitude)
        }
        VehiclePhysicsMath.safeApplyWorldForce(
            body,
            acceleration.mul(vehicle.vehicleDefinition.body.mass),
            body.kinematics.position
        )
    }

    private fun finishSpinOut(vehicle: IVehicle, state: RuntimeState) {
        if (state.spinOutEngineWasOn && VehicleFuel.canStart(vehicle) && VehicleDamage.canEngineStart(vehicle)) {
            setEngineOn(vehicle, true)
        }
        state.spinOutEngineWasOn = false
        state.spinOutPreserveMomentum = false
        state.spinOutImmunityRemaining = SPIN_OUT_IMMUNITY_SECONDS
    }

    private fun isEngineOn(vehicle: IVehicle): Boolean {
        return when (vehicle) {
            is IBike -> vehicle.state.engineOn
            is KartVehicle -> vehicle.kartState.engineOn
            is WheeledVehicle -> vehicle.wheeledState.engineOn
            else -> vehicle.vehicleState.engineOn
        }
    }

    private fun setEngineOn(vehicle: IVehicle, enabled: Boolean) {
        when (vehicle) {
            is IBike -> vehicle.state.engineOn = enabled
            is KartVehicle -> vehicle.kartState.engineOn = enabled
            is WheeledVehicle -> {
                vehicle.wheeledState.engineOn = enabled
                if (enabled) {
                    val behavior = vehicle.vehicleDefinition.behavior as? WheeledVehicleBehaviorDefinition
                    val idleRpm = behavior?.physics?.engine?.idleRpm ?: 850.0
                    vehicle.wheeledState.engineStalled = false
                    vehicle.wheeledState.debugEngineStalled = false
                    vehicle.wheeledState.engineRpm = idleRpm
                    vehicle.wheeledState.debugEngineRpm = idleRpm
                }
            }
        }
    }

    private fun playOneShot(vehicle: IVehicle, sound: net.minecraft.sounds.SoundEvent, volume: Float, pitch: Float) {
        val level = vehicle.level as? ServerLevel ?: return
        val position = try {
            vehicle.getRenderTransform()?.toWorld?.transformPosition(Vector3d()) ?: Vector3d()
        } catch (_: IllegalStateException) {
            Vector3d()
        }
        level.playSound(null, position.x, position.y, position.z, sound, SoundSource.NEUTRAL, volume, pitch)
    }

    private fun key(vehicle: IVehicle): VehicleKey = VehicleKey(vehicle.level.dimensionId, vehicle.bodyId)

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        if (edge0 == edge1) return if (value < edge0) 0.0 else 1.0
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun randomPitch(min: Float, max: Float): Float {
        return ThreadLocalRandom.current().nextDouble(min.toDouble(), max.toDouble()).toFloat()
    }

    private fun randomBetween(level: ServerLevel, min: Double, max: Double): Double {
        return min + level.random.nextDouble() * (max - min)
    }

    private fun pastelRainbowVector(phase: Double): Vector3f {
        val hue = phase - kotlin.math.floor(phase)
        val x = 1.0 - kotlin.math.abs((hue * 6.0) % 2.0 - 1.0)
        val (rawR, rawG, rawB) = when ((hue * 6.0).toInt()) {
            0 -> Triple(1.0, x, 0.0)
            1 -> Triple(x, 1.0, 0.0)
            2 -> Triple(0.0, 1.0, x)
            3 -> Triple(0.0, x, 1.0)
            4 -> Triple(x, 0.0, 1.0)
            else -> Triple(1.0, 0.0, x)
        }
        val saturation = 0.38
        return Vector3f(
            (1.0 - saturation + rawR * saturation).toFloat(),
            (1.0 - saturation + rawG * saturation).toFloat(),
            (1.0 - saturation + rawB * saturation).toFloat()
        )
    }

    private data class VehicleKey(val dimension: DimensionId, val bodyId: BodyId)

    private class RuntimeState {
        var spinOutTimeRemaining: Double = 0.0
        var spinOutDuration: Double = 0.0
        var spinOutYawSpeed: Double = 0.0
        var spinOutDirection: Double = 1.0
        var spinOutEngineWasOn: Boolean = false
        var spinOutPreserveMomentum: Boolean = false
        var spinOutImmunityRemaining: Double = 0.0
        var boostTimeRemaining: Double = 0.0
        var boostDuration: Double = 0.0
        var boostAcceleration: Double = 0.0
        var boostTargetSpeed: Double = 0.0
        var boostFadeRange: Double = 0.0
        var pullTimeRemaining: Double = 0.0
        var pullDuration: Double = 0.0
        var pullAcceleration: Double = 0.0
        var pullMaxSpeed: Double = 0.0
        var pullTarget: Vector3d? = null
        var carryTimeRemaining: Double = 0.0
        var carryStiffness: Double = 0.0
        var carryDamping: Double = 0.0
        var carryMaxAcceleration: Double = 0.0
        var carryTarget: Vector3d? = null
        var slipperyTimeRemaining: Double = 0.0
        var slipperyTractionScale: Double = 1.0
        var moondropTimeRemaining: Double = 0.0
        var moondropDuration: Double = 0.0
        var moondropEndGameTick: Long = Long.MIN_VALUE
        var moondropVisualActive: Boolean = false
        var moondropLastRepairTick: Long = Long.MIN_VALUE / 4
        var moondropLastTrailTick: Long = Long.MIN_VALUE / 4

        fun isIdle(): Boolean {
            return spinOutTimeRemaining <= 0.0 &&
                spinOutImmunityRemaining <= 0.0 &&
                boostTimeRemaining <= 0.0 &&
                pullTimeRemaining <= 0.0 &&
                carryTimeRemaining <= 0.0 &&
                slipperyTimeRemaining <= 0.0 &&
                moondropTimeRemaining <= 0.0 &&
                !moondropVisualActive
        }
    }
}
