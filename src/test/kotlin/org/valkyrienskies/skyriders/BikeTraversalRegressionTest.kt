package org.valkyrienskies.skyriders

import org.joml.Vector3d
import org.joml.Quaterniond
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import org.valkyrienskies.core.api.bodies.shape.BoxBodyShapeData
import org.valkyrienskies.core.api.bodies.shape.CompoundBodyShapeData
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.BikePhysicsConfig
import org.valkyrienskies.skyriders.content.BikeRuntimeState
import org.valkyrienskies.skyriders.util.BikePhysicsSolver
import kotlin.math.abs

class BikeTraversalRegressionTest {
    @ParameterizedTest
    @CsvSource("-1", "1")
    fun riderPitchControlTurnsInRequestedDirection(pitch: Double) {
        NativeVehicleWorld().use { world ->
            val config = BikePhysicsConfig.DIRT_BIKE
            world.box(Vector3d(20.0, 1.0, 100.0), Vector3d(0.0, -0.5, 30.0))
            val bike = world.compound(listOf(CompoundBodyShapeData.Child(BoxBodyShapeData(config.collisionBoxSize),
                config.collisionBoxOffset, segmentId = 0)), Vector3d(0.0, 1.5, 0.0), config.mass, false,
                pivot = Vector3d(0.0, -0.2, 0.0))
            val state = BikeRuntimeState(engineOn = true)
            fun tick(input: BikeInput) {
                BikePhysicsSolver.updateBikePhysics(bike.api, world.level, input, config, state, 1.0 / 60.0)
                world.step(1.0 / 60.0)
            }
            repeat(180) { tick(BikeInput.EMPTY) }
            bike.velocity(Vector3d(0.0, 0.0, 6.0))
            state.frontWheelAngularVelocity = 6.0 / config.wheelRadius
            state.rearWheelAngularVelocity = 6.0 / config.wheelRadius
            repeat(20) { tick(BikeInput(throttle = 0.3, pitch = pitch, riderPresent = true)) }
            val forwardY = bike.current.rotation.transform(Vector3d(0.0, 0.0, 1.0)).y
            assertTrue(pitch * forwardY < -0.02, "Pitch control turned the wrong way: input=$pitch forwardY=$forwardY")
        }
    }

    @Test
    fun wallImpactAndPickupDoNotLeaveWheelsBuried() {
        NativeVehicleWorld().use { world ->
            val config = BikePhysicsConfig.DIRT_BIKE
            world.box(Vector3d(20.0, 1.0, 40.0), Vector3d(0.0, -0.5, 0.0))
            world.box(Vector3d(10.0, 5.0, 1.0), Vector3d(0.0, 2.5, 3.0))
            val bike = world.compound(listOf(CompoundBodyShapeData.Child(BoxBodyShapeData(config.collisionBoxSize),
                config.collisionBoxOffset, segmentId = 0)), Vector3d(0.0, 1.5, -5.0), config.mass, false,
                pivot = Vector3d(0.0, -0.2, 0.0))
            val state = BikeRuntimeState(engineOn = true)
            fun tick(input: BikeInput) {
                BikePhysicsSolver.updateBikePhysics(bike.api, world.level, input, config, state, 1.0 / 60.0)
                world.step(1.0 / 60.0)
            }
            repeat(180) { tick(BikeInput.EMPTY) }
            bike.velocity(Vector3d(0.0, 0.0, 20.0))
            state.frontWheelAngularVelocity = 20.0 / config.wheelRadius
            state.rearWheelAngularVelocity = 20.0 / config.wheelRadius
            repeat(120) { tick(BikeInput(throttle = 1.0, riderPresent = true)) }
            bike.native.poseVel = bike.native.poseVel.copy(pos = Vector3d(0.0, 4.0, -5.0),
                rot = Quaterniond(), vel = Vector3d(), omega = Vector3d())
            bike.refresh()
            repeat(300) { tick(BikeInput.EMPTY) }
            assertTrue(state.debugFrontWheelGrounded && state.debugRearWheelGrounded,
                "Bike lost wheel support after wall impact and pickup: ${bike.current.position}")
            assertTrue(bike.current.position.y() > 0.35, "Wheels stayed buried after pickup: ${bike.current.position}")
        }
    }

    @Test
    fun bikeRecoversGroundContactAfterWallNormal() {
        NativeVehicleWorld().use { world ->
            val config = BikePhysicsConfig.DIRT_BIKE
            world.box(Vector3d(20.0, 1.0, 20.0), Vector3d(0.0, -0.5, 0.0))
            val bike = world.compound(listOf(CompoundBodyShapeData.Child(BoxBodyShapeData(config.collisionBoxSize),
                config.collisionBoxOffset, segmentId = 0)), Vector3d(0.0, 3.0, 0.0), config.mass, false,
                pivot = Vector3d(0.0, -0.2, 0.0))
            val state = BikeRuntimeState(engineOn = true)
            state.smoothedGroundNormal.set(0.0, 0.0, -1.0)
            repeat(300) {
                BikePhysicsSolver.updateBikePhysics(bike.api, world.level, BikeInput.EMPTY, config, state, 1.0 / 60.0)
                world.step(1.0 / 60.0)
            }
            assertTrue(state.debugFrontWheelGrounded && state.debugRearWheelGrounded,
                "Wheel support did not recover after landing: position=${bike.current.position} normal=${state.smoothedGroundNormal}")
            assertTrue(bike.current.position.y() > 0.35, "Bike settled on chassis with buried wheels: ${bike.current.position}")
        }
    }

