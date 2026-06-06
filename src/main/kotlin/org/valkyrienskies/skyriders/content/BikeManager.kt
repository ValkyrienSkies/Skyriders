package org.valkyrienskies.skyriders.content

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
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
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.vsCore
import java.util.concurrent.ConcurrentHashMap

object BikeManager {
    private const val BIKES_KEY = "bikes"
    val registeredBikeIds: Set<ResourceLocation>
        get() = BikeDefinitions.ids

    private val serverBikesByDimension = ConcurrentHashMap<DimensionId, ConcurrentHashMap<BodyId, IBike>>()
    private val clientBikesByDimension = ConcurrentHashMap<DimensionId, ConcurrentHashMap<BodyId, IBike>>()
    private val inputsByDimension = ConcurrentHashMap<DimensionId, ConcurrentHashMap<BodyId, BikeInput>>()

    fun createBike(bikeId: ResourceLocation, level: ServerLevel, position: Vector3dc): IBike {
        val definition = BikeDefinitions.get(bikeId)
            ?: throw IllegalArgumentException("Unknown bike id: $bikeId")
        val bike = createBike(definition, level, position)
        BikeLifecycle.saveLevel(level)
        BikeLifecycle.syncLevel(level)
        return bike
    }

    fun addBike(dimensionId: DimensionId, bike: IBike) {
        serverBikesByDimension.getOrPut(dimensionId) { ConcurrentHashMap() }[bike.bodyId] = bike
        inputsByDimension.getOrPut(dimensionId) { ConcurrentHashMap() }[bike.bodyId] = BikeInput.EMPTY
    }

    fun replaceBikes(dimensionId: DimensionId, bikes: Iterable<IBike>) {
        serverBikesByDimension[dimensionId] = ConcurrentHashMap<BodyId, IBike>().apply {
            bikes.forEach { bike -> put(bike.bodyId, bike) }
        }
        inputsByDimension[dimensionId] = ConcurrentHashMap<BodyId, BikeInput>().apply {
            serverBikesByDimension[dimensionId]?.keys?.forEach { bodyId -> put(bodyId, BikeInput.EMPTY) }
        }
    }

    fun clearBikes(dimensionId: DimensionId) {
        serverBikesByDimension.remove(dimensionId)
        inputsByDimension.remove(dimensionId)
    }

    fun removeBike(dimensionId: DimensionId, bodyId: BodyId): IBike? {
        inputsByDimension[dimensionId]?.remove(bodyId)
        return serverBikesByDimension[dimensionId]?.remove(bodyId)
    }

    fun removeBike(level: ServerLevel, bodyId: BodyId, deleteBody: Boolean = true): IBike? {
        val bike = removeBike(level.dimensionId, bodyId) ?: return null
        if (deleteBody) {
            val shipWorld = requireNotNull(vsApi.getServerShipWorld(level.server)) {
                "Cannot delete bike body before VS server ship world is available"
            }
            shipWorld.allBodies.getById(bodyId)?.let(shipWorld::deleteBody)
        }
        BikeLifecycle.saveLevel(level)
        BikeLifecycle.syncLevel(level)
        return bike
    }

    fun getBike(dimensionId: DimensionId, bodyId: BodyId): IBike? {
        return serverBikesByDimension[dimensionId]?.get(bodyId)
    }

    fun getBike(level: Level, bodyId: BodyId): IBike? {
        return bikeMap(level)[level.dimensionId]?.get(bodyId)
    }

    fun getBikes(dimensionId: DimensionId): List<IBike> {
        return serverBikesByDimension[dimensionId]?.values?.sortedBy(IBike::bodyId) ?: emptyList()
    }

    fun getBikes(level: Level): List<IBike> {
        return bikeMap(level)[level.dimensionId]?.values?.sortedBy(IBike::bodyId) ?: emptyList()
    }

    fun tick(dimensionId: DimensionId) {
        serverBikesByDimension[dimensionId]?.values?.forEach(IBike::tick)
    }

