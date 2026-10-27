package org.valkyrienskies.skyriders.network

import io.netty.handler.codec.DecoderException
import net.minecraft.network.FriendlyByteBuf

internal fun readCollectionSize(buf: FriendlyByteBuf, maximum: Int): Int {
    val count = buf.readVarInt()
    if (count !in 0..maximum) throw DecoderException("Invalid collection size: $count")
    return count
}
