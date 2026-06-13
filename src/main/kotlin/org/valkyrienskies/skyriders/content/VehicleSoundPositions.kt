package org.valkyrienskies.skyriders.content

import org.joml.Vector3d

object VehicleSoundPositions {
    fun engineWorldPosition(vehicle: IVehicle): Vector3d? {
        val local = vehicle.vehicleDefinition.interactions.zones
            .firstOrNull { it.partId == VehicleDamage.ENGINE_PART_ID }
            ?.center
            ?.let(::Vector3d)
            ?: frontBodyPosition(vehicle, yFraction = 0.08, zFraction = 0.24)

        return worldPosition(vehicle, local)
    }

    fun jukeboxWorldPosition(vehicle: IVehicle): Vector3d? {
        val local = vehicle.vehicleDefinition.interactions.zones
            .firstOrNull { it.partId == VehicleJukebox.PART_ID }
            ?.center
            ?.let(::Vector3d)
            ?: frontBodyPosition(vehicle, yFraction = 0.35, zFraction = 0.18)

        return worldPosition(vehicle, local)
    }

    private fun frontBodyPosition(vehicle: IVehicle, yFraction: Double, zFraction: Double): Vector3d {
        val body = vehicle.vehicleDefinition.body
        return Vector3d(
            body.collisionBoxOffset.x,
            body.collisionBoxOffset.y + body.collisionBoxSize.y * yFraction,
            body.collisionBoxOffset.z + body.collisionBoxSize.z * zFraction
        )
    }

    private fun worldPosition(vehicle: IVehicle, local: Vector3d): Vector3d? {
        val transform = try {
            vehicle.getRenderTransform()
        } catch (_: IllegalStateException) {
            null
        } ?: return null

        return transform.toWorld.transformPosition(Vector3d(local)).takeIf { it.isFinite() }
    }

    private fun Vector3d.isFinite(): Boolean {
        return x.isFinite() && y.isFinite() && z.isFinite()
    }
}