    @ParameterizedTest
    @CsvSource("flat,4", "flat,24", "step,4", "step,12", "step,24", "block,4", "block,12", "block,24",
        "stairs,4", "stairs,12", "stairs,24", "drop,12")
    fun dirtBikeKeepsControlledContact(course: String, speed: Double) {
        NativeVehicleWorld().use { world ->
            val config = BikePhysicsConfig.DIRT_BIKE
            world.box(Vector3d(20.0, 1.0, 300.0), Vector3d(0.0, -0.5, 100.0))
            fun platform(height: Double, start: Double, end: Double) =
                world.box(Vector3d(10.0, height, end - start), Vector3d(0.0, height * 0.5, (start + end) * 0.5))
            when (course) {
                "step" -> platform(1.0, 0.0, 200.0)
                "block" -> platform(1.0, 0.0, 1.0)
                "stairs" -> repeat(6) { platform((it + 1).toDouble(), it * 2.0, 200.0) }
                "drop" -> platform(1.0, -30.0, 0.0)
            }
            val bike = world.compound(listOf(CompoundBodyShapeData.Child(BoxBodyShapeData(config.collisionBoxSize),
                config.collisionBoxOffset, segmentId = 0)), Vector3d(0.0, if (course == "drop") 2.5 else 1.5, -12.0),
                config.mass, false, pivot = Vector3d(0.0, -0.2, 0.0))
            val state = BikeRuntimeState(engineOn = true)
            val dt = 1.0 / 60.0
            fun update(throttle: Double) = BikePhysicsSolver.updateBikePhysics(bike.api, world.level,
                BikeInput(throttle = throttle, riderPresent = true), config, state, dt)
            repeat(180) { update(0.0); world.step(dt) }
            bike.velocity(Vector3d(0.0, 0.0, speed))
            state.frontWheelAngularVelocity = speed / config.wheelRadius
            state.rearWheelAngularVelocity = speed / config.wheelRadius
            var maxUpSpeed = 0.0
            var maxHeight = 0.0
            var maxPitch = 0.0
            var longestAirborne = 0.0
            var airborne = 0.0
            var maxPostLandingUpSpeed = 0.0
            var maxLateAssist = 0.0
            var minWheelHeight = Double.POSITIVE_INFINITY
            repeat(((30.0 / speed + 3.0) / dt).toInt()) { tick ->
                update(1.0)
                world.step(dt)
                val pose = bike.current
                assertTrue(pose.position.isFinite && pose.velocity.isFinite)
                maxUpSpeed = maxOf(maxUpSpeed, pose.velocity.y())
                maxHeight = maxOf(maxHeight, pose.position.y())
                maxPitch = maxOf(maxPitch, abs(pose.rotation.transform(Vector3d(0.0, 0.0, 1.0)).y))
                airborne = if (state.debugFrontWheelGrounded || state.debugRearWheelGrounded) 0.0 else airborne + dt
                longestAirborne = maxOf(longestAirborne, airborne)
                val beyondObstacle = pose.position.z() > if (course == "stairs") 14.0 else 4.0
                if (beyondObstacle) {
                    maxLateAssist = maxOf(maxLateAssist, state.debugStepAssistWork)
                    maxPostLandingUpSpeed = maxOf(maxPostLandingUpSpeed, pose.velocity.y())
                }
                for ((mount, compression) in listOf(config.frontWheelLocalPos to state.frontWheelSuspensionOffset,
                    config.rearWheelLocalPos to state.rearWheelSuspensionOffset)) {
                    val bottom = pose.toWorld.transformPosition(Vector3d(mount).add(0.0,
                        -config.suspensionRestLength - config.wheelRadius + compression, 0.0))
                    val ground = when (course) {
                        "step" -> if (bottom.z >= 0.0) 1.0 else 0.0
                        "block" -> if (bottom.z in 0.0..1.0) 1.0 else 0.0
                        "stairs" -> if (bottom.z >= 0.0) (bottom.z.toInt() / 2 + 1).coerceAtMost(6).toDouble() else 0.0
                        "drop" -> if (bottom.z <= 0.0) 1.0 else 0.0
                        else -> 0.0
                    }
                    minWheelHeight = minOf(minWheelHeight, bottom.y - ground)
                }
                if (System.getenv("SKYRIDERS_NATIVE_TRACE") == "1" && tick % 6 == 0) {
                    println("DIRT $course $speed tick=$tick pos=${pose.position} vel=${pose.velocity} pitch=${pose.rotation} grounded=${state.debugFrontWheelGrounded}/${state.debugRearWheelGrounded} assist=${state.debugStepAssistWork}")
                }
            }
            val result = "course=$course speed=$speed pos=${bike.current.position} up=$maxUpSpeed height=$maxHeight pitch=$maxPitch air=$longestAirborne lateUp=$maxPostLandingUpSpeed lateAssist=$maxLateAssist wheel=$minWheelHeight"
            println(result)
            assertTrue(bike.current.position.z() > 15.0, "Bike got stuck: $result")
            assertTrue(maxHeight < (if (course == "stairs") 7.0 else if (course == "flat") 0.8 else 2.0), "Bike launched: $result")
            assertTrue(maxPostLandingUpSpeed < 1.5, "Bike kept bouncing after obstacle: $result")
        }
    }
}
