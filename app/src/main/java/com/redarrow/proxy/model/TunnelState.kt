package com.redarrow.proxy.model

/**
 * 隧道运行状态
 */
data class TunnelState(
    val status: Status = Status.DISCONNECTED,
    val socksPort: Int = 0,
    val httpPort: Int = 0,
    val connectedHost: String = "",
    val errorMessage: String = "",
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val connectedAt: Long = 0,
) {
    enum class Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR,
    }

    val isConnected: Boolean get() = status == Status.CONNECTED
    val isConnecting: Boolean get() = status == Status.CONNECTING

    val uptimeSeconds: Long
        get() = if (connectedAt > 0) (System.currentTimeMillis() - connectedAt) / 1000 else 0
}
