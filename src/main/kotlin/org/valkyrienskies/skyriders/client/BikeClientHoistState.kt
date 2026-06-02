package org.valkyrienskies.skyriders.client

import net.minecraft.client.player.LocalPlayer
import org.joml.Quaterniond
import org.joml.Vector3d

object BikeClientHoistState {
    var hoisting: Boolean = false
    var bodyId: Long = -1L

    fun carriedPosition(player: LocalPlayer): Vector3d {
        val eye = player.eyePosition
        val look = player.lookAngle
        return Vector3d(
            eye.x + look.x * 0.45,
            eye.y + 1.05,
            eye.z + look.z * 0.45
        )
    }

    fun carriedRotation(player: LocalPlayer): Quaterniond {
        return Quaterniond().rotateY(Math.toRadians(90.0 - player.yRot.toDouble()))
    }
}
