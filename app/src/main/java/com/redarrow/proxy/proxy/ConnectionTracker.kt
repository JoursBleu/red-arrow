package com.redarrow.proxy.proxy

import com.redarrow.proxy.model.ProxyConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 跟踪所有代理活跃连接
 */
class ConnectionTracker {

    private val connections = ConcurrentHashMap<String, ProxyConnection>()
    private val _activeConnections = MutableStateFlow<List<ProxyConnection>>(emptyList())
    val activeConnections: StateFlow<List<ProxyConnection>> = _activeConnections

    /**
     * 注册一个新连接，返回连接 ID
     */
    fun add(conn: ProxyConnection): String {
        val id = "${conn.protocol}-${conn.clientIp}:${conn.clientPort}"
        connections[id] = conn
        publish()
        return id
    }

    /**
     * 移除连接
     */
    fun remove(id: String) {
        connections.remove(id)
        publish()
    }

    /**
     * 清除所有
     */
    fun clear() {
        connections.clear()
        publish()
    }

    private fun publish() {
        _activeConnections.value = connections.values.toList()
            .sortedByDescending { it.connectedAt }
    }
}
