package org.valkyrienskies.skyriders.content

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.registries.ForgeRegistries
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.bodies.VsBodyCreateData
import org.valkyrienskies.core.api.bodies.ServerBaseVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.dimensionId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.skyriders.SkyridersMod
import org.valkyrienskies.skyriders.content.vehicles.KartVehicle
import org.valkyrienskies.skyriders.content.vehicles.WheeledVehicle
import org.valkyrienskies.skyriders.content.vehicles.wheeledRuntimeStateFromTag
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic vehicle facade over the current bike-backed implementation.
 *
 * Keep behavior-specific APIs on BikeManager until the generic layer owns
 * enough runtime and networking state to replace them directly.
 */
object VehicleManager {
    private const val VEHICLES_KEY = "vehicles"
    private const val LEGACY_BIKES_KEY = "bikes"
    private const val HORN_COOLDOWN_TICKS = 10L
    private const val MANUAL_SHIFT_CLUTCH_THRESHOLD = 0.75
    private val serverVehiclesByDimension = ConcurrentHashMap<DimensionId, ConcurrentHashMap<BodyId, IVehicle>>()
    private val clientVehiclesByDimension = ConcurrentHashMap<DimensionId, ConcurrentHashMap<BodyId, IVehicle>>()
    private val inputsByDimension = ConcurrentHashMap<DimensionId, ConcurrentHashMap<BodyId, VehicleInput>>()
    private val lastHornTickByVehicle = ConcurrentHashMap<Pair<DimensionId, BodyId>, Long>()

    val registeredVehicleIds: Set<ResourceLocation>
        get() = VehicleDefinitions.ids

    fun getDefinition(vehicleId: ResourceLocation): VehicleDefinition? {
        return VehicleDefinitions.get(vehicleId)
    }

    fun createVehicle(vehicleId: ResourceLocation, level: ServerLevel, position: Vector3dc): IVehicle {
        if (BikeDefinitions.get(vehicleId) != null) {
            return BikeManager.createBike(vehicleId, level, position)
        }
        val definition = getDefinition(vehicleId)
            ?: throw IllegalArgumentException("Unknown vehicle id: $vehicleId")
        val vehicle = createVehicle(definition, level, position)
        BikeLifecycle.saveLevel(level)
        BikeLifecycle.syncLevel(level)
        return vehicle
    }

    fun addVehicle(dimensionId: DimensionId, vehicle: IVehicle) {
        serverVehiclesByDimension.getOrPut(dimensionId) { ConcurrentHashMap() }[vehicle.bodyId] = vehicle
        inputsByDimension.getOrPut(dimensionId) { ConcurrentHashMap() }[vehicle.bodyId] = VehicleInput.EMPTY
    }

    fun clearVehicles(dimensionId: DimensionId) {
        serverVehiclesByDimension.remove(dimensionId)
        inputsByDimension.remove(dimensionId)
    }

    fun clearVehicles(level: Level) {
        if (!level.isClientSide) {
            clearVehicles(level.dimensionId)
            return
        }

        clientVehiclesByDimension.remove(level.dimensionId)
    }

    fun replaceVehicles(dimensionId: DimensionId, vehicles: Iterable<IVehicle>) {
        serverVehiclesByDimension[dimensionId] = ConcurrentHashMap<BodyId, IVehicle>().apply {
            vehicles.forEach { vehicle -> put(vehicle.bodyId, vehicle) }
        }
        inputsByDimension[dimensionId] = ConcurrentHashMap<BodyId, VehicleInput>().apply {
            serverVehiclesByDimension[dimensionId]?.keys?.forEach { bodyId -> put(bodyId, VehicleInput.EMPTY) }
        }
    }

    fun restoreVehicle(level: Level, record: VehicleSaveRecord): IVehicle? {
        if (BikeDefinitions.resolveSavedId(record.vehicleType)?.let(BikeDefinitions::get) != null) {
            return BikeManager.restoreBike(level, record.toBikeSaveRecord())
        }
        val vehicleId = VehicleDefinitions.resolveSavedId(record.vehicleType) ?: return null
        val definition = getDefinition(vehicleId) ?: return null
        val body = level.shipWorld?.allBodies?.getById(record.bodyId) ?: return null
        val vehicle = createVehicleFromBody(level, definition, body.id, record)
        addVehicle(level, vehicle)
        return vehicle
    }

