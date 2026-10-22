package com.ajamuar.ledger

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Backup {
    private val magic = "RLEDGER1".toByteArray()
    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
    fun encrypt(data: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 12) { "Use a backup password of at least 12 characters" }
        require(data.size <= 16_000_000)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, derive(password, salt)) }
        cipher.updateAAD(magic)
        return magic + salt + cipher.iv + cipher.doFinal(data)
    }
    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        require(data.size in 52..16_001_000 && data.take(8).toByteArray().contentEquals(magic)) { "Unsupported or invalid backup" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, derive(password, data.copyOfRange(8, 24)), GCMParameterSpec(128, data.copyOfRange(24, 36)))
        }
        cipher.updateAAD(magic)
        return cipher.doFinal(data.copyOfRange(36, data.size))
    }
}
