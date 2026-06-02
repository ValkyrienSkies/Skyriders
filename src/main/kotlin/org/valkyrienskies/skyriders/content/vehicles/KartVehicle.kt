package org.valkyrienskies.skyriders.content.vehicles

import net.minecraft.world.level.Level
import net.minecraft.nbt.CompoundTag
import org.valkyrienskies.core.api.bodies.ClientVsBody
import org.valkyrienskies.core.api.bodies.PhysVsBody
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.core.api.bodies.properties.BodyTransform
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.IVehicle
import org.valkyrienskies.skyriders.content.KartRuntimeState
import org.valkyrienskies.skyriders.content.KartVehicleBehaviorDefinition
import org.valkyrienskies.skyriders.content.VehicleDefinition
import org.valkyrienskies.skyriders.content.VehicleInput
import org.valkyrienskies.skyriders.content.VehicleRuntimeState
import org.valkyrienskies.skyriders.content.VehicleSaveRecord
import org.valkyrienskies.skyriders.util.KartPhysicsSolver

class KartVehicle(
    override val bodyId: BodyId,
    override val level: Level,
    override val vehicleDefinition: VehicleDefinition,
    val kartState: KartRuntimeState = KartRuntimeState()
) : IVehicle {
    override val id: String
        get() = vehicleDefinition.id.toString()

    override val vehicleState: VehicleRuntimeState
        get() = VehicleRuntimeState(engineOn = kartState.engineOn)

    override fun getRenderTransform(): BodyTransform {
        val body = requireBody()
        return if (body is ClientVsBody) body.renderTransform else body.kinematics.transform
    }

    override fun tick() {
    }

    override fun physTick(physLevel: PhysLevel, body: PhysVsBody, input: VehicleInput, dt: Double) {
        val behavior = vehicleDefinition.behavior as? KartVehicleBehaviorDefinition ?: return
        val activeInput = if (kartState.engineOn) input else VehicleInput.EMPTY.copy(riderPresent = input.riderPresent)
        KartPhysicsSolver.updateKartPhysics(body, physLevel, activeInput, behavior.physics, kartState, dt)
    }

    override fun toVehicleSaveRecord(): VehicleSaveRecord = VehicleSaveRecord(
        bodyId = bodyId,
        vehicleType = id,
        engineOn = kartState.engineOn,
        behaviorTag = CompoundTag().apply {
            putString("behavior_type", "kart")
        }
    )

    private fun requireBody() =
        requireNotNull(level.shipWorld?.allBodies?.getById(bodyId)) {
            "Vehicle $id is bound to missing VS body $bodyId"
        }
}
