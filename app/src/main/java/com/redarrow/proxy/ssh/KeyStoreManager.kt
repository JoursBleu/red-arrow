package com.redarrow.proxy.ssh

import android.content.Context
import android.content.SharedPreferences
import com.redarrow.proxy.model.StoredKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 密钥持久化存储管理器
 * 使用 SharedPreferences 存储密钥列表（JSON 序列化）
 * 数据存储在应用内部目录，卸载时自动删除
 */
class KeyStoreManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("red_arrow_keys", Context.MODE_PRIVATE)

    fun getAll(): List<StoredKey> {
        val json = prefs.getString("keys", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StoredKey(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    type = o.optString("type", "unknown"),
                    privateKey = o.getString("privateKey"),
                    publicKey = o.optString("publicKey", ""),
                    passphrase = o.optString("passphrase", ""),
                    createdAt = o.optLong("createdAt", 0),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(key: StoredKey) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == key.id }
        if (idx >= 0) list[idx] = key else list.add(key)
        persist(list)
    }

    fun delete(id: String) {
        val list = getAll().filter { it.id != id }
        persist(list)
    }

    fun get(id: String): StoredKey? = getAll().find { it.id == id }

    fun generateAndSave(name: String, type: String, passphrase: String = ""): StoredKey {
        val (priv, pub) = when (type) {
            "Ed25519" -> KeyManager.generateEd25519(passphrase)
            else -> KeyManager.generateRSA(passphrase)
        }
        val key = StoredKey(
            id = UUID.randomUUID().toString(),
            name = name,
            type = type,
            privateKey = priv,
            publicKey = pub,
            passphrase = passphrase,
        )
        save(key)
        return key
    }

    fun importAndSave(name: String, privateKeyContent: String, passphrase: String = ""): StoredKey {
        val pub = KeyManager.extractPublicKey(privateKeyContent, passphrase)
        require(pub.isNotBlank()) { "Failed to extract public key" }
        val key = StoredKey(
            id = UUID.randomUUID().toString(),
            name = name,
            type = "imported",
            privateKey = privateKeyContent,
            publicKey = pub,
            passphrase = passphrase,
        )
        save(key)
        return key
    }

    private fun persist(list: List<StoredKey>) {
        val arr = JSONArray()
        list.forEach { k ->
            arr.put(JSONObject().apply {
                put("id", k.id)
                put("name", k.name)
                put("type", k.type)
                put("privateKey", k.privateKey)
                put("publicKey", k.publicKey)
                put("passphrase", k.passphrase)
                put("createdAt", k.createdAt)
            })
        }
        prefs.edit().putString("keys", arr.toString()).apply()
    }
}
