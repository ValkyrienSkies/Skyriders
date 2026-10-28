package org.valkyrienskies.skyriders.content

import net.minecraft.nbt.CompoundTag

internal fun CompoundTag.finiteDouble(key: String, fallback: Double = 0.0): Double =
    getDouble(key).takeIf(Double::isFinite) ?: fallback

internal fun CompoundTag.transmissionGear(transmission: VehicleTransmissionConfig?): Int =
    (if (contains("transmission_gear")) getInt("transmission_gear") else 1)
        .coerceIn(-1, transmission?.forwardGears?.size ?: 1)