    fun restoreVehicles(level: Level, records: Iterable<VehicleSaveRecord>): Int {
        val recordList = records.toList()
        val bikeRecords = recordList.filter { record ->
            BikeDefinitions.resolveSavedId(record.vehicleType)?.let(BikeDefinitions::get) != null
        }
        val vehicleRecords = recordList - bikeRecords.toSet()

        val restoredBikes = BikeManager.restoreBikes(level, bikeRecords.map(VehicleSaveRecord::toBikeSaveRecord))
        clearVehicles(level)
        var restoredCount = 0
        vehicleRecords.forEach { record ->
            if (restoreVehicle(level, record) != null) restoredCount++
        }
        return restoredBikes + restoredCount
    }

    private fun createVehicle(definition: VehicleDefinition, level: ServerLevel, position: Vector3dc): IVehicle {
        val shipWorld = requireNotNull(vsApi.getServerShipWorld(level.server)) {
            "Cannot create vehicle before VS server ship world is available"
        }
        val bodyDef = definition.body
        val body = shipWorld.createBody(
            VsBodyCreateData(
                dimensionId = level.dimensionId,
                inertiaData = vsCore.newShipInertiaData(
                    Vector3d(bodyDef.centerOfMassOffset),
                    bodyDef.mass,
                    Matrix3d().identity().scale(bodyDef.mass)
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
                    bodyDef.resolvedCollisionBoxes().map { box ->
                        vsCore.newCompoundBodyShapeChild(
                            shape = vsCore.newBoxBodyShape(box.size),
                            position = box.offset,
                            rotation = box.rotationQuaternion()
                        )
                    }
                ),
                staticFrictionCoefficient = 0.25,
                dynamicFrictionCoefficient = 0.25,
                restitutionCoefficient = 0.04
            )
        )
        val record = VehicleSaveRecord(
            bodyId = body.id,
            vehicleType = definition.id.toString(),
            engineOn = false,
            fuelAmount = VehicleFuel.initialAmount(definition, Double.NaN),
            partStates = definition.initialPartStates()
        )
        val vehicle = createVehicleFromBody(level, definition, body.id, record)
        addVehicle(level.dimensionId, vehicle)
        return vehicle
    }

    private fun createVehicleFromBody(
        level: Level,
        definition: VehicleDefinition,
        bodyId: BodyId,
        record: VehicleSaveRecord
    ): IVehicle {
        return when (definition.behavior) {
            is KartVehicleBehaviorDefinition -> KartVehicle(
                bodyId = bodyId,
                level = level,
                vehicleDefinition = definition,
                kartState = KartRuntimeState(
                    engineOn = record.engineOn,
                    fuelAmount = VehicleFuel.initialAmount(definition, record.fuelAmount),
                    raceParticipant = record.raceParticipant,
                    raceColorId = record.raceColorId,
                    transmissionGear = record.behaviorTag.getInt("transmission_gear").takeIf { it != 0 } ?: 1,
                    frontWheelSpin = record.behaviorTag.getDouble("front_wheel_spin"),
                    rearWheelSpin = record.behaviorTag.getDouble("rear_wheel_spin"),
                    frontWheelAngularVelocity = record.behaviorTag.getDouble("front_wheel_angular_velocity"),
                    rearWheelAngularVelocity = record.behaviorTag.getDouble("rear_wheel_angular_velocity"),
                    partStates = definition.initialPartStates(record.partStates)
                )
            )
            is WheeledVehicleBehaviorDefinition -> WheeledVehicle(
                bodyId = bodyId,
                level = level,
                vehicleDefinition = definition,
                wheeledState = wheeledRuntimeStateFromTag(
                    definition,
                    record.behaviorTag,
                    record.engineOn,
                    record.fuelAmount,
                    record.raceParticipant,
                    record.raceColorId,
                    record.partStates
                )
            )
            is BikeVehicleBehaviorDefinition -> throw IllegalArgumentException("Bike vehicles are created through BikeManager")
        }
    }

