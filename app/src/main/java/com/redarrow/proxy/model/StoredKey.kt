package com.redarrow.proxy.model

/**
 * 存储的 SSH 密钥对
 */
data class StoredKey(
    val id: String,                  // UUID
    val name: String,                // 显示名称
    val type: String,                // "Ed25519" / "RSA" / "imported"
    val privateKey: String,          // 私钥内容
    val publicKey: String,           // 公钥内容
    val createdAt: Long = System.currentTimeMillis(),
)
