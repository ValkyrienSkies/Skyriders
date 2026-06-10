package org.valkyrienskies.skyriders.client

import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2

object RaceCompassClientState {
    private var target: Vec3? = null
    private var lastUpdateTick: Long = -1000L

    fun update(active: Boolean, targetPos: Vec3?) {
        val level = Minecraft.getInstance().level ?: return
        target = if (active) targetPos else null
        lastUpdateTick = level.gameTime
    }

    fun angle(entity: Entity?, seed: Int): Float {
        val level = Minecraft.getInstance().level ?: return spin(seed)
        val currentTarget = target
        if (entity == null || currentTarget == null || level.gameTime - lastUpdateTick > 40L) {
            return spin(seed)
        }
        val dx = currentTarget.x - entity.x
        val dz = currentTarget.z - entity.z
        if (dx * dx + dz * dz < 1.0E-4) return 0.0f
        val targetAngle = atan2(dz, dx) / (Math.PI * 2.0)
        val entityYaw = Mth.positiveModulo(entity.yRot.toDouble() / 360.0, 1.0)
        return Mth.positiveModulo((0.5 - (targetAngle - entityYaw)).toFloat(), 1.0f)
    }

    fun tick() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        if (level.gameTime - lastUpdateTick > 40L) {
            target = null
        }
    }

    private fun spin(seed: Int): Float {
        val level = Minecraft.getInstance().level
        val time = level?.gameTime ?: System.currentTimeMillis() / 50L
        return Mth.positiveModulo(((time + seed).toFloat() * 0.07f), 1.0f)
    }
}
