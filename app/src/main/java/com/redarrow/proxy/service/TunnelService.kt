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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.redarrow.proxy.MainActivity
import com.redarrow.proxy.R
import com.redarrow.proxy.model.ConnectionConfig
import com.redarrow.proxy.model.TunnelState
import com.redarrow.proxy.proxy.HttpProxyServer
import com.redarrow.proxy.proxy.Socks5Server
import com.redarrow.proxy.ssh.SshManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TunnelService : Service() {
    companion object {
        private const val TAG = "TunnelService"
        private const val CHANNEL_ID = "tunnel_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.redarrow.proxy.CONNECT"
        const val ACTION_DISCONNECT = "com.redarrow.proxy.DISCONNECT"
    }

    inner class TunnelBinder : Binder() {
        fun getService(): TunnelService = this@TunnelService
    }

    private val binder = TunnelBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sshManager = SshManager()
    private var socksServer: Socks5Server? = null
    private var httpProxyServer: HttpProxyServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state

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

        _state.value = TunnelState(status = TunnelState.Status.CONNECTING)
        startForeground(NOTIFICATION_ID, buildNotification("正在连接..."))
        acquireWakeLock()

        scope.launch {
            val result = sshManager.connect(config)
            result.fold(
                onSuccess = {
                    try {
                        // 启动 SOCKS5 代理
                        socksServer = Socks5Server(sshManager, config.socksPort).also { it.start() }
                        // 启动 HTTP 代理
                        httpProxyServer = HttpProxyServer(sshManager, config.httpPort).also { it.start() }

                        _state.value = TunnelState(
                            status = TunnelState.Status.CONNECTED,
                            socksPort = config.socksPort,
                            httpPort = config.httpPort,
                            connectedHost = "${config.host}:${config.port}",
                            connectedAt = System.currentTimeMillis(),
                        )
                        updateNotification("已连接 ${config.host}")
                        Log.i(TAG, "Tunnel active: SOCKS5=:${config.socksPort} HTTP=:${config.httpPort}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start proxy servers", e)
                        sshManager.disconnect()
                        _state.value = TunnelState(
                            status = TunnelState.Status.ERROR,
                            errorMessage = "代理服务器启动失败: ${e.message}"
                        )
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                },
                onFailure = { e ->
                    _state.value = TunnelState(
                        status = TunnelState.Status.ERROR,
                        errorMessage = e.message ?: "连接失败"
                    )
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            )
        }
    }

    suspend fun disconnect() {
        socksServer?.stop()
        httpProxyServer?.stop()
        sshManager.disconnect()
        socksServer = null
        httpProxyServer = null

        _state.value = TunnelState(status = TunnelState.Status.DISCONNECTED)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.launch { disconnect() }
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SSH 隧道服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "SSH 隧道运行状态"
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
            .setContentTitle("Red Arrow")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification, "断开", disconnectIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RedArrow::TunnelWakeLock"
        ).apply { acquire(24 * 60 * 60 * 1000L) } // 24h max
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
