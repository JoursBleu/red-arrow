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
import com.redarrow.proxy.proxy.ConnectionTracker
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
    val connectionTracker = ConnectionTracker()
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
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_connecting)))
        acquireWakeLock()

        scope.launch {
            val result = sshManager.connect(config)
            result.fold(
                onSuccess = {
                    try {
                        socksServer = Socks5Server(sshManager, config.socksPort, config.proxyPassword, connectionTracker).also { it.start() }
                        httpProxyServer = HttpProxyServer(sshManager, config.httpPort, config.proxyPassword, connectionTracker).also { it.start() }

                        _state.value = TunnelState(
                            status = TunnelState.Status.CONNECTED,
                            socksPort = config.socksPort,
                            httpPort = config.httpPort,
                            connectedHost = "${config.host}:${config.port}",
                            connectedAt = System.currentTimeMillis(),
                        )
                        updateNotification(getString(R.string.notif_connected, config.host))
                        Log.i(TAG, "Tunnel active: SOCKS5=:${config.socksPort} HTTP=:${config.httpPort}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start proxy servers", e)
                        sshManager.disconnect()
                        _state.value = TunnelState(
                            status = TunnelState.Status.ERROR,
                            errorMessage = getString(R.string.error_proxy_start_failed, e.message)
                        )
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                },
                onFailure = { e ->
                    _state.value = TunnelState(
                        status = TunnelState.Status.ERROR,
                        errorMessage = e.message ?: getString(R.string.error_connect_failed)
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
        connectionTracker.clear()
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
