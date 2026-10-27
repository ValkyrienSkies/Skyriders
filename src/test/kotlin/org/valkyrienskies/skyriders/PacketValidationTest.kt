package org.valkyrienskies.skyriders

import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import net.minecraft.network.FriendlyByteBuf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.valkyrienskies.skyriders.network.readCollectionSize
import org.valkyrienskies.skyriders.network.SyncSnapshotAssembler

class PacketValidationTest {
    @Test
    fun largeSnapshotsPublishAtomicallyAndDiscardInterruptedBatches() {
        val assembler = SyncSnapshotAssembler<Int>()
        val records = (0 until 10000).toList()
        val batches = records.chunked(128)
        batches.forEachIndexed { index, batch ->
            val result = assembler.accept(batch, index == 0, index == batches.lastIndex)
            if (index == batches.lastIndex) assertEquals(records, result) else assertNull(result)
        }
        assembler.accept(listOf(1), true, false)
        assembler.clear()
        assertNull(assembler.accept(listOf(2), false, true))
        assembler.accept(listOf(3), true, false)
        assertEquals(listOf(4), assembler.accept(listOf(4), true, true))
        assertEquals(emptyList<Int>(), assembler.accept(emptyList(), true, true))
    }

    @Test
    fun collectionLengthsRejectMalformedValuesWithoutAllocatingEntries() {
        for (size in listOf(-1, 4097, Int.MAX_VALUE)) {
            val buf = FriendlyByteBuf(Unpooled.buffer()).writeVarInt(size)
            try {
                assertThrows(DecoderException::class.java) { readCollectionSize(buf, 4096) }
            } finally {
                buf.release()
            }
        }
        for (size in listOf(0, 1, 4096)) {
            val buf = FriendlyByteBuf(Unpooled.buffer()).writeVarInt(size)
            try {
                assertEquals(size, readCollectionSize(buf, 4096))
            } finally {
                buf.release()
            }
        }
    }
}
