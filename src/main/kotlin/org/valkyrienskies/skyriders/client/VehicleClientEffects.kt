package org.valkyrienskies.skyriders.client

import org.valkyrienskies.skyriders.network.SkyridersNetwork

object VehicleClientEffects {
    fun updateBikeTelemetry(packet: SkyridersNetwork.BikeDebugPacket) {
        BikeClientEffects.updateTelemetry(packet)
    }

    fun getBikeTelemetry(bodyId: Long): SkyridersNetwork.BikeDebugPacket? {
        return BikeClientEffects.getTelemetry(bodyId)
    }

    fun tick() {
        BikeClientEffects.tick()
    }
}
