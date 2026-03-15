package com.redarrow.proxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.redarrow.proxy.model.StoredKey
import com.redarrow.proxy.ssh.KeyStoreManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KeysActivity : AppCompatActivity() {

    private lateinit var keyStore: KeyStoreManager
    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView

    private val importKeyLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importKeyFromFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keys)

        keyStore = KeyStoreManager(this)
        container = findViewById(R.id.keyListContainer)
        tvEmpty = findViewById(R.id.tvEmpty)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddKey).setOnClickListener { showAddKeyDialog() }

        refreshList()
    }

    private fun refreshList() {
        container.removeAllViews()
        val keys = keyStore.getAll()

        if (keys.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            return
        }
        tvEmpty.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        for (key in keys.sortedByDescending { it.createdAt }) {
            val view = inflater.inflate(R.layout.item_key, container, false)

            view.findViewById<TextView>(R.id.tvKeyName).text = key.name
            view.findViewById<TextView>(R.id.tvKeyType).text = key.type
            view.findViewById<TextView>(R.id.tvPubKeyPreview).text =
                if (key.publicKey.isNotBlank()) key.publicKey.take(60) + "..."
                else getString(R.string.no_pubkey)

            view.findViewById<MaterialButton>(R.id.btnCopyPubKey).setOnClickListener {
                if (key.publicKey.isBlank()) {
                    Toast.makeText(this, getString(R.string.no_pubkey), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("public_key", key.publicKey))
                Toast.makeText(this, getString(R.string.pubkey_copied), Toast.LENGTH_SHORT).show()
            }

            view.findViewById<MaterialButton>(R.id.btnShareKey).setOnClickListener {
                if (key.publicKey.isBlank()) {
                    Toast.makeText(this, getString(R.string.no_pubkey), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, key.publicKey)
                    putExtra(Intent.EXTRA_SUBJECT, "SSH Public Key - ${key.name}")
                }
                startActivity(Intent.createChooser(intent, getString(R.string.btn_share_pubkey)))
            }

            view.findViewById<MaterialButton>(R.id.btnDeleteKey).setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.confirm_delete_key))
                    .setMessage(key.name)
                    .setPositiveButton(getString(R.string.btn_delete_key)) { _, _ ->
                        keyStore.delete(key.id)
                        refreshList()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }

            container.addView(view)
        }
    }

    private fun showAddKeyDialog() {
        val options = arrayOf(
            getString(R.string.generate_ed25519),
            getString(R.string.generate_rsa),
            getString(R.string.import_from_file),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_add_key))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showNameInputDialog("Ed25519")
                    1 -> showNameInputDialog("RSA")
                    2 -> importKeyLauncher.launch(arrayOf("*/*"))
                }
            }
            .show()
    }

    private fun showNameInputDialog(type: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_key_name)
            setText("${type.lowercase()}_${System.currentTimeMillis() / 1000}")
            setPadding(64, 32, 64, 32)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.key_name_title))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_generate_key)) { _, _ ->
                val name = input.text.toString().ifBlank { type.lowercase() }
                try {
                    keyStore.generateAndSave(name, type)
                    refreshList()
                    Toast.makeText(this, getString(R.string.key_generated, name),
                        Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importKeyFromFile(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: return

            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported_key"

            val input = EditText(this).apply {
                hint = getString(R.string.hint_key_name)
                setText(fileName)
                setPadding(64, 32, 64, 32)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.key_name_title))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val name = input.text.toString().ifBlank { fileName }
                    try {
                        keyStore.importAndSave(name, content)
                        refreshList()
                        Toast.makeText(this, getString(R.string.key_imported),
                            Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_read_key, e.message),
                Toast.LENGTH_LONG).show()
        }
    }
}
