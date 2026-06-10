package org.valkyrienskies.skyriders.content.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.valkyrienskies.core.api.bodies.properties.BodyId
import org.valkyrienskies.mod.api.shipWorld
import org.valkyrienskies.skyriders.content.VehicleManager
import org.valkyrienskies.skyriders.content.VehicleStatusEffects
import kotlin.math.sqrt

class CavendishEntity(type: EntityType<CavendishEntity>, level: Level) : Entity(type, level) {
    var ownerBodyId: BodyId = 0L

    init {
        blocksBuilding = false
        noPhysics = true
    }

    override fun tick() {
        super.tick()
        if (level().isClientSide) return

        if (tickCount > DESPAWN_TICKS) {
            discard()
            return
        }
        if (tickCount < ARMING_TICKS) return

        val serverLevel = level() as? ServerLevel ?: return
        val shipWorld = serverLevel.shipWorld ?: return
        val peelPos = Vector3d(x, y, z)
        for (vehicle in VehicleManager.getVehicles(serverLevel)) {
            if (vehicle.bodyId == ownerBodyId && tickCount < OWNER_IGNORE_TICKS) continue
            val body = shipWorld.allBodies.getById(vehicle.bodyId) ?: continue
            val hitRadius = TRIGGER_RADIUS + vehicleApproxRadius(vehicle.vehicleDefinition.body.collisionBoxSize)
            if (body.kinematics.position.distanceSquared(peelPos) > hitRadius * hitRadius) continue

            VehicleStatusEffects.applySpinOut(vehicle, duration = 1.85, yawSpeed = 11.5)
            discard()
            return
        }
    }

    override fun defineSynchedData() {
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        ownerBodyId = compound.getLong(OWNER_BODY_ID_KEY)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        compound.putLong(OWNER_BODY_ID_KEY, ownerBodyId)
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> {
        return ClientboundAddEntityPacket(this)
    }

    private fun vehicleApproxRadius(size: Vector3d): Double {
        return sqrt(size.x * size.x + size.z * size.z) * 0.5
    }

    private companion object {
        const val OWNER_BODY_ID_KEY = "OwnerBodyId"
        const val ARMING_TICKS = 8
        const val OWNER_IGNORE_TICKS = 45
        const val DESPAWN_TICKS = 20 * 60 * 3
        const val TRIGGER_RADIUS = 0.45
    }
}
