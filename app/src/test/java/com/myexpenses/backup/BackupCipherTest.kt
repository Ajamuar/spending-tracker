package com.myexpenses.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class BackupCipherTest {
    private val cipher = BackupCipher()

    @Test
    fun encryptedBackupRoundTrips() {
        val plaintext = """{"version":1,"merchant":"Synthetic café"}"""
            .toByteArray(Charsets.UTF_8)
        val passphrase = "correct horse battery staple".toCharArray()
        val output = ByteArrayOutputStream()

        cipher.encrypt(plaintext, output, passphrase)
        val restored = cipher.decrypt(
            output.toByteArray().inputStream(),
            "correct horse battery staple".toCharArray(),
        )

        assertArrayEquals(plaintext, restored)
    }

    @Test
    fun wrongPassphraseCannotDecryptBackup() {
        val output = ByteArrayOutputStream()
        cipher.encrypt(
            "private ledger".toByteArray(),
            output,
            "right-passphrase".toCharArray(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            cipher.decrypt(output.toByteArray().inputStream(), "wrong-passphrase".toCharArray())
        }
    }

    @Test
    fun tamperingIsDetectedByAuthenticatedEncryption() {
        val output = ByteArrayOutputStream()
        cipher.encrypt(
            "private ledger".toByteArray(),
            output,
            "right-passphrase".toCharArray(),
        )
        val tampered = output.toByteArray().also {
            it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte()
        }

        assertThrows(IllegalArgumentException::class.java) {
            cipher.decrypt(tampered.inputStream(), "right-passphrase".toCharArray())
        }
    }

    @Test
    fun rejectsShortPassphrases() {
        assertThrows(IllegalArgumentException::class.java) {
            cipher.encrypt(ByteArray(0), ByteArrayOutputStream(), "short".toCharArray())
        }
    }
}
