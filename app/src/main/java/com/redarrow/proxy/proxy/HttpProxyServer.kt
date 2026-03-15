package com.redarrow.proxy.proxy

import android.util.Base64
import com.redarrow.proxy.util.AppLog
import com.redarrow.proxy.model.ProxyConnection
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
 * HTTP 代理服务器
 * 监听 0.0.0.0，支持 CONNECT (HTTPS) 和普通 HTTP 转发
 * 可选 Proxy-Authorization Basic 认证，跟踪活跃连接
 */
class HttpProxyServer(
    private val sshManager: SshManager,
    private val port: Int,
    private val proxyUsername: String = "user",
    private val proxyPassword: String = "",
    private val tracker: ConnectionTracker? = null,
) {
    companion object {
        private const val TAG = "HttpProxy"
        private const val BUFFER_SIZE = 32768
    }

    private val requireAuth get() = proxyUsername.isNotBlank() || proxyPassword.isNotBlank()

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.get()) return
        running.set(true)

        executor = Executors.newCachedThreadPool()
        serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName("0.0.0.0"))
        AppLog.i(TAG, "HTTP proxy started on 0.0.0.0:$port (auth=${requireAuth})")

        Thread({
            while (running.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    executor?.submit { handleClient(client) }
                } catch (e: SocketException) {
                    if (running.get()) AppLog.e(TAG, "Accept error", e)
                } catch (e: Exception) {
                    AppLog.e(TAG, "Accept error", e)
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
        AppLog.i(TAG, "HTTP proxy stopped")
    }

    private fun handleClient(client: Socket) {
        var connId: String? = null
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val reader = BufferedReader(InputStreamReader(input))

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

            val headers = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headers.add(line)
            }

            if (requireAuth && !checkAuth(headers)) {
                send407(client)
                return
            }

            if (method == "CONNECT") {
                connId = handleConnect(client, uri, headers)
            } else {
                connId = handleHttp(client, method, uri, parts[2], headers, reader)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Client handler error", e)
        } finally {
            connId?.let { tracker?.remove(it) }
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun checkAuth(headers: List<String>): Boolean {
        val authHeader = headers.find {
            it.startsWith("Proxy-Authorization:", ignoreCase = true)
        } ?: return false

        val value = authHeader.substringAfter(":").trim()
        if (!value.startsWith("Basic ", ignoreCase = true)) return false

        return try {
            val decoded = String(Base64.decode(value.substring(6), Base64.NO_WRAP))
            val colonIdx = decoded.indexOf(':')
            if (colonIdx < 0) return false
            val clientUsername = decoded.substring(0, colonIdx)
            val clientPassword = decoded.substring(colonIdx + 1)
            clientUsername == proxyUsername && clientPassword == proxyPassword
        } catch (e: Exception) {
            false
        }
    }

    private fun send407(client: Socket) {
        try {
            val body = "<html><body><h1>407 Proxy Authentication Required</h1></body></html>"
            val response = "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"Red Arrow Proxy\"\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: ${body.length}\r\n" +
                    "Connection: close\r\n\r\n" +
                    body
            client.getOutputStream().write(response.toByteArray())
            client.getOutputStream().flush()
        } catch (_: Exception) {}
    }

    /**
     * 返回 connId，调用方在 finally 里 remove
     */
    private fun handleConnect(client: Socket, hostPort: String, headers: List<String>): String? {
        val (host, port) = parseHostPort(hostPort, 443)
        AppLog.d(TAG, "CONNECT $host:$port")

        val clientIp = client.inetAddress.hostAddress ?: "unknown"
        val connId = tracker?.add(ProxyConnection(
            clientIp = clientIp,
            clientPort = client.port,
            targetHost = host,
            targetPort = port,
            protocol = "HTTP"
        ))

        val channel = sshManager.openDirectChannel(host, port)
        if (channel == null) {
            sendError(client, 502, "Bad Gateway - SSH channel failed")
            return connId
        }

        try {
            channel.connect(10000)
        } catch (e: Exception) {
            AppLog.e(TAG, "Channel connect failed: $host:$port", e)
            sendError(client, 502, "Bad Gateway - Connection failed")
            return connId
        }

        val output = client.getOutputStream()
        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        output.flush()

        val remoteIn = channel.inputStream
        val remoteOut = channel.outputStream

        val t1 = relay(client.getInputStream(), remoteOut, "client->tunnel")
        val t2 = relay(remoteIn, output, "tunnel->client")

        try { t1.join() } catch (_: Exception) {}
        try { t2.join() } catch (_: Exception) {}

        channel.disconnect()
        return connId
    }

    private fun handleHttp(
        client: Socket,
        method: String,
        uri: String,
        httpVersion: String,
        headers: List<String>,
        reader: BufferedReader
    ): String? {
        val url = if (uri.startsWith("http://")) uri else "http://$uri"
        val hostPortPath = url.removePrefix("http://")
        val slashIndex = hostPortPath.indexOf('/')
        val hostPort = if (slashIndex >= 0) hostPortPath.substring(0, slashIndex) else hostPortPath
        val path = if (slashIndex >= 0) hostPortPath.substring(slashIndex) else "/"

        val (host, port) = parseHostPort(hostPort, 80)
        AppLog.d(TAG, "$method $host:$port$path")

        val clientIp = client.inetAddress.hostAddress ?: "unknown"
        val connId = tracker?.add(ProxyConnection(
            clientIp = clientIp,
            clientPort = client.port,
            targetHost = host,
            targetPort = port,
            protocol = "HTTP"
        ))

        val channel = sshManager.openDirectChannel(host, port)
        if (channel == null) {
            sendError(client, 502, "Bad Gateway")
            return connId
        }

        try {
            channel.connect(10000)
        } catch (e: Exception) {
            sendError(client, 502, "Bad Gateway - Connection failed")
            return connId
        }

        val remoteOut = channel.outputStream
        val remoteIn = channel.inputStream

        remoteOut.write("$method $path $httpVersion\r\n".toByteArray())

        for (header in headers) {
            if (!header.startsWith("Proxy-", ignoreCase = true)) {
                remoteOut.write("$header\r\n".toByteArray())
            }
        }
        remoteOut.write("\r\n".toByteArray())
        remoteOut.flush()

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
        return connId
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
