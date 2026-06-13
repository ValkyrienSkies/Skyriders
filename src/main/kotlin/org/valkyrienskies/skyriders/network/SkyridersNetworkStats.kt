package org.valkyrienskies.skyriders.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

object SkyridersNetworkStats {
    private val countersByPacket = ConcurrentHashMap<String, PacketCounters>()
    @Volatile
    private var startedAtMillis = System.currentTimeMillis()

    fun record(packetType: String, estimatedBytes: Int, itemCount: Int = 1) {
        recordBatch(packetType, packetCount = 1, estimatedBytes = estimatedBytes, itemCount = itemCount)
    }

    fun recordBatch(packetType: String, packetCount: Int, estimatedBytes: Int, itemCount: Int = packetCount) {
        val counters = countersByPacket.getOrPut(packetType) { PacketCounters() }
        counters.packets.add(packetCount.coerceAtLeast(0).toLong())
        counters.bytes.add(estimatedBytes.coerceAtLeast(0).toLong())
        counters.items.add(itemCount.coerceAtLeast(0).toLong())
    }

    fun reset() {
        countersByPacket.clear()
        startedAtMillis = System.currentTimeMillis()
    }

    fun snapshot(): NetworkStatsSnapshot {
        val now = System.currentTimeMillis()
        val elapsedSeconds = ((now - startedAtMillis).coerceAtLeast(1L)) / 1000.0
        val rows = countersByPacket.map { (packetType, counters) ->
            NetworkStatsRow(
                packetType = packetType,
                packets = counters.packets.sum(),
                estimatedBytes = counters.bytes.sum(),
                items = counters.items.sum()
            )
        }.sortedByDescending(NetworkStatsRow::estimatedBytes)
        return NetworkStatsSnapshot(
            elapsedSeconds = elapsedSeconds,
            rows = rows
        )
    }

    private class PacketCounters {
        val packets = LongAdder()
        val bytes = LongAdder()
        val items = LongAdder()
    }
}

data class NetworkStatsSnapshot(
    val elapsedSeconds: Double,
    val rows: List<NetworkStatsRow>
) {
    val totalPackets: Long
        get() = rows.sumOf { it.packets }

    val totalEstimatedBytes: Long
        get() = rows.sumOf { it.estimatedBytes }
}

data class NetworkStatsRow(
    val packetType: String,
    val packets: Long,
    val estimatedBytes: Long,
    val items: Long
) {
    fun packetsPerSecond(elapsedSeconds: Double): Double = packets / elapsedSeconds.coerceAtLeast(0.001)

    fun bytesPerSecond(elapsedSeconds: Double): Double = estimatedBytes / elapsedSeconds.coerceAtLeast(0.001)
}
