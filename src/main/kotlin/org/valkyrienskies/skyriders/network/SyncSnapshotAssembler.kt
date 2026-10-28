package org.valkyrienskies.skyriders.network

internal class SyncSnapshotAssembler<T> {
    private var records: MutableList<T>? = null

    fun accept(batch: List<T>, first: Boolean, last: Boolean): List<T>? {
        if (first) records = ArrayList()
        val pending = records ?: return null
        pending.addAll(batch)
        if (!last) return null
        records = null
        return pending
    }

    fun clear() { records = null }
}
