package org.valkyrienskies.skyriders

import org.joml.Vector3d
import org.joml.Quaterniond
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.valkyrienskies.skyriders.content.VehicleInput
import org.valkyrienskies.skyriders.content.WheeledVehiclePhysicsConfig
import org.valkyrienskies.skyriders.content.WheeledVehicleRuntimeState
import org.valkyrienskies.skyriders.content.BikePhysicsConfig
import org.valkyrienskies.skyriders.content.BikeRuntimeState
import org.valkyrienskies.skyriders.content.BikeInput
import org.valkyrienskies.skyriders.content.KartPhysicsConfig
import org.valkyrienskies.skyriders.content.KartRuntimeState
import org.valkyrienskies.skyriders.content.KartDefinitions
import org.valkyrienskies.skyriders.content.KartVehicleBehaviorDefinition
import org.valkyrienskies.skyriders.content.WheeledVehicleDefinitions
import org.valkyrienskies.skyriders.content.VehicleCollisionBoxDefinition
import org.valkyrienskies.core.api.bodies.shape.CompoundBodyShapeData
import org.valkyrienskies.core.api.bodies.shape.BoxBodyShapeData
import org.valkyrienskies.skyriders.util.BikePhysicsSolver
import org.valkyrienskies.skyriders.util.KartPhysicsSolver
import org.valkyrienskies.skyriders.util.WheeledVehiclePhysicsSolver

class NativeTraversalTest {
    @ParameterizedTest
    @CsvSource("bike,4", "bike,12", "bike,24", "dirt,4", "dirt,12", "dirt,24", "speedster,4", "speedster,12", "speedster,24", "kart,4", "kart,12", "kart,24", "atv,4", "atv,12", "atv,24", "truck,4", "truck,12", "truck,24")
    fun otherVehiclesClearFullBlocks(vehicle: String, speed: Double) {
        val result = driveCar(1.0, speed, vehicle = vehicle)
        assertTrue(result.position.z > 4.0, "$vehicle failed to clear full block: $result")
        assertTrue(result.penetration <= 0.1, "$vehicle penetrated full block: $result")
        assertTrue(result.peakHeight < 3.0, "$vehicle launched off full block: $result")
    }

    @ParameterizedTest
    @CsvSource("bike,stairs", "bike,rampLip", "kart,stairs", "kart,rampLip", "atv,stairs", "atv,rampLip", "truck,stairs", "truck,rampLip")
    fun otherVehiclesTraverseRepeatedStepsAndCrests(vehicle: String, course: String) {
        val result = driveCar(5.5, 24.0, course, vehicle)
        assertTrue(result.position.z > 20.0, "$vehicle failed to finish $course: $result")
        assertTrue(result.penetration <= 0.1, "$vehicle penetrated $course: $result")
        assertTrue(result.velocity.z > 8.0, "$vehicle lost traversal speed on $course: $result")
        assertTrue(result.peakHeight < 15.0, "$vehicle launched excessively on $course: $result")
    }

    @ParameterizedTest
    @CsvSource("dirt,stairs,30", "dirt,stairs,-30", "dirt,stairs,45", "dirt,stairs,-45",
        "speedster,stairs,30", "speedster,stairs,-30", "speedster,stairs,45", "speedster,stairs,-45",
        "dirt,ramp,30", "dirt,ramp,-30", "dirt,ramp,45", "dirt,ramp,-45",
        "speedster,ramp,30", "speedster,ramp,-30", "speedster,ramp,45", "speedster,ramp,-45")
    fun productionVehiclesTraverseDiagonally(vehicle: String, course: String, heading: Double) {
        val height = if (course == "ramp") 5.0 else 5.5
        val result = driveCar(height, 12.0, course, vehicle, heading = heading)
        if (course == "ramp") assertTrue(result.maxAssist == 0.0, "$vehicle mistook a smooth ramp for a step: $result")
        assertTrue(result.position.z > 20.0, "$vehicle caught on diagonal $course: $result")
        assertTrue(result.penetration <= 0.1, "$vehicle penetrated diagonal $course: $result")
        assertTrue(result.peakHeight < height + 2.0, "$vehicle launched on diagonal $course: $result")
        assertTrue(result.slopeAlignment > 0.95, "$vehicle stayed level instead of following diagonal $course: $result")
    }

