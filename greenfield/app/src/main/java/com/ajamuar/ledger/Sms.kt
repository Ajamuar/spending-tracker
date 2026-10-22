package com.ajamuar.ledger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.room.withTransaction
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

data class SmsPreview(val account: Account, val count: Int, val samples: List<Pair<Long, SmsCandidate>>)
data class SmsDiscovery(val days: Int, val scanned: Int, val accounts: List<SmsPreview>)

private suspend fun readInbox(context: Context, since: Long, consume: suspend (String, String, Long) -> Unit): Int {
    check(context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) { "Grant SMS access or use manual entries" }
    var scanned = 0
    context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "body", "date", "date_sent"), "date >= ?", arrayOf(since.toString()), "date ASC")?.use { cursor ->
        while (cursor.moveToNext()) {
            currentCoroutineContext().ensureActive()
            val sent = cursor.getLong(3).takeIf { it > 0 } ?: cursor.getLong(2)
            consume(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty(), sent)
            scanned++
        }
    } ?: error("The SMS inbox is unavailable")
    return scanned
}

suspend fun discoverSms(context: Context, repo: Repository, days: Int, progress: (Int, Int) -> Unit): SmsDiscovery = withContext(Dispatchers.IO) {
    val accounts = mutableMapOf<String, SmsPreview>()
    var checked = 0
    val scanned = readInbox(context, System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(days.toLong())) { sender, body, time ->
        checked++
        if (checked % 50 == 0) progress(checked, accounts.size)
        val candidate = SmsParser.parse(sender, body)
        if (candidate != null) repo.db.withTransaction {
            val account = repo.discoverAccount(sender, candidate)
            if (account == null) repo.holdSms(sender, candidate, time, digest("$sender|$time|$body"))
            else {
                val previous = accounts[account.id]
                accounts[account.id] = SmsPreview(account, (previous?.count ?: 0) + 1,
                    ((previous?.samples ?: emptyList()) + (time to candidate)).takeLast(3))
            }
        }
    }
    SmsDiscovery(days, scanned, accounts.values.sortedBy { it.account.name })
}

suspend fun scanSms(context: Context, repo: Repository, since: Long, progress: (Int, Int) -> Unit): Pair<Int, Int> = withContext(Dispatchers.IO) {
    var checked = 0; var added = 0
    val scanned = readInbox(context, since) { sender, body, time ->
        if (repo.ingest(sender, body, time)) added++
        checked++
        if (checked % 50 == 0) progress(checked, added)
    }
    context.getSharedPreferences("ledger", Context.MODE_PRIVATE).edit().putBoolean("scanNeeded", false).apply()
    progress(scanned, added)
    scanned to added
}
