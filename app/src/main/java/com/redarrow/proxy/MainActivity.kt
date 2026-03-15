package com.redarrow.proxy

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.redarrow.proxy.databinding.ActivityMainBinding
import com.redarrow.proxy.model.ConnectionConfig
import com.redarrow.proxy.model.ProxyConnection
import com.redarrow.proxy.model.TunnelState
import com.redarrow.proxy.service.TunnelService
import com.redarrow.proxy.ssh.KeyManager
import com.redarrow.proxy.util.AppLog
import com.redarrow.proxy.ui.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var tunnelService: TunnelService? = null
    private var serviceBound = false

    private var selectedKeyContent: String = ""
    private var selectedKeyFileName: String = ""

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
    ) { /* continue regardless */ }

    private val keyFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { readKeyFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved theme before setContentView
        applySavedTheme()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()
        loadSavedConfig()
        setupAuthToggle()
        setupKeyFilePicker()
        setupConnectButton()
        setupSettings()
        setupLogCard()
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

    // ==================== Settings ====================

    private fun applySavedTheme() {
        val prefs = getSharedPreferences("red_arrow_settings", Context.MODE_PRIVATE)
        when (prefs.getString("theme", "system")) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun setupSettings() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ==================== Notification ====================

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ==================== Config ====================

    private fun loadSavedConfig() {
        val config = viewModel.loadConfig()
        binding.apply {
            etHost.setText(config.host)
            etPort.setText(config.port.toString())
            etUsername.setText(config.username)
            etPassword.setText(config.password)
            etKeyPassphrase.setText(config.privateKeyPassphrase)
            etSocksPort.setText(config.socksPort.toString())
            etHttpPort.setText(config.httpPort.toString())
            etProxyUsername.setText(config.proxyUsername)
            etProxyPassword.setText(config.proxyPassword)
        }
        selectedKeyContent = config.privateKey
        selectedKeyFileName = config.privateKeyFileName
        if (selectedKeyFileName.isNotBlank()) {
            binding.tvKeyFileName.text = selectedKeyFileName
        }
        when (config.authMethod) {
            ConnectionConfig.AuthMethod.PASSWORD -> binding.btnAuthPassword.isChecked = true
            ConnectionConfig.AuthMethod.PUBLIC_KEY -> binding.btnAuthKey.isChecked = true
        }
        updateAuthVisibility(config.authMethod == ConnectionConfig.AuthMethod.PUBLIC_KEY)
    }

    private fun setupAuthToggle() {
        binding.toggleAuthMethod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                updateAuthVisibility(checkedId == R.id.btnAuthKey)
            }
        }
    }

    private fun updateAuthVisibility(isKey: Boolean) {
        binding.layoutPassword.visibility = if (isKey) View.GONE else View.VISIBLE
        binding.layoutKeyFile.visibility = if (isKey) View.VISIBLE else View.GONE
    }

    private fun setupKeyFilePicker() {
        binding.btnSelectKey.setOnClickListener {
            keyFileLauncher.launch(arrayOf("*/*"))
        }

        binding.btnGenerateKey.setOnClickListener {
            showGenerateKeyDialog()
        }

        binding.btnSendPubKey.setOnClickListener {
            sendPublicKeyToServer()
        }
    }

    private fun showGenerateKeyDialog() {
        val types = arrayOf("Ed25519", "RSA (4096)")
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.key_type_title))
            .setItems(types) { _, which ->
                val passphrase = binding.etKeyPassphrase.text.toString()
                try {
                    val (privKey, pubKey) = when (which) {
                        0 -> KeyManager.generateEd25519(passphrase)
                        else -> KeyManager.generateRSA(passphrase)
                    }
                    selectedKeyContent = privKey
                    selectedKeyFileName = if (which == 0) "ed25519_red-arrow" else "rsa_red-arrow"
                    binding.tvKeyFileName.text = selectedKeyFileName
                    Toast.makeText(this,
                        getString(R.string.key_generated, selectedKeyFileName),
                        Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    binding.tvError.apply {
                        text = e.message
                        visibility = View.VISIBLE
                    }
                }
            }
            .show()
    }

    private fun sendPublicKeyToServer() {
        if (selectedKeyContent.isBlank()) {
            Toast.makeText(this, getString(R.string.error_no_key), Toast.LENGTH_SHORT).show()
            return
        }

        val host = binding.etHost.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        if (host.isBlank() || username.isBlank()) {
            Toast.makeText(this, getString(R.string.error_fill_server), Toast.LENGTH_SHORT).show()
            return
        }

        val config = buildConfig()
        Toast.makeText(this, getString(R.string.sending_pubkey), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val passphrase = binding.etKeyPassphrase.text.toString()
                val pubKey = KeyManager.extractPublicKey(selectedKeyContent, passphrase)
                val result = KeyManager.sendPublicKey(config, pubKey)
                result.fold(
                    onSuccess = {
                        Toast.makeText(this@MainActivity,
                            getString(R.string.pubkey_sent), Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        Toast.makeText(this@MainActivity,
                            getString(R.string.pubkey_send_failed, e.message),
                            Toast.LENGTH_LONG).show()
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity,
                    getString(R.string.pubkey_send_failed, e.message),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun readKeyFile(uri: Uri) {
        try {
            val fileName = getFileName(uri) ?: "unknown_key"
            val content = contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: throw Exception(getString(R.string.error_read_key, "null stream"))

            selectedKeyContent = content
            selectedKeyFileName = fileName
            binding.tvKeyFileName.text = fileName
        } catch (e: Exception) {
            binding.tvError.apply {
                text = getString(R.string.error_read_key, e.message)
                visibility = View.VISIBLE
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    // ==================== Connect ====================

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
                        text = getString(R.string.error_incomplete)
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
        val isKey = binding.btnAuthKey.isChecked
        return ConnectionConfig(
            host = binding.etHost.text.toString().trim(),
            port = binding.etPort.text.toString().toIntOrNull() ?: 22,
            username = binding.etUsername.text.toString().trim(),
            password = if (!isKey) binding.etPassword.text.toString() else "",
            privateKey = if (isKey) selectedKeyContent else "",
            privateKeyFileName = if (isKey) selectedKeyFileName else "",
            privateKeyPassphrase = if (isKey) binding.etKeyPassphrase.text.toString() else "",
            authMethod = if (isKey) ConnectionConfig.AuthMethod.PUBLIC_KEY
                         else ConnectionConfig.AuthMethod.PASSWORD,
            socksPort = binding.etSocksPort.text.toString().toIntOrNull() ?: 1080,
            httpPort = binding.etHttpPort.text.toString().toIntOrNull() ?: 8080,
            proxyUsername = binding.etProxyUsername.text.toString(),
            proxyPassword = binding.etProxyPassword.text.toString(),
        )
    }

    // ==================== State ====================

    private fun setupLogCard() {
        binding.btnClearLog.setOnClickListener {
            AppLog.clear()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            tunnelService?.state?.collectLatest { state ->
                updateUI(state)
            }
        }
        lifecycleScope.launch {
            tunnelService?.connectionTracker?.activeConnections?.collectLatest { connections ->
                updateConnectionsUI(connections)
            }
        }
        lifecycleScope.launch {
            AppLog.logs.collectLatest { lines ->
                binding.tvLog.text = lines.joinToString("\n")
                binding.scrollLog.post {
                    binding.scrollLog.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun updateUI(state: TunnelState) {
        binding.apply {
            when (state.status) {
                TunnelState.Status.DISCONNECTED -> {
                    tvStatus.text = getString(R.string.status_disconnected)
                    statusDot.setBackgroundResource(R.drawable.status_dot_red)
                    btnConnect.text = getString(R.string.btn_connect)
                    btnConnect.isEnabled = true
                    tvProxyInfo.visibility = View.GONE
                    tvUptime.text = ""
                    cardConnections.visibility = View.GONE
                    setFieldsEnabled(true)
                }
                TunnelState.Status.CONNECTING -> {
                    tvStatus.text = getString(R.string.status_connecting)
                    statusDot.setBackgroundResource(R.drawable.status_dot_yellow)
                    btnConnect.text = getString(R.string.btn_cancel)
                    btnConnect.isEnabled = true
                    setFieldsEnabled(false)
                }
                TunnelState.Status.CONNECTED -> {
                    tvStatus.text = getString(R.string.status_connected, state.connectedHost)
                    statusDot.setBackgroundResource(R.drawable.status_dot_green)
                    btnConnect.text = getString(R.string.btn_disconnect)
                    btnConnect.isEnabled = true
                    tvProxyInfo.apply {
                        text = "SOCKS5  0.0.0.0:${state.socksPort}\nHTTP      0.0.0.0:${state.httpPort}"
                        visibility = View.VISIBLE
                    }
                    val secs = state.uptimeSeconds
                    tvUptime.text = String.format("%02d:%02d:%02d", secs / 3600, secs % 3600 / 60, secs % 60)
                    setFieldsEnabled(false)
                }
                TunnelState.Status.ERROR -> {
                    tvStatus.text = getString(R.string.status_error)
                    statusDot.setBackgroundResource(R.drawable.status_dot_red)
                    btnConnect.text = getString(R.string.btn_retry)
                    btnConnect.isEnabled = true
                    tvError.apply {
                        text = state.errorMessage
                        visibility = View.VISIBLE
                    }
                    tvProxyInfo.visibility = View.GONE
                    cardConnections.visibility = View.GONE
                    setFieldsEnabled(true)
                }
            }
        }
    }

    private fun updateConnectionsUI(connections: List<ProxyConnection>) {
        binding.apply {
            if (connections.isEmpty()) {
                cardConnections.visibility = if (tunnelService?.state?.value?.isConnected == true) View.VISIBLE else View.GONE
                tvConnections.text = getString(R.string.no_active_connections)
                tvConnectionCount.text = ""
            } else {
                cardConnections.visibility = View.VISIBLE
                tvConnectionCount.text = getString(R.string.connection_count, connections.size)
                // 按 IP 分组统计连接数
                val grouped = connections.groupBy { it.clientIp }
                tvConnections.text = grouped.entries
                    .sortedByDescending { it.value.size }
                    .joinToString("\n") { (ip, conns) ->
                        "$ip  \u2014  ${conns.size} conn"
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
            etKeyPassphrase.isEnabled = enabled
            btnSelectKey.isEnabled = enabled
            btnGenerateKey.isEnabled = enabled
            btnSendPubKey.isEnabled = enabled
            etSocksPort.isEnabled = enabled
            etHttpPort.isEnabled = enabled
            etProxyUsername.isEnabled = enabled
            etProxyPassword.isEnabled = enabled
            btnAuthPassword.isEnabled = enabled
            btnAuthKey.isEnabled = enabled
        }
    }
}