    fun addVehicle(level: Level, vehicle: IVehicle) {
        if (!level.isClientSide) {
            addVehicle(level.dimensionId, vehicle)
            return
        }

        clientVehiclesByDimension.getOrPut(level.dimensionId) { ConcurrentHashMap() }[vehicle.bodyId] = vehicle
    }

    @Deprecated("Use createVehicle", ReplaceWith("createVehicle(vehicleId, level, position)"))
    fun createBikeBackedVehicle(vehicleId: ResourceLocation, level: ServerLevel, position: Vector3dc): IVehicle {
        return BikeManager.createBike(vehicleId, level, position)
    }

    fun getVehicle(dimensionId: DimensionId, bodyId: BodyId): IVehicle? {
        return BikeManager.getBike(dimensionId, bodyId) ?: serverVehiclesByDimension[dimensionId]?.get(bodyId)
    }

    fun getVehicle(level: Level, bodyId: BodyId): IVehicle? {
        return BikeManager.getBike(level, bodyId) ?: vehicleMap(level)[level.dimensionId]?.get(bodyId)
    }

    fun getVehicles(dimensionId: DimensionId): List<IVehicle> {
        return BikeManager.getBikes(dimensionId) + (serverVehiclesByDimension[dimensionId]?.values?.sortedBy(IVehicle::bodyId) ?: emptyList())
    }

    fun getVehicles(level: Level): List<IVehicle> {
        return BikeManager.getBikes(level) + (vehicleMap(level)[level.dimensionId]?.values?.sortedBy(IVehicle::bodyId) ?: emptyList())
    }

    fun getInput(dimensionId: DimensionId, bodyId: BodyId): VehicleInput {
        return BikeManager.getBike(dimensionId, bodyId)
            ?.let { BikeManager.getInput(dimensionId, bodyId).toVehicleInput() }
            ?: inputsByDimension[dimensionId]?.get(bodyId)
            ?: VehicleInput.EMPTY
    }

    fun updateInput(
        dimensionId: DimensionId,
        bodyId: BodyId,
        updater: (VehicleInput) -> VehicleInput
    ): VehicleInput? {
        if (BikeManager.getBike(dimensionId, bodyId) != null) {
            return BikeManager.updateInput(dimensionId, bodyId) { current ->
                updater(current.toVehicleInput()).toBikeInput()
            }?.toVehicleInput()
        }
        if (serverVehiclesByDimension[dimensionId]?.containsKey(bodyId) != true) return null
        val inputs = inputsByDimension.getOrPut(dimensionId) { ConcurrentHashMap() }
        return inputs.compute(bodyId) { _, current ->
            updater(current ?: VehicleInput.EMPTY).clamped()
        }
    }

    fun clearInput(dimensionId: DimensionId, bodyId: BodyId): VehicleInput? {
        return updateInput(dimensionId, bodyId) { VehicleInput.EMPTY }
    }

    fun teleportVehicle(level: ServerLevel, bodyId: BodyId, position: Vector3dc): IVehicle? {
        val vehicle = getVehicle(level.dimensionId, bodyId) ?: return null
        val body = level.shipWorld?.allBodies?.getById(bodyId) as? ServerBaseVsBody
            ?: throw IllegalStateException("Vehicle $bodyId is bound to a missing VS body.")
        body.isStatic = true
        body.setPosition(position)
        body.isStatic = false
        clearInput(level.dimensionId, bodyId)
        BikeLifecycle.saveLevel(level)
        BikeLifecycle.syncLevel(level)
        return vehicle
    }

