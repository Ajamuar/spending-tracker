package com.myexpenses.backup

import com.myexpenses.data.ExpenseRepository
import com.myexpenses.data.model.AccountEntity
import com.myexpenses.data.model.AccountType
import com.myexpenses.data.model.BudgetEntity
import com.myexpenses.data.model.CategoryEntity
import com.myexpenses.data.model.LedgerSnapshot
import com.myexpenses.data.model.SourceType
import com.myexpenses.data.model.TransactionEntity
import com.myexpenses.data.model.TransactionStatus
import com.myexpenses.data.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.RoundingMode
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    private val repository: ExpenseRepository,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun exportEncrypted(output: OutputStream, passphrase: CharArray) =
        withContext(Dispatchers.IO) {
            require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
                "Backup passphrase must contain at least $MIN_PASSPHRASE_LENGTH characters"
            }
            val plaintext = json.encodeToString(repository.snapshot().toBackup())
                .toByteArray(Charsets.UTF_8)
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
            plaintext.fill(0)
        }

    suspend fun restoreEncrypted(input: InputStream, passphrase: CharArray) =
        withContext(Dispatchers.IO) {
            require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
                "Backup passphrase must contain at least $MIN_PASSPHRASE_LENGTH characters"
            }
            val data = readLimited(input)
            require(data.size > MAGIC.size + SALT_SIZE + IV_SIZE) { "Backup is empty or truncated" }
            val stream = DataInputStream(data.inputStream())
            val magic = ByteArray(MAGIC.size).also(stream::readFully)
            require(magic.contentEquals(MAGIC)) { "Not a My Expenses encrypted backup" }
            val salt = ByteArray(SALT_SIZE).also(stream::readFully)
            val iv = ByteArray(IV_SIZE).also(stream::readFully)
            val ciphertext = stream.readBytes()
            val key = deriveKey(passphrase, salt)
            val plaintext = try {
                Cipher.getInstance(CIPHER).run {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                    updateAAD(MAGIC)
                    doFinal(ciphertext)
                }
            } catch (error: Exception) {
                throw IllegalArgumentException("Wrong passphrase or damaged backup", error)
            }
            val envelope = json.decodeFromString<BackupEnvelope>(plaintext.toString(Charsets.UTF_8))
            require(envelope.version == BACKUP_VERSION) {
                "Unsupported backup version ${envelope.version}"
            }
            repository.restore(envelope.toSnapshot())
            plaintext.fill(0)
        }

    suspend fun exportCsv(output: OutputStream) = withContext(Dispatchers.IO) {
        val snapshot = repository.snapshot()
        output.bufferedWriter().use { writer ->
            writer.appendLine(CSV_HEADER.joinToString(","))
            snapshot.transactions.forEach { transaction ->
                writer.appendLine(
                    listOf(
                        transaction.id,
                        Instant.ofEpochMilli(transaction.occurredAt).toString(),
                        transaction.amountMinor.toBigDecimal().movePointLeft(2).toPlainString(),
                        transaction.type.name,
                        transaction.status.name,
                        transaction.merchant,
                        transaction.categoryId.orEmpty(),
                        transaction.accountId.orEmpty(),
                        transaction.source.name,
                        transaction.note,
                        transaction.isExcluded.toString(),
                        transaction.reference.orEmpty(),
                    ).map(::escapeCsv).joinToString(","),
                )
            }
        }
    }

    suspend fun importCsv(input: InputStream): Int = withContext(Dispatchers.IO) {
        val text = readLimited(input).toString(Charsets.UTF_8)
        val rows = parseCsv(text)
        require(rows.isNotEmpty()) { "CSV file is empty" }
        val header = rows.first().map { it.trim().lowercase() }
        val indexes = header.withIndex().associate { it.value to it.index }
        require("amount_inr" in indexes && "merchant" in indexes) {
            "CSV must contain amount_inr and merchant columns"
        }
        val entities = rows.drop(1).mapNotNull { row ->
            fun value(name: String): String = indexes[name]?.let { row.getOrNull(it) }.orEmpty()
            val amountMinor = value("amount_inr").toBigDecimalOrNull()
                ?.movePointRight(2)
                ?.setScale(0, RoundingMode.HALF_UP)
                ?.longValueExactOrNull()
                ?.takeIf { it > 0 }
                ?: return@mapNotNull null
            val type = enumOrDefault(value("type"), TransactionType.EXPENSE)
            val status = enumOrDefault(value("status"), TransactionStatus.POSTED)
            TransactionEntity(
                id = value("id").ifBlank { UUID.randomUUID().toString() },
                amountMinor = amountMinor,
                type = type,
                status = status,
                merchant = value("merchant").ifBlank { "CSV transaction" },
                categoryId = value("category_id").ifBlank { null },
                accountId = value("account_id").ifBlank { null },
                occurredAt = runCatching { Instant.parse(value("timestamp")).toEpochMilli() }
                    .getOrDefault(System.currentTimeMillis()),
                source = SourceType.CSV,
                note = value("note"),
                isExcluded = value("excluded").toBooleanStrictOrNull() ?: false,
                reference = value("reference").ifBlank { null },
            )
        }
        repository.importCsv(entities)
        entities.size
    }

    private fun LedgerSnapshot.toBackup() = BackupEnvelope(
        version = BACKUP_VERSION,
        exportedAt = System.currentTimeMillis(),
        transactions = transactions.map(TransactionEntity::toBackup),
        accounts = accounts.map(AccountEntity::toBackup),
        categories = categories.map(CategoryEntity::toBackup),
        budgets = budgets.map(BudgetEntity::toBackup),
    )

    private fun BackupEnvelope.toSnapshot() = LedgerSnapshot(
        transactions = transactions.map(TransactionBackup::toEntity),
        accounts = accounts.map(AccountBackup::toEntity),
        categories = categories.map(CategoryBackup::toEntity),
        budgets = budgets.map(BudgetBackup::toEntity),
    )

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

    private fun parseCsv(value: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                quoted && character == '"' && value.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                !quoted && character == ',' -> {
                    row.add(field.toString())
                    field.clear()
                }
                !quoted && (character == '\n' || character == '\r') -> {
                    if (character == '\r' && value.getOrNull(index + 1) == '\n') index++
                    row.add(field.toString())
                    field.clear()
                    if (row.any(String::isNotBlank)) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(character)
            }
            index++
        }
        row.add(field.toString())
        if (row.any(String::isNotBlank)) rows.add(row)
        return rows
    }

    private fun escapeCsv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    private companion object {
        const val BACKUP_VERSION = 1
        const val MIN_PASSPHRASE_LENGTH = 8
        const val SALT_SIZE = 16
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
        const val PBKDF2_ITERATIONS = 310_000
        const val MAX_FILE_BYTES = 25 * 1024 * 1024
        const val CIPHER = "AES/GCM/NoPadding"
        val MAGIC = "MYEXP001".toByteArray(Charsets.US_ASCII)
        val CSV_HEADER = listOf(
            "id",
            "timestamp",
            "amount_inr",
            "type",
            "status",
            "merchant",
            "category_id",
            "account_id",
            "source",
            "note",
            "excluded",
            "reference",
        )
    }
}

