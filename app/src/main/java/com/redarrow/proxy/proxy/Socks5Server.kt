package com.redarrow.proxy.proxy

import android.util.Log
import com.redarrow.proxy.ssh.SshManager
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 SOCKS5 代理服务器
 * 接收 SOCKS5 请求，通过 SSH 隧道转发
 */
class Socks5Server(
    private val sshManager: SshManager,
    private val port: Int,
) {
    companion object {
        private const val TAG = "Socks5Server"
        private const val BUFFER_SIZE = 32768
    }

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.get()) return
        running.set(true)

        executor = Executors.newCachedThreadPool()
        serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Log.i(TAG, "SOCKS5 server started on 127.0.0.1:$port")

        Thread({
            while (running.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    executor?.submit { handleClient(client) }
                } catch (e: SocketException) {
                    if (running.get()) Log.e(TAG, "Accept error", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Accept error", e)
                }
            }
        }, "socks5-accept").start()
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        executor?.shutdownNow()
        serverSocket = null
        executor = null
        Log.i(TAG, "SOCKS5 server stopped")
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // SOCKS5 握手
            if (!doHandshake(input, output)) {
                client.close()
                return
            }

            // 读取连接请求
            val request = readConnectRequest(input) ?: run {
                sendReply(output, 0x01) // general failure
                client.close()
                return
            }

            Log.d(TAG, "CONNECT ${request.host}:${request.port}")

            // 通过 SSH 隧道连接目标
            val channel = sshManager.openDirectChannel(request.host, request.port)
            if (channel == null) {
                sendReply(output, 0x04) // host unreachable
                client.close()
                return
            }

            try {
                channel.connect(10000)
            } catch (e: Exception) {
                Log.e(TAG, "Channel connect failed: ${request.host}:${request.port}", e)
                sendReply(output, 0x05) // connection refused
                client.close()
                return
            }

            // 发送成功响应
            sendReply(output, 0x00)

            // 双向转发数据
            val remoteIn = channel.inputStream
            val remoteOut = channel.outputStream

            val t1 = relay(input, remoteOut, "client->remote")
            val t2 = relay(remoteIn, output, "remote->client")

            try { t1.join() } catch (_: Exception) {}
            try { t2.join() } catch (_: Exception) {}

            channel.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun doHandshake(input: InputStream, output: OutputStream): Boolean {
        val version = input.read()
        if (version != 0x05) return false

        val nMethods = input.read()
        val methods = ByteArray(nMethods)
        input.readFully(methods)

        // 回复：不需要认证
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()
        return true
    }

    private data class ConnectRequest(val host: String, val port: Int)

    private fun readConnectRequest(input: InputStream): ConnectRequest? {
        val ver = input.read()    // VER
        val cmd = input.read()    // CMD
        val rsv = input.read()    // RSV
        val atyp = input.read()   // ATYP

        if (ver != 0x05 || cmd != 0x01) return null  // 只支持 CONNECT

        val host = when (atyp) {
            0x01 -> { // IPv4
                val addr = ByteArray(4)
                input.readFully(addr)
                addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> { // DOMAIN
                val len = input.read()
                val domain = ByteArray(len)
                input.readFully(domain)
                String(domain)
            }
            0x04 -> { // IPv6
                val addr = ByteArray(16)
                input.readFully(addr)
                // 简化 IPv6 显示
                addr.toList().chunked(2).joinToString(":") {
                    String.format("%02x%02x", it[0], it[1])
                }
            }
            else -> return null
        }

        val portHigh = input.read()
        val portLow = input.read()
        val port = (portHigh shl 8) or portLow

        return ConnectRequest(host, port)
    }

    private fun sendReply(output: OutputStream, status: Int) {
        output.write(byteArrayOf(
            0x05,                   // VER
            status.toByte(),        // REP
            0x00,                   // RSV
            0x01,                   // ATYP: IPv4
            0x00, 0x00, 0x00, 0x00, // BIND.ADDR
            0x00, 0x00              // BIND.PORT
        ))
        output.flush()
    }

    private fun relay(from: InputStream, to: OutputStream, tag: String): Thread {
        return Thread({
            try {
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = from.read(buf)
                    if (n <= 0) break
                    to.write(buf, 0, n)
                    to.flush()
                }
            } catch (_: IOException) {
            } catch (_: SocketException) {
            }
        }, "relay-$tag").also { it.isDaemon = true; it.start() }
    }

    private fun InputStream.readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = read(buf, offset, buf.size - offset)
            if (n <= 0) throw IOException("Unexpected EOF")
            offset += n
        }
    }
}
