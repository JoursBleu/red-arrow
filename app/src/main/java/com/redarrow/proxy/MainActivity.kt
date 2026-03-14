package com.redarrow.proxy

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.redarrow.proxy.databinding.ActivityMainBinding
import com.redarrow.proxy.model.ConnectionConfig
import com.redarrow.proxy.model.TunnelState
import com.redarrow.proxy.service.TunnelService
import com.redarrow.proxy.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var tunnelService: TunnelService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            tunnelService = (binder as TunnelService.TunnelBinder).getService()
            serviceBound = true
            observeState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            tunnelService = null
            serviceBound = false
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 不管结果如何都继续 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()
        loadSavedConfig()
        setupAuthToggle()
        setupConnectButton()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, TunnelService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadSavedConfig() {
        val config = viewModel.loadConfig()
        binding.apply {
            etHost.setText(config.host)
            etPort.setText(config.port.toString())
            etUsername.setText(config.username)
            etPassword.setText(config.password)
            etPrivateKey.setText(config.privateKey)
            etSocksPort.setText(config.socksPort.toString())
            etHttpPort.setText(config.httpPort.toString())
            when (config.authMethod) {
                ConnectionConfig.AuthMethod.PASSWORD -> chipPassword.isChecked = true
                ConnectionConfig.AuthMethod.PUBLIC_KEY -> chipKey.isChecked = true
            }
        }
    }

    private fun setupAuthToggle() {
        binding.chipGroupAuth.setOnCheckedStateChangeListener { _, checkedIds ->
            val isKey = checkedIds.contains(R.id.chipKey)
            binding.tilPassword.visibility = if (isKey) View.GONE else View.VISIBLE
            binding.tilPrivateKey.visibility = if (isKey) View.VISIBLE else View.GONE
        }
    }

    private fun setupConnectButton() {
        binding.btnConnect.setOnClickListener {
            val service = tunnelService ?: return@setOnClickListener
            val state = service.state.value

            if (state.isConnected || state.isConnecting) {
                lifecycleScope.launch { service.disconnect() }
            } else {
                val config = buildConfig()
                if (!config.isValid()) {
                    binding.tvError.apply {
                        text = "请填写完整的连接信息"
                        visibility = View.VISIBLE
                    }
                    return@setOnClickListener
                }
                viewModel.saveConfig(config)
                binding.tvError.visibility = View.GONE
                service.connect(config)
            }
        }
    }

    private fun buildConfig(): ConnectionConfig {
        val isKey = binding.chipKey.isChecked
        return ConnectionConfig(
            host = binding.etHost.text.toString().trim(),
            port = binding.etPort.text.toString().toIntOrNull() ?: 22,
            username = binding.etUsername.text.toString().trim(),
            password = if (!isKey) binding.etPassword.text.toString() else "",
            privateKey = if (isKey) binding.etPrivateKey.text.toString() else "",
            authMethod = if (isKey) ConnectionConfig.AuthMethod.PUBLIC_KEY
                         else ConnectionConfig.AuthMethod.PASSWORD,
            socksPort = binding.etSocksPort.text.toString().toIntOrNull() ?: 1080,
            httpPort = binding.etHttpPort.text.toString().toIntOrNull() ?: 8080,
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            tunnelService?.state?.collectLatest { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: TunnelState) {
        binding.apply {
            when (state.status) {
                TunnelState.Status.DISCONNECTED -> {
                    tvStatus.text = "未连接"
                    statusDot.setBackgroundResource(R.drawable.status_dot_red)
                    btnConnect.text = "连接"
                    btnConnect.isEnabled = true
                    tvProxyInfo.visibility = View.GONE
                    tvUptime.text = ""
                    setFieldsEnabled(true)
                }
                TunnelState.Status.CONNECTING -> {
                    tvStatus.text = "正在连接..."
                    statusDot.setBackgroundResource(R.drawable.status_dot_yellow)
                    btnConnect.text = "取消"
                    btnConnect.isEnabled = true
                    setFieldsEnabled(false)
                }
                TunnelState.Status.CONNECTED -> {
                    tvStatus.text = "已连接 ${state.connectedHost}"
                    statusDot.setBackgroundResource(R.drawable.status_dot_green)
                    btnConnect.text = "断开"
                    btnConnect.isEnabled = true
                    tvProxyInfo.apply {
                        text = "SOCKS5  127.0.0.1:${state.socksPort}\n" +
                               "HTTP      127.0.0.1:${state.httpPort}"
                        visibility = View.VISIBLE
                    }
                    val secs = state.uptimeSeconds
                    tvUptime.text = String.format("%02d:%02d:%02d", secs / 3600, secs % 3600 / 60, secs % 60)
                    setFieldsEnabled(false)
                }
                TunnelState.Status.ERROR -> {
                    tvStatus.text = "连接失败"
                    statusDot.setBackgroundResource(R.drawable.status_dot_red)
                    btnConnect.text = "重试"
                    btnConnect.isEnabled = true
                    tvError.apply {
                        text = state.errorMessage
                        visibility = View.VISIBLE
                    }
                    tvProxyInfo.visibility = View.GONE
                    setFieldsEnabled(true)
                }
            }
        }
    }

    private fun setFieldsEnabled(enabled: Boolean) {
        binding.apply {
            etHost.isEnabled = enabled
            etPort.isEnabled = enabled
            etUsername.isEnabled = enabled
            etPassword.isEnabled = enabled
            etPrivateKey.isEnabled = enabled
            etSocksPort.isEnabled = enabled
            etHttpPort.isEnabled = enabled
            chipPassword.isEnabled = enabled
            chipKey.isEnabled = enabled
        }
    }
}