    fun physTick(physLevel: PhysLevel, dt: Double) {
        serverBikesByDimension[physLevel.dimension]?.values?.forEach { bike ->
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

    fun toggleEngine(level: ServerLevel, bodyId: BodyId): Boolean? {
        val bike = getBike(level.dimensionId, bodyId) ?: return null
        bike.state.engineOn = !bike.state.engineOn
        if (!bike.state.engineOn) {
            updateInput(level.dimensionId, bodyId) { BikeInput.EMPTY }
        }
        BikeLifecycle.saveLevel(level)
        BikeLifecycle.syncLevel(level)
        return bike.state.engineOn
    }

    fun save(dimensionId: DimensionId): CompoundTag = CompoundTag().apply {
        val bikes = ListTag()
        serverBikesByDimension[dimensionId]?.values
            ?.map(IBike::toSaveRecord)
            ?.map(BikeSaveRecord::save)
            ?.forEach(bikes::add)
        put(BIKES_KEY, bikes)
    }

    fun loadRecords(tag: CompoundTag): List<BikeSaveRecord> {
        val list = tag.getList(BIKES_KEY, Tag.TAG_COMPOUND.toInt())
        return (0 until list.size).map { index -> BikeSaveRecord.load(list.getCompound(index)) }
    }

    fun restoreBike(level: Level, record: BikeSaveRecord): IBike? {
        val bikeId = BikeDefinitions.resolveSavedId(record.bikeType) ?: return null
        val definition = BikeDefinitions.get(bikeId) ?: return null
        val bike = restoreBike(level, record, definition) ?: return null

        addBike(level, bike)
        return bike
    }

    fun restoreBikes(level: Level, records: Iterable<BikeSaveRecord>): Int {
        clearBikes(level)
        var restoredCount = 0
        records.forEach { record ->
            if (restoreBike(level, record) != null) restoredCount++
        }
        return restoredCount
    }

    fun applyVisualState(
        level: Level,
        bodyId: BodyId,
        engineOn: Boolean,
        visualLeanRad: Double,
        visualSteerRad: Double,
        frontWheelSpin: Double,
        rearWheelSpin: Double,
        frontWheelAngularVelocity: Double,
        rearWheelAngularVelocity: Double,
        frontWheelSuspensionOffset: Double,
        rearWheelSuspensionOffset: Double
    ) {
        val bike = getBike(level, bodyId) ?: return
        bike.state.engineOn = engineOn
        bike.state.visualLeanRad = visualLeanRad
        bike.state.visualSteerRad = visualSteerRad
        bike.state.frontWheelSpin = frontWheelSpin
        bike.state.rearWheelSpin = rearWheelSpin
        bike.state.frontWheelAngularVelocity = frontWheelAngularVelocity
        bike.state.rearWheelAngularVelocity = rearWheelAngularVelocity
        bike.state.frontWheelSuspensionOffset = frontWheelSuspensionOffset
        bike.state.rearWheelSuspensionOffset = rearWheelSuspensionOffset
    }

    private fun addBike(level: Level, bike: IBike) {
        if (!level.isClientSide) {
            addBike(level.dimensionId, bike)
            return
        }

        clientBikesByDimension.getOrPut(level.dimensionId) { ConcurrentHashMap() }[bike.bodyId] = bike
    }

    private fun clearBikes(level: Level) {
        if (!level.isClientSide) {
            clearBikes(level.dimensionId)
            return
        }

        clientBikesByDimension.remove(level.dimensionId)
    }

    private fun bikeMap(level: Level): ConcurrentHashMap<DimensionId, ConcurrentHashMap<BodyId, IBike>> {
        return if (level.isClientSide) clientBikesByDimension else serverBikesByDimension
    }

    private fun createBike(definition: BikeDefinition, level: ServerLevel, position: Vector3dc): IBike {
        val config = definition.config
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
        val bike = definition.factory.create(
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
            definition = definition,
            state = BikeRuntimeState()
        )
        addBike(level.dimensionId, bike)
        return bike
    }

    private fun restoreBike(level: Level, record: BikeSaveRecord, definition: BikeDefinition): IBike? {
        val config = definition.config
        val body = level.shipWorld?.allBodies?.getById(record.bodyId) ?: return null
        val position = body.kinematics.position
        return definition.factory.create(
            bodyId = record.bodyId,
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
            definition = definition,
            state = BikeRuntimeState(
                engineOn = record.engineOn,
                visualLeanRad = record.visualLeanRad,
                frontWheelSpin = record.frontWheelSpin,
                rearWheelSpin = record.rearWheelSpin,
                frontWheelAngularVelocity = record.frontWheelAngularVelocity,
                rearWheelAngularVelocity = record.rearWheelAngularVelocity
            )
        )
    }
}
