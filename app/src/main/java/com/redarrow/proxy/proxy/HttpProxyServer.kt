package com.redarrow.proxy.proxy

import android.util.Log
import com.redarrow.proxy.ssh.SshManager
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 HTTP 代理服务器
 * 接收 HTTP/HTTPS 请求，通过 SSH direct-tcpip 通道转发
 *
 * 支持:
 * - HTTP CONNECT (HTTPS 隧道)
 * - HTTP 普通请求转发 (GET/POST 等)
 */
class HttpProxyServer(
    private val sshManager: SshManager,
    private val port: Int,
) {
    companion object {
        private const val TAG = "HttpProxy"
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
        Log.i(TAG, "HTTP proxy started on 127.0.0.1:$port")

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
        }, "http-proxy-accept").start()
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        executor?.shutdownNow()
        serverSocket = null
        executor = null
        Log.i(TAG, "HTTP proxy stopped")
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val reader = BufferedReader(InputStreamReader(input))

            // 读取请求行
            val requestLine = reader.readLine() ?: run {
                client.close()
                return
            }

            val parts = requestLine.split(" ", limit = 3)
            if (parts.size < 3) {
                sendError(client, 400, "Bad Request")
                return
            }

            val method = parts[0].uppercase()
            val uri = parts[1]

            // 读取所有 headers
            val headers = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headers.add(line)
            }

            if (method == "CONNECT") {
                handleConnect(client, uri, headers)
            } else {
                handleHttp(client, method, uri, parts[2], headers, reader)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * 处理 CONNECT 方法 (HTTPS 隧道)
     * 客户端发送: CONNECT host:port HTTP/1.1
     */
    private fun handleConnect(client: Socket, hostPort: String, headers: List<String>) {
        val (host, port) = parseHostPort(hostPort, 443)
        Log.d(TAG, "CONNECT $host:$port")

        val channel = sshManager.openDirectChannel(host, port)
        if (channel == null) {
            sendError(client, 502, "Bad Gateway - SSH channel failed")
            return
        }

        try {
            channel.connect(10000)
        } catch (e: Exception) {
            Log.e(TAG, "Channel connect failed: $host:$port", e)
            sendError(client, 502, "Bad Gateway - Connection failed")
            return
        }

        // 告诉客户端隧道已建立
        val output = client.getOutputStream()
        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        output.flush()

        // 双向转发
        val remoteIn = channel.inputStream
        val remoteOut = channel.outputStream

        val t1 = relay(client.getInputStream(), remoteOut, "client->tunnel")
        val t2 = relay(remoteIn, output, "tunnel->client")

        try { t1.join() } catch (_: Exception) {}
        try { t2.join() } catch (_: Exception) {}

        channel.disconnect()
    }

    /**
     * 处理普通 HTTP 请求 (GET/POST 等)
     * 转发整个 HTTP 请求到目标服务器
     */
    private fun handleHttp(
        client: Socket,
        method: String,
        uri: String,
        httpVersion: String,
        headers: List<String>,
        reader: BufferedReader
    ) {
        // 解析 URI: http://host:port/path
        val url = if (uri.startsWith("http://")) uri else "http://$uri"
        val hostPortPath = url.removePrefix("http://")
        val slashIndex = hostPortPath.indexOf('/')
        val hostPort = if (slashIndex >= 0) hostPortPath.substring(0, slashIndex) else hostPortPath
        val path = if (slashIndex >= 0) hostPortPath.substring(slashIndex) else "/"

        val (host, port) = parseHostPort(hostPort, 80)
        Log.d(TAG, "$method $host:$port$path")

        val channel = sshManager.openDirectChannel(host, port)
        if (channel == null) {
            sendError(client, 502, "Bad Gateway")
            return
        }

        try {
            channel.connect(10000)
        } catch (e: Exception) {
            sendError(client, 502, "Bad Gateway - Connection failed")
            return
        }

        val remoteOut = channel.outputStream
        val remoteIn = channel.inputStream

        // 重写请求行，使用相对路径
        remoteOut.write("$method $path $httpVersion\r\n".toByteArray())

        // 转发 headers (过滤 Proxy- 头)
        for (header in headers) {
            if (!header.startsWith("Proxy-", ignoreCase = true)) {
                remoteOut.write("$header\r\n".toByteArray())
            }
        }
        remoteOut.write("\r\n".toByteArray())
        remoteOut.flush()

        // 如果有 body (Content-Length)，转发
        val contentLength = headers.find {
            it.startsWith("Content-Length:", ignoreCase = true)
        }?.substringAfter(":")?.trim()?.toLongOrNull()

        if (contentLength != null && contentLength > 0) {
            val buf = ByteArray(BUFFER_SIZE)
            var remaining = contentLength
            while (remaining > 0) {
                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                val n = client.getInputStream().read(buf, 0, toRead)
                if (n <= 0) break
                remoteOut.write(buf, 0, n)
                remaining -= n
            }
            remoteOut.flush()
        }

        // 转发响应回客户端
        val clientOut = client.getOutputStream()
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = remoteIn.read(buf)
                if (n <= 0) break
                clientOut.write(buf, 0, n)
                clientOut.flush()
            }
        } catch (_: IOException) {}

        channel.disconnect()
    }

    private fun parseHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        val colonIndex = hostPort.lastIndexOf(':')
        return if (colonIndex > 0) {
            val port = hostPort.substring(colonIndex + 1).toIntOrNull() ?: defaultPort
            hostPort.substring(0, colonIndex) to port
        } else {
            hostPort to defaultPort
        }
    }

    private fun sendError(client: Socket, code: Int, message: String) {
        try {
            val body = "<html><body><h1>$code $message</h1></body></html>"
            val response = "HTTP/1.1 $code $message\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: ${body.length}\r\n" +
                    "Connection: close\r\n\r\n" +
                    body
            client.getOutputStream().write(response.toByteArray())
            client.getOutputStream().flush()
        } catch (_: Exception) {}
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
}
