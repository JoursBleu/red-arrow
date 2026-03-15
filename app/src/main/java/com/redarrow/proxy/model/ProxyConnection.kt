package com.redarrow.proxy.model

/**
 * 代理活跃连接信息
 */
data class ProxyConnection(
    val clientIp: String,          // 客户端 IP
    val clientPort: Int,           // 客户端端口
    val targetHost: String,        // 目标地址
    val targetPort: Int,           // 目标端口
    val protocol: String,          // "SOCKS5" or "HTTP"
    val connectedAt: Long = System.currentTimeMillis(),
) {
    val clientAddress: String get() = "$clientIp:$clientPort"
    val targetAddress: String get() = "$targetHost:$targetPort"
}
