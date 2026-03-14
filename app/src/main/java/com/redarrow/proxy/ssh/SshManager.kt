package com.redarrow.proxy.ssh

import android.util.Log
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.redarrow.proxy.model.ConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * SSH 连接管理器
 * 建立 SSH 连接，提供 direct-tcpip channel 用于隧道转发
 */
class SshManager {
    companion object {
        private const val TAG = "SshManager"
    }

    private var session: Session? = null
    private val jsch = JSch()

    val isConnected: Boolean
        get() = session?.isConnected == true

    /**
     * 连接 SSH 服务器
     */
    suspend fun connect(config: ConnectionConfig): Result<Session> = withContext(Dispatchers.IO) {
        try {
            disconnect()

            // 配置认证
            when (config.authMethod) {
                ConnectionConfig.AuthMethod.PASSWORD -> { /* 密码在 session 上设置 */ }
                ConnectionConfig.AuthMethod.PUBLIC_KEY -> {
                    jsch.removeAllIdentity()
                    val keyBytes = config.privateKey.toByteArray(StandardCharsets.UTF_8)
                    if (config.privateKeyPassphrase.isNotBlank()) {
                        jsch.addIdentity("key", keyBytes, null,
                            config.privateKeyPassphrase.toByteArray())
                    } else {
                        jsch.addIdentity("key", keyBytes, null, null)
                    }
                }
            }

            val newSession = jsch.getSession(config.username, config.host, config.port).apply {
                if (config.authMethod == ConnectionConfig.AuthMethod.PASSWORD) {
                    setPassword(config.password)
                }
                setConfig("StrictHostKeyChecking", "no")
                setServerAliveInterval(config.keepAliveInterval * 1000)
                setServerAliveCountMax(3)
                setConfig("compression.s2c", "zlib@openssh.com,none")
                setConfig("compression.c2s", "zlib@openssh.com,none")
                timeout = 15000
            }

            Log.i(TAG, "Connecting to ${config.host}:${config.port}...")
            newSession.connect(15000)
            Log.i(TAG, "SSH session established")

            session = newSession
            Result.success(newSession)
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            disconnect()
            Result.failure(e)
        }
    }

    /**
     * 打开一个 direct-tcpip 通道，用于转发流量到目标地址
     */
    fun openDirectChannel(targetHost: String, targetPort: Int): ChannelDirectTCPIP? {
        val s = session ?: return null
        if (!s.isConnected) return null
        return try {
            (s.openChannel("direct-tcpip") as ChannelDirectTCPIP).apply {
                setHost(targetHost)
                setPort(targetPort)
                setOrgIPAddress("127.0.0.1")
                setOrgPort(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open direct channel to $targetHost:$targetPort", e)
            null
        }
    }

    /**
     * 断开 SSH 连接
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            session?.let {
                if (it.isConnected) {
                    it.disconnect()
                    Log.i(TAG, "SSH disconnected")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        } finally {
            session = null
        }
    }
}