    fun toggleEngine(level: ServerLevel, bodyId: BodyId): Boolean? {
        val bike = BikeManager.getBike(level.dimensionId, bodyId)
        if (bike != null) {
            return BikeManager.toggleEngine(level, bodyId)
        }
        val vehicle = serverVehiclesByDimension[level.dimensionId]?.get(bodyId) ?: return null
        val enabled = !vehicle.vehicleState.engineOn
        if (enabled && (!VehicleFuel.canStart(vehicle) || !VehicleDamage.canEngineStart(vehicle))) {
            VehicleEngineSounds.playStartFail(level, vehicle)
            return false
        }
        when (vehicle) {
            is KartVehicle -> vehicle.kartState.engineOn = enabled
            is WheeledVehicle -> {
                vehicle.wheeledState.engineOn = enabled
                vehicle.wheeledState.engineStalled = false
                vehicle.wheeledState.debugEngineStalled = false
                if (enabled) {
                    val behavior = vehicle.vehicleDefinition.behavior as? WheeledVehicleBehaviorDefinition
                    val idleRpm = behavior?.physics?.engine?.idleRpm ?: 850.0
                    vehicle.wheeledState.engineRpm = idleRpm
                    vehicle.wheeledState.debugEngineRpm = idleRpm
                    vehicle.wheeledState.clutchEngagement = 0.0
                    vehicle.wheeledState.debugClutchEngagement = 0.0
                }
            }
        }
        if (!enabled) {
            clearInput(level.dimensionId, bodyId)
        }
        BikeLifecycle.saveLevel(level)
        BikeLifecycle.syncLevel(level)
        return enabled
    }

    fun applyControlAction(level: ServerLevel, bodyId: BodyId, action: ResourceLocation): Boolean {
        val vehicle = getVehicle(level.dimensionId, bodyId) ?: return false
        if (action == VehicleControlActions.HORN) {
            return playHorn(level, vehicle)
        }
        var changed = false
        when (vehicle) {
            is KartVehicle -> {
                val behavior = vehicle.vehicleDefinition.behavior as? KartVehicleBehaviorDefinition ?: return false
                val transmission = behavior.physics.transmission ?: return false
                when (action) {
                    VehicleControlActions.GEAR_UP -> {
                        if (!transmission.automatic) {
                            val maxGear = transmission.forwardGears.size
                            vehicle.kartState.transmissionGear = (vehicle.kartState.transmissionGear + 1).coerceAtMost(maxGear)
                            vehicle.kartState.transmissionShiftCooldown = transmission.shiftCooldownSeconds
                            changed = true
                        }
                    }
                    VehicleControlActions.GEAR_DOWN -> {
                        if (!transmission.automatic) {
                            vehicle.kartState.transmissionGear = (vehicle.kartState.transmissionGear - 1).coerceAtLeast(-1)
                            vehicle.kartState.transmissionShiftCooldown = transmission.shiftCooldownSeconds
                            changed = true
                        }
                    }
                    else -> return false
                }
            }
            is WheeledVehicle -> {
                val behavior = vehicle.vehicleDefinition.behavior as? WheeledVehicleBehaviorDefinition ?: return false
                when (action) {
                    VehicleControlActions.TOGGLE_PARKING_BRAKE -> {
                        vehicle.wheeledState.parkingBrakeEngaged = !vehicle.wheeledState.parkingBrakeEngaged
                        changed = true
                    }
                    VehicleControlActions.GEAR_UP -> {
                        if (!behavior.physics.transmission.automatic &&
                            canShiftManualTransmission(level, bodyId, behavior.physics.transmission)
                        ) {
                            val maxGear = behavior.physics.transmission.forwardGears.size
                            vehicle.wheeledState.transmissionGear = (vehicle.wheeledState.transmissionGear + 1).coerceAtMost(maxGear)
                            vehicle.wheeledState.transmissionShiftCooldown = behavior.physics.transmission.shiftCooldownSeconds
                            changed = true
                        }
                    }
                    VehicleControlActions.GEAR_DOWN -> {
                        if (!behavior.physics.transmission.automatic &&
                            canShiftManualTransmission(level, bodyId, behavior.physics.transmission)
                        ) {
                            vehicle.wheeledState.transmissionGear = (vehicle.wheeledState.transmissionGear - 1).coerceAtLeast(-1)
                            vehicle.wheeledState.transmissionShiftCooldown = behavior.physics.transmission.shiftCooldownSeconds
                            changed = true
                        }
                    }
                    else -> return false
                }
            }
            else -> return false
        }
        if (changed) {
            BikeLifecycle.saveLevel(level)
            BikeLifecycle.syncLevel(level)
        }
        return changed
    }

