package org.valkyrienskies.skyriders.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle
import org.valkyrienskies.skyriders.util.VehiclePhysicsMath
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max

object VehicleStatusEffects {
    private const val DEFAULT_SPIN_OUT_DURATION = 2.0
    private const val DEFAULT_SPIN_OUT_YAW_SPEED = 11.0
    private const val SPIN_OUT_IMMUNITY_SECONDS = 0.4
    private const val SPIN_OUT_YAW_RESPONSE = 260.0
    private const val SPIN_OUT_PLANAR_DAMPING = 4.25
    private const val DEFAULT_BOOST_DURATION = 0.75
    private const val DEFAULT_BOOST_ACCELERATION = 30.0
    private const val DEFAULT_BOOST_TARGET_SPEED = 32.0
    private const val DEFAULT_BOOST_FADE_RANGE = 8.0

    private val WORLD_UP = Vector3d(0.0, 1.0, 0.0)
    private val LOCAL_FORWARD = Vector3d(0.0, 0.0, 1.0)
    private val LOCAL_UP = Vector3d(0.0, 1.0, 0.0)

    private val states = ConcurrentHashMap<VehicleKey, RuntimeState>()

    fun applySpinOut(
        vehicle: IVehicle,
        duration: Double = DEFAULT_SPIN_OUT_DURATION,
        yawSpeed: Double = DEFAULT_SPIN_OUT_YAW_SPEED
    ) {
        val state = states.getOrPut(key(vehicle)) { RuntimeState() }
        if (state.spinOutImmunityRemaining > 0.0 && state.spinOutTimeRemaining <= 0.0) return

        state.spinOutDuration = duration.coerceAtLeast(0.05)
        state.spinOutTimeRemaining = state.spinOutDuration
        state.spinOutYawSpeed = yawSpeed.coerceAtLeast(0.0)
        state.spinOutDirection = if (ThreadLocalRandom.current().nextBoolean()) 1.0 else -1.0
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

    fun modifyInput(vehicle: IVehicle, input: VehicleInput): VehicleInput {
        return if (isSpinningOut(vehicle)) {
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
        if (state.isIdle()) {
            states.remove(key(vehicle), state)
        }
    }

    private fun applySpinOutForces(vehicle: IVehicle, body: PhysVsBody, state: RuntimeState) {
        val up = VehiclePhysicsMath.transformDirection(body, LOCAL_UP, WORLD_UP)
        val velocity = body.kinematics.velocity
        val planarVelocity = Vector3d(velocity).fma(-VehiclePhysicsMath.safeDot(velocity, up), up)
        if (VehiclePhysicsMath.isFinite(planarVelocity)) {
            val dampingForce = planarVelocity.mul(-vehicle.vehicleDefinition.body.mass * SPIN_OUT_PLANAR_DAMPING)
            VehiclePhysicsMath.safeApplyWorldForce(body, dampingForce, body.kinematics.position)
        }

        val currentYawVelocity = VehiclePhysicsMath.safeDot(body.kinematics.angularVelocity, up)
        val targetYawVelocity = state.spinOutYawSpeed * state.spinOutDirection
        val yawTorque = (targetYawVelocity - currentYawVelocity) *
            vehicle.vehicleDefinition.body.mass *
            SPIN_OUT_YAW_RESPONSE
        VehiclePhysicsMath.safeApplyWorldTorque(body, Vector3d(up).mul(yawTorque))
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

    private fun finishSpinOut(vehicle: IVehicle, state: RuntimeState) {
        if (state.spinOutEngineWasOn && VehicleFuel.canStart(vehicle)) {
            setEngineOn(vehicle, true)
        }
        state.spinOutEngineWasOn = false
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
            vehicle.getRenderTransform().toWorld.transformPosition(Vector3d())
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

    private data class VehicleKey(val dimension: DimensionId, val bodyId: BodyId)

    private data class RuntimeState(
        var spinOutTimeRemaining: Double = 0.0,
        var spinOutDuration: Double = 0.0,
        var spinOutYawSpeed: Double = 0.0,
        var spinOutDirection: Double = 1.0,
        var spinOutEngineWasOn: Boolean = false,
        var spinOutImmunityRemaining: Double = 0.0,
        var boostTimeRemaining: Double = 0.0,
        var boostDuration: Double = 0.0,
        var boostAcceleration: Double = 0.0,
        var boostTargetSpeed: Double = 0.0,
        var boostFadeRange: Double = 0.0
    ) {
        fun isIdle(): Boolean {
            return spinOutTimeRemaining <= 0.0 &&
                spinOutImmunityRemaining <= 0.0 &&
                boostTimeRemaining <= 0.0
        }
    }
}
