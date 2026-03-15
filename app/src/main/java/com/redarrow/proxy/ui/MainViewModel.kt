package com.redarrow.proxy.ui

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import com.redarrow.proxy.model.ConnectionConfig

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: SharedPreferences =
        app.getSharedPreferences("red_arrow_config", android.content.Context.MODE_PRIVATE)

    fun saveConfig(config: ConnectionConfig) {
        prefs.edit()
            .putString("host", config.host)
            .putInt("port", config.port)
            .putString("username", config.username)
            .putString("password", config.password)
            .putString("privateKey", config.privateKey)
            .putString("privateKeyFileName", config.privateKeyFileName)
            .putString("privateKeyPassphrase", config.privateKeyPassphrase)
            .putString("authMethod", config.authMethod.name)
            .putInt("socksPort", config.socksPort)
            .putInt("httpPort", config.httpPort)
            .putString("proxyUsername", config.proxyUsername)
            .putString("proxyPassword", config.proxyPassword)
            .putInt("keepAliveInterval", config.keepAliveInterval)
            .apply()
    }

    fun loadConfig(): ConnectionConfig {
        return ConnectionConfig(
            host = prefs.getString("host", "") ?: "",
            port = prefs.getInt("port", 22),
            username = prefs.getString("username", "") ?: "",
            password = prefs.getString("password", "") ?: "",
            privateKey = prefs.getString("privateKey", "") ?: "",
            privateKeyFileName = prefs.getString("privateKeyFileName", "") ?: "",
            privateKeyPassphrase = prefs.getString("privateKeyPassphrase", "") ?: "",
            authMethod = try {
                ConnectionConfig.AuthMethod.valueOf(
                    prefs.getString("authMethod", "PASSWORD") ?: "PASSWORD"
                )
            } catch (_: Exception) { ConnectionConfig.AuthMethod.PASSWORD },
            socksPort = prefs.getInt("socksPort", 1080),
            httpPort = prefs.getInt("httpPort", 8080),
            proxyUsername = prefs.getString("proxyUsername", "user") ?: "user",
            proxyPassword = prefs.getString("proxyPassword", "") ?: "",
            keepAliveInterval = prefs.getInt("keepAliveInterval", 30),
        )
    }
}
