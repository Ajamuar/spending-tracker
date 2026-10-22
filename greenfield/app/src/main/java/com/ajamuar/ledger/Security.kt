package com.ajamuar.ledger

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(): SecretKey {
        (store.getKey("ledger-wrapping", null) as? SecretKey)?.let { return it }
        check(!prefs.contains("db")) { "Device key is missing. Restore your encrypted backup; the ledger has not been reset." }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("ledger-wrapping", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized fun read(name: String): ByteArray? {
        val encoded = prefs.getString(name, null) ?: return null
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size > 12)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            doFinal(data.copyOfRange(12, data.size))
        }
    }
    @Synchronized fun write(name: String, value: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(prefs.edit().putString(name, Base64.encodeToString(cipher.iv + cipher.doFinal(value), Base64.NO_WRAP)).commit()) {
            "Unable to save encrypted settings"
        }
    }
    fun forget(name: String) { check(prefs.edit().remove(name).commit()) }
    fun databaseKey(): ByteArray = synchronized(keyLock) {
        read("db") ?: ByteArray(32).also {
            java.security.SecureRandom().nextBytes(it)
            write("db", it)
        }
    }
    private companion object { val keyLock = Any() }
}
