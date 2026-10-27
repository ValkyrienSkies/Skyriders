package org.valkyrienskies.skyriders

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.valkyrienskies.core.api.bodies.shape.BoxBodyShapeData
import org.valkyrienskies.core.api.bodies.shape.CompoundBodyShapeData
import org.valkyrienskies.skyriders.content.VehicleInput
import org.valkyrienskies.skyriders.content.WheeledVehicleDefinitions
import org.valkyrienskies.skyriders.content.WheeledVehiclePhysicsConfig
import org.valkyrienskies.skyriders.content.WheeledVehicleRuntimeState
import org.valkyrienskies.skyriders.util.WheeledVehiclePhysicsSolver

class NativeTraversalEdgeTest {
    private val config = WheeledVehiclePhysicsConfig.CAR
    private val dt = 1.0 / 60.0

    @ParameterizedTest
    @CsvSource("4,0,0", "12,0,0", "24,0,0", "12,10,0", "24,10,0", "12,0,0.05")
    fun reverseAndMovingPlatforms(speed: Double, platformSpeed: Double, platformYaw: Double) {
        NativeVehicleWorld().use { world ->
            val reverse = platformSpeed == 0.0 && platformYaw == 0.0
            val sign = if (reverse) -1.0 else 1.0
            val platformMass = 1.0e8
            val platform = world.compound(listOf(
                CompoundBodyShapeData.Child(BoxBodyShapeData(Vector3d(60.0, 1.0, 200.0)), Vector3d(0.0, -0.5, 0.0), segmentId = 0),
                CompoundBodyShapeData.Child(BoxBodyShapeData(Vector3d(40.0, 1.0, 80.0)), Vector3d(0.0, 0.5, sign * 40.0), segmentId = 1)
            ), Vector3d(), platformMass)
            val car = world.compound(listOf(CompoundBodyShapeData.Child(BoxBodyShapeData(config.collisionBoxSize),
                config.collisionBoxOffset, segmentId = 0)), Vector3d(0.0, 1.5, -sign * 12.0), config.mass, false,
                pivot = WheeledVehicleDefinitions.CAR.body.centerOfMassOffset)
            val state = WheeledVehicleRuntimeState(engineOn = true)
            fun update(throttle: Double) = WheeledVehiclePhysicsSolver.updatePhysics(car.api, world.level,
                VehicleInput(throttle = throttle, riderPresent = true), config, state, dt)
            repeat(120) { update(0.0); world.step(dt) }
            platform.native.isStatic = false
            platform.velocity(Vector3d(0.0, 0.0, platformSpeed))
            platform.angularVelocity(Vector3d(0.0, platformYaw, 0.0))
            val pointVelocity = Vector3d(0.0, platformYaw, 0.0).cross(car.current.position).add(0.0, 0.0, platformSpeed)
            car.velocity(pointVelocity.add(0.0, 0.0, sign * speed))
            state.transmissionGear = if (reverse) -1 else config.transmission.forwardGears.indexOfFirst { it.maxSpeed >= speed } + 1
            config.axles.forEach { axle ->
                for (side in listOf("left", "right")) {
                    state.wheelAngularVelocityById["${axle.id}_$side"] = sign * speed / axle.wheelRadius
                    state.visualWheelAngularVelocityById["${axle.id}_$side"] = sign * speed / axle.wheelRadius
                }
            }
            val gear = config.transmission.forwardGears[maxOf(0, state.transmissionGear - 1)]
            val ratio = if (reverse) config.transmission.reverseGearRatio else gear.gearRatio
            state.engineRpm = ratio?.let { ratio -> speed / config.axles.first().wheelRadius *
                60.0 / (2.0 * Math.PI) * ratio * config.transmission.finalDriveRatio }
                ?: (speed / gear.maxSpeed * config.engine.redlineRpm)
            var assisted = false
            var maxHeight = 0.0
            repeat(((24.0 / speed + 1.0) / dt).toInt()) {
                platform.api.applyWorldForce(Vector3d(0.0, platformMass * 9.81, 0.0), platform.current.position)
                update(if (reverse && config.transmission.automatic) -0.5 else 0.5)
                assisted = assisted || state.debugStepAssistWork > 0.0
                world.step(dt)
                val position = platform.current.toModel.transformPosition(car.current.position, Vector3d())
                maxHeight = maxOf(maxHeight, position.y)
                assertTrue(position.isFinite)
            }
            val position = platform.current.toModel.transformPosition(car.current.position, Vector3d())
            println("Native moving platform speed=$speed platform=$platformSpeed yaw=$platformYaw reverse=$reverse position=$position peak=$maxHeight")
            assertTrue(assisted, "Course did not exercise step traversal")
            assertTrue(position.z * sign > 4.0, "Vehicle failed to cross step: $position")
            assertTrue(position.y > 1.3, "Vehicle passed below platform step: $position")
            assertTrue(maxHeight < 2.7, "Moving support launched vehicle: $maxHeight")
        }
    }

    @Test
    fun airborneVehicleDoesNotAcquireStepSupport() {
        NativeVehicleWorld().use { world ->
            world.box(Vector3d(20.0, 1.0, 100.0), Vector3d(0.0, 0.5, 40.0))
            val car = world.box(config.collisionBoxSize, Vector3d(0.0, 10.0, -10.0), config.mass, false,
                offset = config.collisionBoxOffset)
            val state = WheeledVehicleRuntimeState(engineOn = true)
            car.velocity(Vector3d(0.0, 0.0, 24.0))
            repeat(45) {
                WheeledVehiclePhysicsSolver.updatePhysics(car.api, world.level,
                    VehicleInput(throttle = 0.5, riderPresent = true), config, state, dt)
                assertEquals(0.0, state.debugStepAssistWork, "Airborne traversal created support")
                world.step(dt)
            }
        }
    }
}