    private fun canShiftManualTransmission(
        level: ServerLevel,
        bodyId: BodyId,
        transmission: VehicleTransmissionConfig
    ): Boolean {
        if (!transmission.manualClutch) return true
        return getInput(level.dimensionId, bodyId).clutch >= MANUAL_SHIFT_CLUTCH_THRESHOLD
    }

    private fun playHorn(level: ServerLevel, vehicle: IVehicle): Boolean {
        val key = level.dimensionId to vehicle.bodyId
        val now = level.gameTime
        val last = lastHornTickByVehicle[key]
        if (last != null && now - last < HORN_COOLDOWN_TICKS) return false

        val soundId = vehicle.vehicleDefinition.sounds.horn ?: ResourceLocation(SkyridersMod.MOD_ID, "horn")
        val sound = ForgeRegistries.SOUND_EVENTS.getValue(soundId) ?: return false
        val position = try {
            vehicle.getRenderTransform()?.toWorld?.transformPosition(Vector3d()) ?: return false
        } catch (_: IllegalStateException) {
            return false
        }
        level.playSound(
            null,
            position.x,
            position.y,
            position.z,
            sound,
            SoundSource.NEUTRAL,
            1.0f,
            1.0f
        )
        lastHornTickByVehicle[key] = now
        return true
    }

    fun mutatePartState(
        level: ServerLevel,
        bodyId: BodyId,
        partId: String,
        mutator: (CompoundTag) -> Unit
    ): Boolean {
        val vehicle = getVehicle(level.dimensionId, bodyId) ?: return false
        val stateMap = vehicle.vehicleState.partStates
        val current = stateMap[partId]?.data?.copy()
            ?: vehicle.vehicleDefinition.parts.firstOrNull { it.id == partId }?.defaultState?.copy()
            ?: CompoundTag()
        mutator(current)
        stateMap[partId] = VehiclePartState(partId, current)
        BikeLifecycle.saveLevel(level)
        BikeLifecycle.syncLevel(level)
        return true
    }

    fun getSaveRecords(dimensionId: DimensionId): List<VehicleSaveRecord> {
        return BikeManager.getBikes(dimensionId).map(IBike::toVehicleSaveRecord) +
            (serverVehiclesByDimension[dimensionId]?.values?.map(IVehicle::toVehicleSaveRecord) ?: emptyList())
    }

    fun save(dimensionId: DimensionId): CompoundTag = CompoundTag().apply {
        val vehicles = ListTag()
        getSaveRecords(dimensionId)
            .map(VehicleSaveRecord::save)
            .forEach(vehicles::add)
        put(VEHICLES_KEY, vehicles)
    }

    fun loadRecords(tag: CompoundTag): List<VehicleSaveRecord> {
        val key = if (tag.contains(VEHICLES_KEY, Tag.TAG_LIST.toInt())) VEHICLES_KEY else LEGACY_BIKES_KEY
        val list = tag.getList(key, Tag.TAG_COMPOUND.toInt())
        return (0 until list.size).map { index -> VehicleSaveRecord.load(list.getCompound(index)) }
    }

    fun tick(dimensionId: DimensionId) {
        BikeManager.tick(dimensionId)
        serverVehiclesByDimension[dimensionId]?.values?.forEach(IVehicle::tick)
    }

    fun physTick(physLevel: PhysLevel, dt: Double) {
        BikeManager.physTick(physLevel, dt)
        serverVehiclesByDimension[physLevel.dimension]?.values?.forEach { vehicle ->
            val body = physLevel.getBodyById(vehicle.bodyId) ?: return@forEach
            VehicleDamage.physTick(vehicle, dt)
            val input = VehicleDamage.modifyInput(
                vehicle,
                VehicleStatusEffects.modifyInput(vehicle, getInput(physLevel.dimension, vehicle.bodyId))
            )
            VehicleFuel.consume(vehicle, body, input, dt)
            vehicle.physTick(physLevel, body, input, dt)
            VehicleStatusEffects.physTick(vehicle, body, dt)
        }
        BoostPadHandler.physTick(physLevel, getVehicles(physLevel.dimension), dt)
    }

