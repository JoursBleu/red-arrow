package com.redarrow.proxy.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 流量统计
 */
object TrafficCounter {
    private val _bytesIn = AtomicLong(0)
    private val _bytesOut = AtomicLong(0)

    /** Updated periodically for UI */
    private val _snapshot = MutableStateFlow(Pair(0L, 0L))
    val snapshot: StateFlow<Pair<Long, Long>> = _snapshot

    fun addIn(bytes: Long) { _bytesIn.addAndGet(bytes) }
    fun addOut(bytes: Long) { _bytesOut.addAndGet(bytes) }

    fun refreshSnapshot() {
        _snapshot.value = Pair(_bytesIn.get(), _bytesOut.get())
    }

    fun reset() {
        _bytesIn.set(0)
        _bytesOut.set(0)
        _snapshot.value = Pair(0L, 0L)
    }
}
