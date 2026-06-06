package org.valkyrienskies.skyriders.content

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.util.VehiclePhysicsMath
import kotlin.math.max

object BoostPadHandler {
    private const val BOOST_ACCELERATION = 32.0
    private const val BOOST_TARGET_SPEED = 30.0
    private const val BOOST_FADE_RANGE = 6.0
    private const val MIN_TRIGGER_SPEED = 0.35
    private const val PROBE_STEP = 0.25

    fun physTick(physLevel: PhysLevel, vehicles: Iterable<IVehicle>, dt: Double) {
        vehicles.forEach { vehicle ->
            if (vehicle.level.isClientSide || vehicle.level.dimensionId != physLevel.dimension) return@forEach
            val body = physLevel.getBodyById(vehicle.bodyId) ?: return@forEach
            applyBoostIfOnPad(vehicle, body, dt)
        }
    }

    private fun applyBoostIfOnPad(vehicle: IVehicle, body: PhysVsBody, dt: Double) {
        if (dt <= 0.0 || !isOnBoostPad(vehicle, body)) return

        val velocity = body.kinematics.velocity
        val planarVelocity = Vector3d(velocity.x(), 0.0, velocity.z())
        if (!VehiclePhysicsMath.isFinite(planarVelocity) || planarVelocity.lengthSquared() < MIN_TRIGGER_SPEED * MIN_TRIGGER_SPEED) {
            return
        }

        val direction = planarVelocity.normalize()
        val speed = max(0.0, VehiclePhysicsMath.safeDot(velocity, direction))
        val speedScale = 1.0 - smoothstep(BOOST_TARGET_SPEED, BOOST_TARGET_SPEED + BOOST_FADE_RANGE, speed)
        if (speedScale <= 0.0) return

        val force = direction.mul(vehicle.vehicleDefinition.body.mass * BOOST_ACCELERATION * speedScale)
        VehiclePhysicsMath.safeApplyWorldForce(body, force, body.kinematics.position)
    }

    private fun isOnBoostPad(vehicle: IVehicle, body: PhysVsBody): Boolean {
        val level = vehicle.level
        val probes = wheelProbeLocalPositions(vehicle)
        val maxDrop = boostProbeDrop(vehicle)
        return probes.any { local ->
            val world = body.kinematics.transform.toWorld.transformPosition(Vector3d(local))
            hasBoostPadBelow(level, world, maxDrop)
        }
    }

    private fun wheelProbeLocalPositions(vehicle: IVehicle): List<Vector3d> {
        return when (val behavior = vehicle.vehicleDefinition.behavior) {
            is BikeVehicleBehaviorDefinition -> listOf(
                Vector3d(behavior.physics.frontWheelLocalPos),
                Vector3d(behavior.physics.rearWheelLocalPos)
            )
            is KartVehicleBehaviorDefinition -> behavior.physics.wheelLocalPositions.map(::Vector3d)
        }
    }

    private fun boostProbeDrop(vehicle: IVehicle): Double {
        return when (val behavior = vehicle.vehicleDefinition.behavior) {
            is BikeVehicleBehaviorDefinition ->
                behavior.physics.suspensionRestLength + behavior.physics.suspensionTravel + behavior.physics.wheelRadius + 0.3
            is KartVehicleBehaviorDefinition ->
                behavior.physics.suspensionRestLength + behavior.physics.suspensionTravel + behavior.physics.wheelRadius + 0.3
        }.coerceAtLeast(0.5)
    }

    private fun hasBoostPadBelow(level: Level, worldPos: Vector3d, maxDrop: Double): Boolean {
        var drop = 0.0
        while (drop <= maxDrop) {
            val blockPos = BlockPos.containing(worldPos.x, worldPos.y - drop, worldPos.z)
            if (level.getBlockState(blockPos).`is`(SkyridersMod.BOOST_PAD_BLOCK.get())) {
                return true
            }
            drop += PROBE_STEP
        }
        return false
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        if (edge0 == edge1) return if (value < edge0) 0.0 else 1.0
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