    @ParameterizedTest
    @CsvSource("wall,2.0", "ceiling,1.0", "engineOff,1.0", "brake,1.0", "handbrake,1.0")
    fun traversalDoesNotActivateWithoutClearanceOrDrivePermission(course: String, height: Double) {
        val result = driveCar(height, 12.0, course)
        assertTrue(result.maxAssist == 0.0, "$course incorrectly enabled step assistance: $result")
    }
    @ParameterizedTest
    @CsvSource("stairs,12", "stairs,24", "ramp,12", "ramp,24", "rampLip,12", "rampLip,24")
    fun carTraversesSlopesAndRepeatedSteps(course: String, speed: Double) {
        val height = if (course == "ramp") 5.0 else 5.5
        val result = driveCar(height, speed, course)
        assertTrue(result.position.z > 20.0, "Car failed to finish $course: $result")
        assertTrue(result.velocity.z > speed * 0.4, "Car lost traversal speed on $course: $result")
        val verticalEnergyFraction = if (course.startsWith("ramp")) 0.25 else 0.2
        val expectedAirborneRise = speed * speed * verticalEnergyFraction / (2.0 * 9.81)
        assertTrue(result.position.y >= height + 0.3, "Car fell below landing after $course: $result")
        assertTrue(result.penetration <= 0.1, "Chassis penetrated $course: $result")
        assertTrue(result.peakHeight <= height + 1.7 + expectedAirborneRise, "$course added excessive launch energy: $result")
    }
    @ParameterizedTest
    @CsvSource("0.5,4", "0.5,12", "0.5,24", "1.0,4", "1.0,12", "1.0,24")
    fun carClearsSteps(height: Double, speed: Double) {
        val result = driveCar(height, speed)
        assertTrue(result.position.z > 4.0, "Car failed to clear step: $result")
        assertTrue(result.position.y in (height + 0.3)..(height + 1.5), "Car did not settle on landing: $result")
        assertTrue(result.penetration <= 0.1, "Chassis penetrated the step: $result")
        assertTrue(result.peakHeight <= height + 1.7, "Step caused an unintended launch: $result")
    }

    @Test
    fun carFlatRoadBaseline() {
        val result = driveCar(0.0, 12.0)
        assertTrue(result.position.z > 10.0, "Car failed to travel across flat ground: $result")
        assertTrue(result.position.y in 0.3..1.5, "Car lost flat-road suspension support: $result")
    }

    @Test
    fun slowCarRemainsStableAfterLeavingStep() {
        val origin = Vector3d(105.5, -57.0, 41.0)
        val result = driveCar(1.0, 4.0, origin = origin, extraSeconds = 12.0)
        assertTrue(result.position.z > 30.0, "Car lost progress after step: $result")
        assertTrue(result.peakHeight < 2.7, "Car launched on flat landing: $result")
    }

    @ParameterizedTest
    @CsvSource("0", "2")
    fun configuredStepForceIsBounded(maxAcceleration: Double) {
        val result = driveCar(1.0, 4.0, forceBudget = WheeledVehiclePhysicsConfig.CAR.mass * maxAcceleration)
        assertTrue(result.maxAssist <= maxAcceleration + 1.0e-6, "Step force exceeded configured budget: $result")
    }

    private data class DriveResult(val position: Vector3d, val velocity: Vector3d, val penetration: Double,
                                  val peakHeight: Double, val maxAssist: Double, val slopeAlignment: Double)

