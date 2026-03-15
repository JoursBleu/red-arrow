package com.redarrow.proxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import com.redarrow.proxy.util.AppLog
import androidx.core.app.NotificationCompat
import com.redarrow.proxy.MainActivity
import com.redarrow.proxy.R
import com.redarrow.proxy.model.ConnectionConfig
import com.redarrow.proxy.model.TunnelState
import com.redarrow.proxy.proxy.HttpProxyServer
import com.redarrow.proxy.proxy.ConnectionTracker
import com.redarrow.proxy.proxy.Socks5Server
import com.redarrow.proxy.proxy.TrafficCounter
import com.redarrow.proxy.ssh.SshManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TunnelService : Service() {
    companion object {
        private const val TAG = "TunnelService"
        private const val CHANNEL_ID = "tunnel_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.redarrow.proxy.CONNECT"
        const val ACTION_DISCONNECT = "com.redarrow.proxy.DISCONNECT"
        private const val RECONNECT_BASE_DELAY = 3000L    // 3s
        private const val RECONNECT_MAX_DELAY = 60000L    // 60s
        private const val HEALTH_CHECK_INTERVAL = 10000L  // 10s
    }

    inner class TunnelBinder : Binder() {
        fun getService(): TunnelService = this@TunnelService
    }

    private val binder = TunnelBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sshManager = SshManager()
    val connectionTracker = ConnectionTracker()
    private var socksServer: Socks5Server? = null
    private var httpProxyServer: HttpProxyServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state

    private var currentConfig: ConnectionConfig? = null
    private var healthCheckJob: Job? = null
    private var uptimeJob: Job? = null
    private var reconnectAttempts = 0
    private var userDisconnected = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                scope.launch { disconnect() }
            }
        }
        return START_STICKY
    }

    fun connect(config: ConnectionConfig) {
        if (_state.value.isConnected || _state.value.isConnecting) return

        currentConfig = config
        userDisconnected = false
        reconnectAttempts = 0
        TrafficCounter.reset()
        doConnect(config)
    }

    private fun doConnect(config: ConnectionConfig) {
        _state.value = TunnelState(status = TunnelState.Status.CONNECTING)
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_connecting)))
        acquireWakeLock()

        scope.launch {
            val result = sshManager.connect(config)
            result.fold(
                onSuccess = {
                    try {
                        socksServer = Socks5Server(sshManager, config.socksPort, config.proxyUsername, config.proxyPassword, connectionTracker).also { it.start() }
                        httpProxyServer = HttpProxyServer(sshManager, config.httpPort, config.proxyUsername, config.proxyPassword, connectionTracker).also { it.start() }

                        reconnectAttempts = 0
                        _state.value = TunnelState(
                            status = TunnelState.Status.CONNECTED,
                            socksPort = config.socksPort,
                            httpPort = config.httpPort,
                            connectedHost = "${config.host}:${config.port}",
                            connectedAt = System.currentTimeMillis(),
                        )
                        updateNotification(getString(R.string.notif_connected, config.host))
                        AppLog.i(TAG, "Tunnel active: SOCKS5=:${config.socksPort} HTTP=:${config.httpPort}")
                        startHealthCheck()
                        startUptimeCounter()
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Failed to start proxy servers", e)
                        sshManager.disconnect()
                        handleConnectionLost(getString(R.string.error_proxy_start_failed, e.message))
                    }
                },
                onFailure = { e ->
                    handleConnectionLost(e.message ?: getString(R.string.error_connect_failed))
                }
            )
        }
    }

    private fun handleConnectionLost(errorMsg: String) {
        stopProxyServers()
        connectionTracker.clear()
        healthCheckJob?.cancel()
        uptimeJob?.cancel()

        if (userDisconnected) {
            _state.value = TunnelState(status = TunnelState.Status.DISCONNECTED)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val config = currentConfig
        if (config != null && reconnectAttempts < 10) {
            reconnectAttempts++
            val delayMs = (RECONNECT_BASE_DELAY * reconnectAttempts).coerceAtMost(RECONNECT_MAX_DELAY)
            AppLog.w(TAG, "Connection lost: $errorMsg. Reconnecting in ${delayMs / 1000}s (attempt $reconnectAttempts)...")
            _state.value = TunnelState(
                status = TunnelState.Status.CONNECTING,
                errorMessage = "Reconnecting ($reconnectAttempts/10)..."
            )
            updateNotification("Reconnecting ($reconnectAttempts/10)...")

            scope.launch {
                delay(delayMs)
                if (!userDisconnected) {
                    doConnect(config)
                }
            }
        } else {
            _state.value = TunnelState(
                status = TunnelState.Status.ERROR,
                errorMessage = errorMsg
            )
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL)
                if (_state.value.isConnected && !sshManager.isConnected) {
                    AppLog.w(TAG, "SSH session lost, triggering reconnect")
                    handleConnectionLost("SSH session disconnected")
                    break
                }
            }
        }
    }

    private fun startUptimeCounter() {
        uptimeJob?.cancel()
        uptimeJob = scope.launch {
            while (isActive && _state.value.isConnected) {
                delay(1000)
                // Force StateFlow update for uptime display
                val current = _state.value
                if (current.isConnected) {
                    TrafficCounter.refreshSnapshot()
                    _state.value = current.copy(uptimeTick = current.uptimeTick + 1)
                }
            }
        }
    }

    suspend fun disconnect() {
        userDisconnected = true
        healthCheckJob?.cancel()
        uptimeJob?.cancel()
        stopProxyServers()
        sshManager.disconnect()
        connectionTracker.clear()
        currentConfig = null
        reconnectAttempts = 0

        _state.value = TunnelState(status = TunnelState.Status.DISCONNECTED)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopProxyServers() {
        socksServer?.stop()
        httpProxyServer?.stop()
        socksServer = null
        httpProxyServer = null
    }

    override fun onDestroy() {
        scope.launch { disconnect() }
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TunnelService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification, getString(R.string.btn_disconnect), disconnectIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RedArrow::TunnelWakeLock"
        ).apply { acquire(24 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
