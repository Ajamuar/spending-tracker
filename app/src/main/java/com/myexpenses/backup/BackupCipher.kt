package com.myexpenses.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupCipher @Inject constructor() {
    fun encrypt(plaintext: ByteArray, output: OutputStream, passphrase: CharArray) {
        validatePassphrase(passphrase)
        val salt = ByteArray(SALT_SIZE).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_SIZE).also(SecureRandom()::nextBytes)
        val key = deriveKey(passphrase, salt)
        val ciphertext = Cipher.getInstance(CIPHER).run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(MAGIC)
            doFinal(plaintext)
        }
        DataOutputStream(output).use {
            it.write(MAGIC)
            it.write(salt)
            it.write(iv)
            it.write(ciphertext)
        }
    }

    fun decrypt(input: InputStream, passphrase: CharArray): ByteArray {
        validatePassphrase(passphrase)
        val data = readLimited(input)
        require(data.size > MAGIC.size + SALT_SIZE + IV_SIZE) { "Backup is empty or truncated" }
        val stream = DataInputStream(data.inputStream())
        val magic = ByteArray(MAGIC.size).also(stream::readFully)
        require(magic.contentEquals(MAGIC)) { "Not a My Expenses encrypted backup" }
        val salt = ByteArray(SALT_SIZE).also(stream::readFully)
        val iv = ByteArray(IV_SIZE).also(stream::readFully)
        val ciphertext = stream.readBytes()
        val key = deriveKey(passphrase, salt)
        return try {
            Cipher.getInstance(CIPHER).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                updateAAD(MAGIC)
                doFinal(ciphertext)
            }
        } catch (error: Exception) {
            throw IllegalArgumentException("Wrong passphrase or damaged backup", error)
        }
    }

    private fun validatePassphrase(passphrase: CharArray) {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must contain at least $MIN_PASSPHRASE_LENGTH characters"
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val specification = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(specification)
                .encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            specification.clearPassword()
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_FILE_BYTES) { "File is larger than the 25 MB safety limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MIN_PASSPHRASE_LENGTH = 8
        const val SALT_SIZE = 16
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
        const val PBKDF2_ITERATIONS = 310_000
        const val MAX_FILE_BYTES = 25 * 1024 * 1024
        const val CIPHER = "AES/GCM/NoPadding"
        val MAGIC = "MYEXP001".toByteArray(Charsets.US_ASCII)
    }
}