    fun removeVehicle(level: ServerLevel, bodyId: BodyId, deleteBody: Boolean = true): IVehicle? {
        val bike = BikeManager.removeBike(level, bodyId, deleteBody = false)
        val vehicle = bike ?: serverVehiclesByDimension[level.dimensionId]?.remove(bodyId)
        inputsByDimension[level.dimensionId]?.remove(bodyId)
        if (vehicle != null && deleteBody) {
            val shipWorld = requireNotNull(vsApi.getServerShipWorld(level.server)) {
                "Cannot delete vehicle body before VS server ship world is available"
            }
            shipWorld.allBodies.getById(bodyId)?.let(shipWorld::deleteBody)
        }
        if (vehicle != null) {
            BikeLifecycle.saveLevel(level)
            BikeLifecycle.syncLevel(level)
        }
        return vehicle
    }

    fun applyVisualWheelState(
        level: Level,
        bodyId: BodyId,
        engineOn: Boolean,
        visualLeanRad: Double,
        frontSteerRad: Double,
        frontWheelSpin: Double,
        rearWheelSpin: Double,
        frontWheelAngularVelocity: Double,
        rearWheelAngularVelocity: Double,
        frontWheelSuspensionOffset: Double,
        rearWheelSuspensionOffset: Double
    ) {
        when (val vehicle = getVehicle(level, bodyId)) {
            is IBike -> {
                vehicle.state.engineOn = engineOn
                if (visualLeanRad.isFinite()) {
                    vehicle.state.visualLeanRad = visualLeanRad
                }
                vehicle.state.visualSteerRad = frontSteerRad
                vehicle.state.frontWheelSpin = frontWheelSpin
                vehicle.state.rearWheelSpin = rearWheelSpin
                vehicle.state.frontWheelAngularVelocity = frontWheelAngularVelocity
                vehicle.state.rearWheelAngularVelocity = rearWheelAngularVelocity
                vehicle.state.frontWheelSuspensionOffset = frontWheelSuspensionOffset
                vehicle.state.rearWheelSuspensionOffset = rearWheelSuspensionOffset
            }
            is KartVehicle -> {
                vehicle.kartState.engineOn = engineOn
                vehicle.kartState.debugSteerRad = frontSteerRad
                vehicle.kartState.smoothedSteerRad = frontSteerRad
                vehicle.kartState.frontWheelSpin = frontWheelSpin
                vehicle.kartState.rearWheelSpin = rearWheelSpin
                vehicle.kartState.frontWheelAngularVelocity = frontWheelAngularVelocity
                vehicle.kartState.rearWheelAngularVelocity = rearWheelAngularVelocity
                vehicle.kartState.frontWheelSuspensionOffset = frontWheelSuspensionOffset
                vehicle.kartState.rearWheelSuspensionOffset = rearWheelSuspensionOffset
            }
            is WheeledVehicle -> {
                vehicle.wheeledState.engineOn = engineOn
                vehicle.wheeledState.debugSteerRad = frontSteerRad
                vehicle.wheeledState.smoothedSteerRad = frontSteerRad
                vehicle.wheeledState.frontWheelSpin = frontWheelSpin
                vehicle.wheeledState.rearWheelSpin = rearWheelSpin
                vehicle.wheeledState.frontWheelAngularVelocity = frontWheelAngularVelocity
                vehicle.wheeledState.rearWheelAngularVelocity = rearWheelAngularVelocity
                vehicle.wheeledState.frontWheelSuspensionOffset = frontWheelSuspensionOffset
                vehicle.wheeledState.rearWheelSuspensionOffset = rearWheelSuspensionOffset
            }
        }
    }

    private fun vehicleMap(level: Level): ConcurrentHashMap<DimensionId, ConcurrentHashMap<BodyId, IVehicle>> {
        return if (level.isClientSide) clientVehiclesByDimension else serverVehiclesByDimension
    }
}
