package org.valkyrienskies.skyriders.content

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.VsBodyCreateData
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.bikes.DebugBike
import java.util.concurrent.ConcurrentHashMap

object BikeManager {
    private const val BIKES_KEY = "bikes"
    val DEBUG_BIKE_ID = ResourceLocation(SkyridersMod.MOD_ID, "debug_bike")
    val registeredBikeIds: Set<ResourceLocation> = setOf(DEBUG_BIKE_ID)

    private val bikesByDimension = ConcurrentHashMap<DimensionId, ConcurrentHashMap<BodyId, IBike>>()
    private val inputsByDimension = ConcurrentHashMap<DimensionId, ConcurrentHashMap<BodyId, BikeInput>>()

    fun createBike(bikeId: ResourceLocation, level: ServerLevel, position: Vector3dc): IBike {
        return when (bikeId) {
            DEBUG_BIKE_ID -> createDebugBike(level, position)
            else -> throw IllegalArgumentException("Unknown bike id: $bikeId")
        }
    }

    fun addBike(dimensionId: DimensionId, bike: IBike) {
        bikesByDimension.getOrPut(dimensionId) { ConcurrentHashMap() }[bike.bodyId] = bike
        inputsByDimension.getOrPut(dimensionId) { ConcurrentHashMap() }[bike.bodyId] = BikeInput.EMPTY
    }

    fun removeBike(dimensionId: DimensionId, bodyId: BodyId): IBike? {
        inputsByDimension[dimensionId]?.remove(bodyId)
        return bikesByDimension[dimensionId]?.remove(bodyId)
    }

    fun removeBike(level: ServerLevel, bodyId: BodyId, deleteBody: Boolean = true): IBike? {
        val bike = removeBike(level.dimensionId, bodyId) ?: return null
        if (deleteBody) {
            val shipWorld = requireNotNull(vsApi.getServerShipWorld(level.server)) {
                "Cannot delete bike body before VS server ship world is available"
            }
            shipWorld.allBodies.getById(bodyId)?.let(shipWorld::deleteBody)
        }
        return bike
    }

    fun getBike(dimensionId: DimensionId, bodyId: BodyId): IBike? {
        return bikesByDimension[dimensionId]?.get(bodyId)
    }

    fun getBikes(dimensionId: DimensionId): List<IBike> {
        return bikesByDimension[dimensionId]?.values?.sortedBy(IBike::bodyId) ?: emptyList()
    }

    fun tick(dimensionId: DimensionId) {
        bikesByDimension[dimensionId]?.values?.forEach(IBike::tick)
    }

    fun physTick(physLevel: PhysLevel, dt: Double) {
        bikesByDimension[physLevel.dimension]?.values?.forEach { bike ->
            val body = physLevel.getBodyById(bike.bodyId) ?: return@forEach
            bike.physTick(physLevel, body, getInput(physLevel.dimension, bike.bodyId), dt)
        }
    }

    fun getInput(dimensionId: DimensionId, bodyId: BodyId): BikeInput {
        return inputsByDimension[dimensionId]?.get(bodyId) ?: BikeInput.EMPTY
    }

    fun updateInput(dimensionId: DimensionId, bodyId: BodyId, updater: (BikeInput) -> BikeInput): BikeInput? {
        if (getBike(dimensionId, bodyId) == null) return null

        val inputs = inputsByDimension.getOrPut(dimensionId) { ConcurrentHashMap() }
        return inputs.compute(bodyId) { _, current ->
            updater(current ?: BikeInput.EMPTY).clamped()
        }
    }

    fun save(dimensionId: DimensionId): CompoundTag = CompoundTag().apply {
        val bikes = ListTag()
        bikesByDimension[dimensionId]?.values
            ?.map(IBike::toSaveRecord)
            ?.map(BikeSaveRecord::save)
            ?.forEach(bikes::add)
        put(BIKES_KEY, bikes)
    }

    fun loadRecords(tag: CompoundTag): List<BikeSaveRecord> {
        val list = tag.getList(BIKES_KEY, Tag.TAG_COMPOUND.toInt())
        return (0 until list.size).map { index -> BikeSaveRecord.load(list.getCompound(index)) }
    }

    private fun createDebugBike(level: ServerLevel, position: Vector3dc): IBike {
        val config = BikePhysicsConfig.DEBUG_MOTORCYCLE
        val shipWorld = requireNotNull(vsApi.getServerShipWorld(level.server)) {
            "Cannot create bike before VS server ship world is available"
        }
        val body = shipWorld.createBody(
            VsBodyCreateData(
                dimensionId = level.dimensionId,
                inertiaData = vsCore.newShipInertiaData(
                    Vector3d(0.0, -0.2, 0.0),
                    config.mass,
                    Matrix3d().identity().scale(config.mass)
                ),
                kinematics = vsCore.newBodyKinematics(
                    velocity = Vector3d(),
                    angularVelocity = Vector3d(),
                    position = Vector3d(position),
                    rotation = Quaterniond(),
                    scaling = Vector3d(1.0),
                    positionInModel = Vector3d()
                ),
                collisionShape = vsCore.newCompoundBodyShape(
                    listOf(
                        vsCore.newCompoundBodyShapeChild(
                            shape = vsCore.newBoxBodyShape(config.collisionBoxSize),
                            position = config.collisionBoxOffset
                        )
                    )
                ),
                staticFrictionCoefficient = 0.2,
                dynamicFrictionCoefficient = 0.2,
                restitutionCoefficient = 0.05
            )
        )
        val bike = DebugBike(
            bodyId = body.id,
            boundingBox = AABB.ofSize(
                Vec3(
                    position.x() + config.collisionBoxOffset.x,
                    position.y() + config.collisionBoxOffset.y,
                    position.z() + config.collisionBoxOffset.z
                ),
                config.collisionBoxSize.x,
                config.collisionBoxSize.y,
                config.collisionBoxSize.z
            ),
            level = level,
            config = config
        )
        addBike(level.dimensionId, bike)
        return bike
    }
}
