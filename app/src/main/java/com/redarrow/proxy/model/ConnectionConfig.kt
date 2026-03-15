package com.redarrow.proxy.model

/**
 * SSH 连接配置
 */
data class ConnectionConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",             // 私钥内容（从文件读取，支持 OpenSSH/PEM/PKCS8 等格式）
    val privateKeyFileName: String = "",     // 私钥文件名（仅用于 UI 显示）
    val privateKeyPassphrase: String = "",
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val socksPort: Int = 1080,         // 本地 SOCKS5 代理端口
    val httpPort: Int = 8080,          // 本地 HTTP 代理端口
    val proxyPassword: String = "",    // 代理访问密码（空则不鉴权）
    val keepAliveInterval: Int = 30,   // 心跳间隔(秒)
) {
    enum class AuthMethod {
        PASSWORD, PUBLIC_KEY
    }

    fun isValid(): Boolean {
        return host.isNotBlank() &&
                port in 1..65535 &&
                username.isNotBlank() &&
                socksPort in 1..65535 &&
                httpPort in 1..65535 &&
                socksPort != httpPort &&
                when (authMethod) {
                    AuthMethod.PASSWORD -> password.isNotBlank()
                    AuthMethod.PUBLIC_KEY -> privateKey.isNotBlank()
                }
    }
}
