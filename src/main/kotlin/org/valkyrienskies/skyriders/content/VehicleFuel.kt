package org.valkyrienskies.skyriders.content

import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle
import kotlin.math.abs

object VehicleFuel {
    private const val EPSILON = 1.0e-4

    fun initialAmount(definition: VehicleDefinition, savedAmount: Double): Double {
        val capacity = capacity(definition)
        return if (savedAmount.isFinite()) savedAmount.coerceIn(0.0, capacity) else capacity
    }

    fun capacity(definition: VehicleDefinition): Double {
        return definition.fuel.capacity.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    }

    fun amount(vehicle: IVehicle): Double {
        return when (vehicle) {
            is IBike -> resolveRuntimeAmount(vehicle.vehicleDefinition, vehicle.state.fuelAmount)
            is KartVehicle -> resolveRuntimeAmount(vehicle.vehicleDefinition, vehicle.kartState.fuelAmount)
            is WheeledVehicle -> resolveRuntimeAmount(vehicle.vehicleDefinition, vehicle.wheeledState.fuelAmount)
            else -> capacity(vehicle.vehicleDefinition)
        }
    }

    fun fraction(vehicle: IVehicle): Double {
        val capacity = capacity(vehicle.vehicleDefinition)
        if (capacity <= 0.0) return 1.0
        return (amount(vehicle) / capacity).coerceIn(0.0, 1.0)
    }

    fun canStart(vehicle: IVehicle): Boolean {
        return capacity(vehicle.vehicleDefinition) <= 0.0 || amount(vehicle) > EPSILON
    }

    fun refill(vehicle: IVehicle): Double {
        val capacity = capacity(vehicle.vehicleDefinition)
        val before = amount(vehicle)
        setAmount(vehicle, capacity)
        if (vehicle is WheeledVehicle && capacity > 0.0) {
            vehicle.wheeledState.engineStalled = false
            vehicle.wheeledState.debugEngineStalled = false
        }
        return (capacity - before).coerceAtLeast(0.0)
    }

    fun consume(vehicle: IVehicle, body: PhysVsBody, input: VehicleInput, dt: Double) {
        if (!isEngineOn(vehicle)) return
        val definition = vehicle.vehicleDefinition
        val capacity = capacity(definition)
        if (capacity <= 0.0) return

        val current = amount(vehicle)
        if (current <= EPSILON) {
            stallOutOfFuel(vehicle)
            return
        }

        val safeDt = dt.coerceIn(0.0, 0.25)
        val throttle = abs(input.throttle.coerceIn(-1.0, 1.0))
        val speed = body.kinematics.velocity.length().takeIf { it.isFinite() } ?: 0.0
        val stepAssistWork = stepAssistWork(vehicle)
        val fuel = definition.fuel
        val rate =
            fuel.idleUsePerSecond.coerceAtLeast(0.0) +
                throttle * fuel.throttleUsePerSecond.coerceAtLeast(0.0) +
                throttle * speed * fuel.motionWorkUsePerSpeedPerSecond.coerceAtLeast(0.0) +
                stepAssistWork * fuel.stepAssistUsePerWorkSecond.coerceAtLeast(0.0)
        val next = (current - rate * safeDt).coerceAtLeast(0.0)
        setAmount(vehicle, next)
        if (next <= EPSILON) {
            stallOutOfFuel(vehicle)
        }
    }

    private fun resolveRuntimeAmount(definition: VehicleDefinition, runtimeAmount: Double): Double {
        val capacity = capacity(definition)
        return if (runtimeAmount.isFinite()) runtimeAmount.coerceIn(0.0, capacity) else capacity
    }

    private fun setAmount(vehicle: IVehicle, amount: Double) {
        val capacity = capacity(vehicle.vehicleDefinition)
        val clamped = amount.coerceIn(0.0, capacity)
        when (vehicle) {
            is IBike -> vehicle.state.fuelAmount = clamped
            is KartVehicle -> vehicle.kartState.fuelAmount = clamped
            is WheeledVehicle -> vehicle.wheeledState.fuelAmount = clamped
        }
    }

    private fun isEngineOn(vehicle: IVehicle): Boolean {
        return when (vehicle) {
            is IBike -> vehicle.state.engineOn
            is KartVehicle -> vehicle.kartState.engineOn
            is WheeledVehicle -> vehicle.wheeledState.engineOn
            else -> vehicle.vehicleState.engineOn
        }
    }

    private fun stepAssistWork(vehicle: IVehicle): Double {
        return when (vehicle) {
            is IBike -> vehicle.state.debugStepAssistWork
            is KartVehicle -> vehicle.kartState.debugStepAssistWork
            is WheeledVehicle -> vehicle.wheeledState.debugStepAssistWork
            else -> 0.0
        }.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    }

    private fun stallOutOfFuel(vehicle: IVehicle) {
        when (vehicle) {
            is IBike -> vehicle.state.engineOn = false
            is KartVehicle -> vehicle.kartState.engineOn = false
            is WheeledVehicle -> {
                vehicle.wheeledState.engineOn = false
                vehicle.wheeledState.engineStalled = true
                vehicle.wheeledState.debugEngineStalled = true
                vehicle.wheeledState.engineRpm = 0.0
                vehicle.wheeledState.debugEngineRpm = 0.0
            }
        }
    }
}
