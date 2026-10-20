package com.myexpenses.ingestion.sms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.myexpenses.data.AppPreferences
import com.myexpenses.data.ExpenseRepository
import com.myexpenses.data.model.IngestionOutcome
import com.myexpenses.data.model.SourceType
import com.myexpenses.ingestion.Hashing
import com.myexpenses.ingestion.ParseInput
import com.myexpenses.ingestion.ParseResult
import com.myexpenses.ingestion.TransactionMessageFilter
import com.myexpenses.ingestion.TransactionParser
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class SmsScanSummary(
    val scanned: Int,
    val imported: Int,
    val duplicates: Int,
    val ignored: Int,
    val errors: Int,
)

@Singleton
class SmsScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: TransactionParser,
    private val repository: ExpenseRepository,
    private val preferences: AppPreferences,
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasAutomaticPermissions(): Boolean =
        hasPermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun scanInbox(maxMessages: Int = 5_000): SmsScanSummary {
        if (!hasPermission()) throw SecurityException("SMS permission has not been granted")

        var scanned = 0
        var imported = 0
        var duplicates = 0
        var ignored = 0
        var errors = 0
        var examined = 0
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val senderIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext() && examined < maxMessages) {
                examined++
                val sender = cursor.getString(senderIndex).orEmpty()
                if (!looksTransactionalSender(sender)) continue
                scanned++
                val externalId = "sms:${cursor.getLong(idIndex)}"
                val result = runCatching {
                    process(
                        externalId = externalId,
                        sender = sender,
                        body = cursor.getString(bodyIndex).orEmpty(),
                        timestamp = cursor.getLong(dateIndex),
                    )
                }.getOrElse {
                    errors++
                    null
                }
                when (result) {
                    IngestionOutcome.IMPORTED -> imported++
                    IngestionOutcome.DUPLICATE -> duplicates++
                    IngestionOutcome.REJECTED -> ignored++
                    IngestionOutcome.ERROR -> errors++
                    null -> Unit
                }
            }
        }
        val summary = SmsScanSummary(scanned, imported, duplicates, ignored, errors)
        preferences.recordSmsScan(
            status = "SMS scan: $imported new, $duplicates matched, $ignored ignored",
        )
        return summary
    }

    suspend fun process(
        externalId: String,
        sender: String,
        body: String,
        timestamp: Long,
    ): IngestionOutcome {
        val rules = repository.enabledParserRules()
        if (!TransactionMessageFilter.looksTransactional(body, rules)) {
            repository.recordIgnored(
                source = SourceType.SMS,
                externalId = externalId,
                fingerprint = Hashing.sha256(
                    body.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim(),
                ),
                reason = "Sender matched, but transaction keywords were not present",
            )
            return IngestionOutcome.REJECTED
        }
        val result = parser.parse(
            ParseInput(
                externalId = externalId,
                source = SourceType.SMS,
                sender = sender,
                body = body,
                receivedAt = timestamp,
            ),
            rules = rules,
        )
        return when (result) {
            is ParseResult.Parsed -> repository.import(result.candidate).outcome
            is ParseResult.Ignored -> {
                repository.recordIgnored(
                    source = SourceType.SMS,
                    externalId = externalId,
                    fingerprint = result.contentFingerprint,
                    reason = result.reason,
                )
                IngestionOutcome.REJECTED
            }
        }
    }

    companion object {
        fun looksTransactionalSender(sender: String): Boolean {
            val normalized = sender.uppercase(Locale.ROOT).trim()
            if (normalized.isBlank() || normalized.all(Char::isDigit)) return false
            val dltHeader = normalized.matches(Regex("^[A-Z]{2}-[A-Z0-9]{4,11}$"))
            val explicitFinancialSender = normalized.matches(Regex("^[A-Z0-9]{5,16}$")) &&
                listOf("BANK", "UPI", "PAY", "CARD", "ALERT").any(normalized::contains)
            return dltHeader || explicitFinancialSender
        }
    }
}

@AndroidEntryPoint
class TransactionSmsReceiver : BroadcastReceiver() {
    @Inject lateinit var scanner: SmsScanner

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return
        val sender = messages.first().displayOriginatingAddress.orEmpty()
        if (!SmsScanner.looksTransactionalSender(sender)) return

        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val timestamp = messages.minOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
        val externalId = "live:$sender:$timestamp:${Hashing.sha256(body).take(16)}"
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                scanner.process(externalId, sender, body, timestamp)
            } finally {
                pending.finish()
            }
        }
    }
}
