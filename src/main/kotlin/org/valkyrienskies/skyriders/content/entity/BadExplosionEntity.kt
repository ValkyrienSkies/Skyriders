package org.valkyrienskies.skyriders.content.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level

class BadExplosionEntity(type: EntityType<BadExplosionEntity>, level: Level) : Entity(type, level) {
    init {
        blocksBuilding = false
        noPhysics = true
    }

    override fun tick() {
        super.tick()
        if (tickCount > FRAME_COUNT) {
            discard()
        }
    }

    override fun defineSynchedData() {
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> {
        return ClientboundAddEntityPacket(this)
    }

    companion object {
        const val FRAME_COUNT = 17
    }
}
