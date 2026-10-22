package com.ajamuar.ledger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import kotlinx.coroutines.*

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    val repo = (context.applicationContext as LedgerApplication).repository
                    repo.ingest(messages.first().originatingAddress.orEmpty(), messages.joinToString("") { it.messageBody.orEmpty() }, messages.first().timestampMillis)
                }
            } catch (_: Exception) {
                context.getSharedPreferences("ledger", Context.MODE_PRIVATE).edit().putBoolean("scanNeeded", true).apply()
            } finally { pending.finish() }
        }
    }
}

suspend fun scanSms(context: Context, repo: Repository, since: Long, progress: (Int, Int) -> Unit): Pair<Int, Int> = withContext(Dispatchers.IO) {
    check(context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) { "Grant SMS access or use manual entries" }
    var scanned = 0; var added = 0
    context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "body", "date", "date_sent"), "date >= ?", arrayOf(since.toString()), "date ASC")?.use { cursor ->
        while (cursor.moveToNext()) {
            currentCoroutineContext().ensureActive()
            val sent = cursor.getLong(3).takeIf { it > 0 } ?: cursor.getLong(2)
            if (repo.ingest(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty(), sent)) added++
            scanned++
            if (scanned % 50 == 0) progress(scanned, added)
        }
    } ?: error("The SMS inbox is unavailable")
    context.getSharedPreferences("ledger", Context.MODE_PRIVATE).edit().putBoolean("scanNeeded", false).apply()
    progress(scanned, added)
    scanned to added
}