@Serializable
private data class BackupEnvelope(
    val version: Int,
    val exportedAt: Long,
    val transactions: List<TransactionBackup>,
    val accounts: List<AccountBackup>,
    val categories: List<CategoryBackup>,
    val budgets: List<BudgetBackup>,
)

@Serializable
private data class TransactionBackup(
    val id: String,
    val amountMinor: Long,
    val type: String,
    val status: String,
    val merchant: String,
    val accountId: String?,
    val accountHint: String?,
    val categoryId: String?,
    val occurredAt: Long,
    val source: String,
    val sourceConfidence: Float,
    val reference: String?,
    val note: String,
    val isExcluded: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
private data class AccountBackup(
    val id: String,
    val name: String,
    val type: String,
    val lastFour: String?,
    val balanceMinor: Long?,
    val isArchived: Boolean,
    val createdAt: Long,
)

@Serializable
private data class CategoryBackup(
    val id: String,
    val name: String,
    val icon: String,
    val color: Long,
    val isIncome: Boolean,
    val isSystem: Boolean,
)

@Serializable
private data class BudgetBackup(
    val id: String,
    val categoryId: String,
    val monthKey: String,
    val limitMinor: Long,
)

private fun TransactionEntity.toBackup() = TransactionBackup(
    id, amountMinor, type.name, status.name, merchant, accountId, accountHint, categoryId,
    occurredAt, source.name, sourceConfidence, reference, note, isExcluded, createdAt, updatedAt,
)

private fun TransactionBackup.toEntity() = TransactionEntity(
    id, amountMinor, enumOrDefault(type, TransactionType.EXPENSE),
    enumOrDefault(status, TransactionStatus.POSTED), merchant, accountId, accountHint, categoryId,
    occurredAt, enumOrDefault(source, SourceType.BACKUP), sourceConfidence, reference, note,
    isExcluded, createdAt, updatedAt,
)

private fun AccountEntity.toBackup() = AccountBackup(
    id, name, type.name, lastFour, balanceMinor, isArchived, createdAt,
)

private fun AccountBackup.toEntity() = AccountEntity(
    id, name, enumOrDefault(type, AccountType.OTHER), lastFour, balanceMinor, isArchived, createdAt,
)

private fun CategoryEntity.toBackup() = CategoryBackup(
    id, name, icon, color, isIncome, isSystem,
)

private fun CategoryBackup.toEntity() = CategoryEntity(id, name, icon, color, isIncome, isSystem)

private fun BudgetEntity.toBackup() = BudgetBackup(id, categoryId, monthKey, limitMinor)

private fun BudgetBackup.toEntity() = BudgetEntity(id, categoryId, monthKey, limitMinor)

private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default

private fun java.math.BigDecimal.longValueExactOrNull(): Long? = try {
    longValueExact()
} catch (_: ArithmeticException) {
    null
}