    private fun driveCar(height: Double, speed: Double, course: String = "step", vehicle: String = "car",
                         origin: Vector3d = Vector3d(), extraSeconds: Double = 0.0, forceBudget: Double? = null,
                         heading: Double = 0.0): DriveResult {
        NativeVehicleWorld().use { world ->
            val rotation = Quaterniond().rotateY(Math.toRadians(heading))
            val travel = rotation.transform(Vector3d(0.0, 0.0, 1.0))
            world.box(Vector3d(200.0, 1.0, 240.0), Vector3d(0.0, -0.5, 90.0).add(origin))
            val obstacles = mutableListOf<Pair<NativeVehicleWorld.Body, Vector3d>>()
            fun terrain(size: Vector3d, position: Vector3d, rotation: Quaterniond = Quaterniond()) {
                obstacles.add(world.box(size, Vector3d(position).add(origin), rotation = rotation) to size)
            }
            when (course) {
                "stairs" -> repeat(11) { index ->
                    val stepHeight = (index + 1) * 0.5
                    terrain(Vector3d(180.0, stepHeight, 180.0), Vector3d(0.0, stepHeight * 0.5, index + 90.0))
                }
                "ramp", "rampLip" -> {
                    val angle = Math.toRadians(30.0)
                    val end = 10.0 * kotlin.math.cos(angle)
                    terrain(Vector3d(180.0, 1.0, 10.0), Vector3d(0.0, 2.5 - kotlin.math.cos(angle) * 0.5,
                        end * 0.5 + kotlin.math.sin(angle) * 0.5), Quaterniond().rotateX(-angle))
                    terrain(Vector3d(180.0, height, 180.0), Vector3d(0.0, height * 0.5, end + 90.0))
                }
                else -> if (height > 0.0) terrain(Vector3d(180.0, height, 180.0), Vector3d(0.0, height * 0.5, 90.0))
            }
            if (course == "ceiling") terrain(Vector3d(8.0, 0.5, 20.0), Vector3d(0.0, 2.65, 2.0))
            val config = when (vehicle) {
                "atv" -> WheeledVehiclePhysicsConfig.ATV
                "truck" -> WheeledVehiclePhysicsConfig.TRUCK
                else -> WheeledVehiclePhysicsConfig.CAR
            }.let { if (forceBudget == null) it else it.copy(stepAssistStrength = forceBudget) }
            val bike = if (vehicle == "dirt") BikePhysicsConfig.DIRT_BIKE else BikePhysicsConfig.MOTORCYCLE
            val kart = if (vehicle == "speedster") (KartDefinitions.SPEEDSTER.behavior as KartVehicleBehaviorDefinition).physics
                else KartPhysicsConfig.DEBUG_KART
            val size = when (vehicle) { "bike", "dirt" -> bike.collisionBoxSize; "kart", "speedster" -> kart.collisionBoxSize; else -> config.collisionBoxSize }
            val offset = when (vehicle) { "bike", "dirt" -> bike.collisionBoxOffset; "kart", "speedster" -> kart.collisionBoxOffset; else -> config.collisionBoxOffset }
            val mass = when (vehicle) { "bike", "dirt" -> bike.mass; "kart", "speedster" -> kart.mass; else -> config.mass }
            val boxes = if (vehicle == "truck") WheeledVehicleDefinitions.PICKUP_TRUCK.body.resolvedCollisionBoxes()
                else listOf(VehicleCollisionBoxDefinition(size, offset))
            val car = world.compound(boxes.mapIndexed { index, box ->
                CompoundBodyShapeData.Child(BoxBodyShapeData(box.size), box.offset, box.rotationQuaternion(), segmentId = index)
            }, Vector3d(0.0, 1.5, -12.0).add(origin), mass, false, rotation = rotation, pivot = when (vehicle) {
                "truck" -> WheeledVehicleDefinitions.PICKUP_TRUCK.body.centerOfMassOffset
                "car" -> WheeledVehicleDefinitions.CAR.body.centerOfMassOffset
                "atv" -> WheeledVehicleDefinitions.ATV.body.centerOfMassOffset
                "bike", "dirt" -> Vector3d(0.0, -0.2, 0.0)
                else -> Vector3d()
            })
            val state = WheeledVehicleRuntimeState(engineOn = course != "engineOff")
            val bikeState = BikeRuntimeState(engineOn = true)
            val kartState = KartRuntimeState(engineOn = true)
            val dt = 1.0 / 60.0
            fun update(driving: Boolean) {
                when (vehicle) {
                    "bike", "dirt" -> BikePhysicsSolver.updateBikePhysics(car.api, world.level,
                        BikeInput(throttle = if (driving) 1.0 else 0.0, riderPresent = true), bike, bikeState, dt)
                    "kart", "speedster" -> KartPhysicsSolver.updateKartPhysics(car.api, world.level,
                        VehicleInput(throttle = if (driving) 1.0 else 0.0, riderPresent = true), kart, kartState, dt)
                    else -> WheeledVehiclePhysicsSolver.updatePhysics(car.api, world.level,
                        VehicleInput(throttle = if (driving && state.engineOn) 1.0 else 0.0,
                            brake = if (course == "brake") 1.0 else 0.0,
                            handbrake = if (course == "handbrake") 1.0 else 0.0, riderPresent = true), config, state, dt, boxes)
                }
            }
            repeat(120) {
                update(false)
                world.step(dt)
            }
            car.velocity(Vector3d(travel).mul(speed))
            state.transmissionGear = config.transmission.forwardGears.indexOfFirst { it.maxSpeed >= speed }
                .let { if (it < 0) config.transmission.forwardGears.size else it + 1 }
            config.axles.forEach { axle ->
                for (side in listOf("left", "right")) {
                    state.wheelAngularVelocityById["${axle.id}_$side"] = speed / axle.wheelRadius
                    state.visualWheelAngularVelocityById["${axle.id}_$side"] = speed / axle.wheelRadius
                }
            }
            val entryGear = config.transmission.forwardGears[state.transmissionGear - 1]
            state.engineRpm = entryGear.gearRatio?.let { ratio ->
                speed / config.axles.first().wheelRadius * 60.0 / (2.0 * Math.PI) * ratio * config.transmission.finalDriveRatio
            } ?: (speed / entryGear.maxSpeed * config.engine.redlineRpm)
            bikeState.frontWheelAngularVelocity = speed / bike.wheelRadius
            bikeState.rearWheelAngularVelocity = speed / bike.wheelRadius
            kartState.frontWheelAngularVelocity = speed / kart.wheelRadius
            kartState.rearWheelAngularVelocity = speed / kart.wheelRadius
            kart.transmission?.let { transmission ->
                kartState.transmissionGear = transmission.forwardGears.indexOfFirst { it.maxSpeed >= speed }
                    .let { if (it < 0) transmission.forwardGears.size else it + 1 }
            }
            var penetration = 0.0
            var peakHeight = 0.0
            var maxAssist = 0.0
            var slopeAlignment = 0.0
            var slopeSamples = 0
            repeat((((if (course == "step") 24.0 else 45.0) / (speed * travel.z) + 1.0 + extraSeconds) / dt).toInt()) {
                update(true)
                maxAssist = maxOf(maxAssist, state.debugStepAssistWork, bikeState.debugStepAssistWork, kartState.debugStepAssistWork)
                world.step(dt)
                assertTrue(car.current.position.isFinite, "Native simulation became non-finite at tick $it")
                if (System.getenv("SKYRIDERS_NATIVE_TRACE") == "1" && it % 6 == 0) {
                    println("trace vehicle=$vehicle course=$course height=$height speed=$speed tick=$it pos=${car.current.position} vel=${car.current.velocity} rot=${car.current.rotation} grounded=${state.debugGroundedWheels} step=${state.debugStepAssistWork} rpm=${state.engineRpm} gear=${state.transmissionGear}")
                }
                peakHeight = maxOf(peakHeight, car.current.position.y() - origin.y)
                if ((course == "stairs" || course.startsWith("ramp")) && car.current.position.z() - origin.z in 2.0..7.0) {
                    val normal = Vector3d(0.0, 1.0, if (course == "stairs") -0.5 else -kotlin.math.tan(Math.toRadians(30.0))).normalize()
                    slopeAlignment += car.current.rotation.transform(Vector3d(0.0, 1.0, 0.0)).dot(normal)
                    slopeSamples++
                }
                for (box in boxes) for (x in listOf(-0.5, 0.5)) for (y in listOf(-0.5, 0.5)) for (z in listOf(-0.5, 0.5)) {
                    val corner = box.rotationQuaternion().transform(Vector3d(box.size).mul(x, y, z)).add(box.offset)
                    car.current.toWorld.transformPosition(corner)
                    for ((terrain, size) in obstacles) {
                        val local = terrain.current.toModel.transformPosition(corner, Vector3d())
                        val depth = minOf(size.x * 0.5 - kotlin.math.abs(local.x), size.y * 0.5 - kotlin.math.abs(local.y),
                            size.z * 0.5 - kotlin.math.abs(local.z))
                        penetration = maxOf(penetration, depth)
                    }
                }
            }
            val result = DriveResult(Vector3d(car.current.position).sub(origin), Vector3d(car.current.velocity), penetration,
                peakHeight, maxAssist, if (slopeSamples > 0) slopeAlignment / slopeSamples else 0.0)
            println("Native $vehicle course=$course height=$height speed=$speed result=$result velocity=${car.current.velocity} queries=${world.queryCount}")
            return result
        }
    }
}
