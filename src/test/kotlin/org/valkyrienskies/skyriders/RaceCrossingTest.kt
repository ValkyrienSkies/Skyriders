package org.valkyrienskies.skyriders

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.valkyrienskies.skyriders.content.racing.RaceCrossingTracker
import org.valkyrienskies.skyriders.content.racing.RaceLine

class RaceCrossingTest {
    private val line = RaceLine(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))

    @Test
    fun `crossing at gate endpoint counts even when tick ends outside gate`() {
        val tracker = RaceCrossingTracker(line, Vector3d(9.0, 0.0, -1.0))
        val hit = tracker.update(line, Vector3d(11.0, 0.0, 1.0))!!
        assertEquals(0.5, hit.fraction, 1.0e-9)
        assertEquals(Vector3d(10.0, 0.0, 0.0), hit.position)
    }

    @Test
    fun `touching and resting on line preserves entry side and counts only departure across it`() {
        val tracker = RaceCrossingTracker(line, Vector3d(5.0, 0.0, -1.0))
        repeat(4) { assertNull(tracker.update(line, Vector3d(5.0, 0.0, 0.0))) }
        assertNotNull(tracker.update(line, Vector3d(5.0, 0.0, 1.0)))
        assertNull(tracker.update(line, Vector3d(5.0, 0.0, 2.0)))
        assertNull(tracker.update(line, Vector3d(5.0, 0.0, 0.0)))
        assertNull(tracker.update(line, Vector3d(5.0, 0.0, 1.0)))
    }

    @Test
    fun `crossings outside gate bounds or height are rejected`() {
        for (start in listOf(Vector3d(12.0, 0.0, -1.0), Vector3d(5.0, 4.0, -1.0))) {
            assertNull(RaceCrossingTracker(line, start).update(line, Vector3d(start).add(0.0, 0.0, 2.0)))
        }
    }

    @Test
    fun `fast movement orders multiple gates by intersection time`() {
        val start = Vector3d(5.0, 0.0, -2.0)
        val end = Vector3d(5.0, 0.0, 10.0)
        val gates = listOf(8.0, 2.0, 5.0).map { z ->
            val gate = RaceLine(Vector3d(0.0, 0.0, z), Vector3d(10.0, 0.0, z))
            z to RaceCrossingTracker(gate, start).update(gate, end)!!
        }
        assertEquals(listOf(2.0, 5.0, 8.0), gates.sortedBy { it.second.fraction }.map { it.first })
    }
}
