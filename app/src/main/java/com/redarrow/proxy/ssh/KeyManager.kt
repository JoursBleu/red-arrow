package com.redarrow.proxy.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.redarrow.proxy.model.ConnectionConfig
import com.redarrow.proxy.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * SSH 密钥管理工具
 */
object KeyManager {
    private const val TAG = "KeyManager"

    /**
     * 生成 Ed25519 密钥对
     * @param passphrase 可选私钥密码
     * @return Pair(privateKeyString, publicKeyString)
     */
    fun generateEd25519(passphrase: String = ""): Pair<String, String> {
        val jsch = JSch()
        val kp = KeyPair.genKeyPair(jsch, KeyPair.ED25519)
        @Suppress("DEPRECATION")
        if (passphrase.isNotBlank()) {
            kp.setPassphrase(passphrase.toByteArray())
        }
        val privOut = ByteArrayOutputStream()
        val pubOut = ByteArrayOutputStream()
        kp.writePrivateKey(privOut)
        kp.writePublicKey(pubOut, "red-arrow")
        kp.dispose()
        return privOut.toString("UTF-8") to pubOut.toString("UTF-8")
    }

    /**
     * 生成 RSA 密钥对 (4096 bits)
     * @param passphrase 可选私钥密码
     * @return Pair(privateKeyString, publicKeyString)
     */
    fun generateRSA(passphrase: String = ""): Pair<String, String> {
        val jsch = JSch()
        val kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096)
        @Suppress("DEPRECATION")
        if (passphrase.isNotBlank()) {
            kp.setPassphrase(passphrase.toByteArray())
        }
        val privOut = ByteArrayOutputStream()
        val pubOut = ByteArrayOutputStream()
        kp.writePrivateKey(privOut)
        kp.writePublicKey(pubOut, "red-arrow")
        kp.dispose()
        return privOut.toString("UTF-8") to pubOut.toString("UTF-8")
    }

    /**
     * 从私钥内容提取公钥
     * @param privateKey 私钥内容
     * @param passphrase 私钥密码（可选）
     * @return 公钥字符串
     */
    fun extractPublicKey(privateKey: String, passphrase: String = ""): String {
        val jsch = JSch()
        val kp = KeyPair.load(jsch, privateKey.toByteArray(Charsets.UTF_8), null)
        if (passphrase.isNotBlank()) {
            if (!kp.decrypt(passphrase.toByteArray())) {
                throw IllegalArgumentException("Wrong passphrase")
            }
        }
        val pubOut = ByteArrayOutputStream()
        kp.writePublicKey(pubOut, "red-arrow")
        kp.dispose()
        return pubOut.toString("UTF-8")
    }

    /**
     * 通过 SSH exec 将公钥追加到远程 ~/.ssh/authorized_keys
     * 类似 ssh-copy-id
     */
    suspend fun sendPublicKey(
        config: ConnectionConfig,
        publicKey: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val jsch = JSch()
        try {
            // 用密码或已有密钥认证
            when (config.authMethod) {
                ConnectionConfig.AuthMethod.PASSWORD -> {}
                ConnectionConfig.AuthMethod.PUBLIC_KEY -> {
                    val keyBytes = config.privateKey.toByteArray(Charsets.UTF_8)
                    if (config.privateKeyPassphrase.isNotBlank()) {
                        jsch.addIdentity("key", keyBytes, null,
                            config.privateKeyPassphrase.toByteArray())
                    } else {
                        jsch.addIdentity("key", keyBytes, null, null)
                    }
                }
            }

            val session = jsch.getSession(config.username, config.host, config.port).apply {
                if (config.authMethod == ConnectionConfig.AuthMethod.PASSWORD) {
                    setPassword(config.password)
                }
                setConfig("StrictHostKeyChecking", "no")
                timeout = 15000
            }

            session.connect(15000)
            AppLog.i(TAG, "Connected for key upload")

            val cmd = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                    "echo '${publicKey.trim()}' >> ~/.ssh/authorized_keys && " +
                    "chmod 600 ~/.ssh/authorized_keys"

            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(cmd)
            channel.inputStream = null
            val errStream = channel.errStream
            channel.connect(10000)

            // 等待执行完毕
            while (!channel.isClosed) {
                Thread.sleep(100)
            }

            val exitStatus = channel.exitStatus
            val errMsg = errStream.bufferedReader().readText()
            channel.disconnect()
            session.disconnect()

            if (exitStatus == 0) {
                AppLog.i(TAG, "Public key sent successfully")
                Result.success(Unit)
            } else {
                AppLog.e(TAG, "ssh-copy-id failed (exit=$exitStatus): $errMsg")
                Result.failure(Exception("Exit $exitStatus: $errMsg"))
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to send public key", e)
            Result.failure(e)
        }
    }
}
