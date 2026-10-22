package com.ajamuar.ledger

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

class LedgerApplication : Application() {
    val repository by lazy { Repository(this) }
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("ledger-sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("ledger-reminders", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(12, TimeUnit.HOURS).build())
    }
}

class Repository(private val context: Context, databaseName: String = "ledger.db") {
    val vault = Vault(context)
    private val preferences = context.getSharedPreferences("ledger", Context.MODE_PRIVATE)
    val state = MutableStateFlow(preferences.getString("syncState", "Not paired")!!)
    val db: LedgerDatabase
    val dao: LedgerDao
    init {
        System.loadLibrary("sqlcipher")
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, databaseName)
            .openHelperFactory(SupportOpenHelperFactory(vault.databaseKey())).addMigrations(MIGRATION_1_2).build()
        dao = db.ledger()
    }
    suspend fun initialise() {
        db.withTransaction {
            dao.insertAccount(Account("cash", "Cash", "Cash", "cash"))
            if (dao.metadata("directionProtocol") == null) {
                dao.snapshot().forEach { old ->
                    val updated = old.copy(revision = old.revision + 1)
                    dao.put(updated); dao.enqueue(Change(updated.id, updated.revision, payload(updated)))
                }
                dao.putMetadata(Metadata("directionProtocol", "1"))
            }
        }
    }
    suspend fun identity(): String = db.withTransaction {
        dao.metadata("ledgerId") ?: UUID.randomUUID().toString().also { dao.putMetadata(Metadata("ledgerId", it)) }
    }
    fun syncState(text: String) {
        preferences.edit().putString("syncState", text).apply()
        state.value = text
    }
    private suspend fun payload(entry: Entry): String {
        val account = requireNotNull(dao.account(entry.accountId)) { "Choose an existing account" }
        return JSONObject().apply {
            put("id", entry.id); put("revision", entry.revision); put("amount_minor", entry.amount)
            put("kind", entry.kind); put("status", entry.status); put("account_id", account.repoId)
            put("merchant", entry.merchant); put("category", entry.category); put("tags", entry.tags); put("note", entry.note)
            put("occurred_at", entry.time); put("date", Instant.ofEpochMilli(entry.time).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString())
            put("source", entry.source); put("reviewed", entry.reviewed); put("excluded", entry.excluded)
            put("deleted", entry.deleted); put("linked_id", entry.linkedId ?: JSONObject.NULL)
            put("personal_minor", entry.personalMinor ?: JSONObject.NULL)
            put("direction", entry.direction)
        }.toString()
    }
    suspend fun save(value: Entry) {
        require(value.amount in 1..Money.MAX_MINOR) { "Invalid amount" }
        require(value.merchant.isNotBlank() && value.merchant.length <= 120) { "Enter a description of up to 120 characters" }
        require(value.category.isNotBlank() && value.category.length <= 80 && value.tags.length <= 500 && value.note.length <= 2000)
        Kind.valueOf(value.kind); Status.valueOf(value.status)
        require(value.time in 0..System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)) { "Transaction date is in the future" }
        require(value.personalMinor == null || value.personalMinor in 0..value.amount) { "Personal share exceeds the transaction amount" }
        require(value.linkedId != value.id) { "An entry cannot link to itself" }
        require(value.direction in listOf("in", "out"))
        require(value.kind != Kind.EXPENSE.name || value.direction == "out") { "An expense must be outgoing" }
        require(value.kind !in listOf(Kind.INCOME.name, Kind.REFUND.name) || value.direction == "in") { "Income and refunds must be incoming" }
        db.withTransaction {
            val prior = dao.entry(value.id)
            require(prior == null || prior.revision == value.revision) { "This entry changed. Reopen it before saving." }
            val split = dao.entrySplit(value.id)
            require(split == null || (prior != null && value.amount == prior.amount && value.kind == Kind.EXPENSE.name && !value.deleted)) {
                "Remove the linked group expense before changing its amount/type or deleting it"
            }
            val updated = value.copy(revision = (prior?.revision ?: 0) + 1, updatedAt = System.currentTimeMillis())
            dao.put(updated)
            dao.enqueue(Change(updated.id, updated.revision, payload(updated)))
        }
        scheduleSync()
    }
    suspend fun account(value: Account) {
        require(value.name.isNotBlank() && value.name.length <= 80) { "Enter an account name" }
        require(value.repoId.isEmpty() || Regex("[a-zA-Z0-9_-]{1,80}").matches(value.repoId)) { "Repo ID: use letters, numbers, hyphens or underscores" }
        require(value.openingMinor == null || (value.openingMinor in -Money.MAX_MINOR..Money.MAX_MINOR && value.openingAt != null))
        require(value.observedMinor == null || (value.observedMinor in -Money.MAX_MINOR..Money.MAX_MINOR && value.observedAt != null))
        require(value.openingAt == null || value.openingAt in 0..System.currentTimeMillis()) { "Opening date must be between 1970 and today" }
        require(value.observedAt == null || value.observedAt in 0..System.currentTimeMillis()) { "Observation date must be between 1970 and today" }
        db.withTransaction {
            dao.putAccount(value)
            dao.snapshot().filter { it.accountId == value.id }.forEach { entry ->
                val updated = entry.copy(revision = entry.revision + 1)
                dao.put(updated)
                dao.enqueue(Change(updated.id, updated.revision, payload(updated)))
            }
        }
        scheduleSync()
    }
    suspend fun ingest(sender: String, body: String, timestamp: Long): Boolean {
        val candidate = parseSms(sender, body) ?: return false
        val fallback = digest("$sender|$timestamp|$body")
        val inserted = db.withTransaction {
            val account = discoverAccount(sender, candidate)
            if (account == null) {
                holdSms(sender, candidate, timestamp, fallback)
                return@withTransaction false
            }
            if (account.archived || dao.metadata("sms-track:${account.id}") != "true") return@withTransaction false
            insertSms(sender, candidate, timestamp, fallback, account.id)
        }
        if (inserted) scheduleSync()
        return inserted
    }
    fun scheduleSync(force: Boolean = false) {
        if (!force && preferences.getBoolean("syncPaused", false)) return
        WorkManager.getInstance(context).enqueueUniqueWork("ledger-sync", if (force) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf("force" to force)).setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    suspend fun importEntries(entries: List<Entry>): Int {
        require(entries.size <= 50_000)
        var inserted = 0
        db.withTransaction {
            entries.forEach { entry ->
                if (dao.source(requireNotNull(entry.sourceKey)) == null && dao.entry(entry.id) == null) {
                    require(dao.account(entry.accountId) != null) { "Select an account before importing" }
                    dao.put(entry)
                    dao.enqueue(Change(entry.id, entry.revision, payload(entry)))
                    inserted++
                }
            }
        }
        scheduleSync()
        return inserted
    }
    suspend fun backup(password: CharArray): ByteArray {
        val snapshot = db.withTransaction {
            JSONObject().put("schema", 1).put("ledger_id", identity())
                .put("accounts", JSONArray().apply { dao.accountSnapshot().forEach { a -> put(JSONObject()
                    .put("id", a.id).put("name", a.name).put("type", a.type).put("repo_id", a.repoId).put("archived", a.archived)
                    .put("opening_minor", a.openingMinor ?: JSONObject.NULL).put("opening_at", a.openingAt ?: JSONObject.NULL)
                    .put("observed_minor", a.observedMinor ?: JSONObject.NULL).put("observed_at", a.observedAt ?: JSONObject.NULL)) } })
                .put("entries", JSONArray().apply { dao.snapshot().forEach { e -> put(JSONObject(payload(e))
                    .put("account_internal", e.accountId).put("source_key", e.sourceKey ?: JSONObject.NULL)
                    .put("updated_at", e.updatedAt)) } })
                .put("budgets", JSONArray().apply { dao.budgetSnapshot().forEach { b -> put(JSONObject().put("category", b.category).put("limit", b.limitMinor)) } })
                .put("bills", JSONArray().apply { dao.billSnapshot().forEach { b -> put(JSONObject().put("id", b.id).put("name", b.name).put("amount", b.amount ?: JSONObject.NULL).put("due", b.due).put("repeat", b.repeat).put("paid", b.paid).put("payment_id", b.paymentId ?: JSONObject.NULL)) } })
                .put("groups", JSONArray().apply { dao.groupSnapshot().forEach { g -> put(JSONObject().put("id", g.id).put("name", g.name).put("members", g.members)) } })
                .put("splits", JSONArray().apply { dao.splitSnapshot().forEach { s -> put(JSONObject().put("id", s.id).put("group_id", s.groupId).put("description", s.description).put("payer", s.payer).put("shares", s.shares).put("amount", s.amount).put("time", s.time).put("settlement_to", s.settlementTo ?: JSONObject.NULL).put("entry_id", s.entryId ?: JSONObject.NULL).put("generated", s.generatedEntry)) } })
                .put("rules", JSONArray().apply { dao.ruleSnapshot().forEach { r -> put(JSONObject().put("merchant", r.merchant).put("category", r.category)) } })
                .put("aliases", JSONObject().apply { dao.aliases().forEach { put(it.key, it.value) } })
                .toString().toByteArray()
        }
        return try { Backup.encrypt(snapshot, password) } finally { password.fill('\u0000') }
    }
    suspend fun restore(data: ByteArray, password: CharArray) {
        val root = try {
            JSONObject(Backup.decrypt(data, password).toString(Charsets.UTF_8))
        } catch (e: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("Cannot unlock backup. Check the password and use an intact backup file.", e)
        } finally { password.fill('\u0000') }
        require(root.getInt("schema") == 1) { "Unsupported backup version" }
        val id = root.getString("ledger_id").also { UUID.fromString(it) }
        val rawAccounts = root.getJSONArray("accounts")
        val rawEntries = root.getJSONArray("entries")
        val rawBudgets = root.getJSONArray("budgets")
        require(rawAccounts.length() in 1..1000 && rawEntries.length() <= 50_000 && rawBudgets.length() <= 1000) { "Backup exceeds supported limits" }
        val accounts = (0 until rawAccounts.length()).map { index -> rawAccounts.getJSONObject(index).let {
            Account(it.getString("id"), it.getString("name"), it.getString("type"), it.getString("repo_id"), it.getBoolean("archived"),
                if (it.isNull("opening_minor")) null else it.getLong("opening_minor"), if (it.isNull("opening_at")) null else it.getLong("opening_at"),
                if (it.isNull("observed_minor")) null else it.getLong("observed_minor"), if (it.isNull("observed_at")) null else it.getLong("observed_at"))
        } }
        require(accounts.map { it.id }.toSet().size == accounts.size && accounts.all { it.name.isNotBlank() && it.name.length <= 80 && it.id.length in 1..100 && (it.repoId.isEmpty() || Regex("[A-Za-z0-9_-]{1,80}").matches(it.repoId)) }) { "Invalid backup accounts" }
        require(accounts.all { a ->
            a.type in listOf("Bank", "Card", "Cash", "Wallet", "Meal card", "Other") &&
                (a.openingMinor == null || (a.openingMinor in -Money.MAX_MINOR..Money.MAX_MINOR && a.openingAt != null && a.openingAt in 0..System.currentTimeMillis())) &&
                (a.observedMinor == null || (a.observedMinor in -Money.MAX_MINOR..Money.MAX_MINOR && a.observedAt != null && a.observedAt in 0..System.currentTimeMillis()))
        }) { "Invalid backup balance observations" }
        val entries = (0 until rawEntries.length()).map { index -> rawEntries.getJSONObject(index).let { o ->
            val key = if (o.isNull("source_key")) null else o.getString("source_key").also { require(Regex("[a-f0-9]{64}").matches(it)) }
            Entry(id = o.getString("id").also { UUID.fromString(it) }, revision = o.getLong("revision"), amount = o.getLong("amount_minor"),
                kind = o.getString("kind").also { Kind.valueOf(it) }, status = o.getString("status").also { Status.valueOf(it) },
                accountId = o.getString("account_internal"), merchant = o.getString("merchant"), category = o.getString("category"),
                tags = o.getString("tags"), note = o.getString("note"), time = o.getLong("occurred_at"), source = o.getString("source"),
                sourceKey = key, reviewed = o.getBoolean("reviewed"), excluded = o.getBoolean("excluded"), deleted = o.getBoolean("deleted"),
                linkedId = if (o.isNull("linked_id")) null else o.getString("linked_id").also { UUID.fromString(it) }, updatedAt = o.getLong("updated_at"),
                personalMinor = if (o.isNull("personal_minor")) null else o.getLong("personal_minor"),
                direction = o.optString("direction", if (o.getString("kind") in listOf("INCOME", "REFUND", "REIMBURSEMENT")) "in" else "out"))
        } }
        require(entries.map { it.id }.toSet().size == entries.size && entries.mapNotNull { it.sourceKey }.distinct().size == entries.count { it.sourceKey != null }) { "Duplicate backup entries" }
        require(entries.all { it.accountId in accounts.map { a -> a.id } && it.amount in 1..Money.MAX_MINOR && it.revision in 1..(1L shl 53) &&
            it.merchant.isNotBlank() && it.merchant.length <= 120 && it.category.isNotBlank() && it.category.length <= 80 &&
            it.tags.length <= 500 && it.note.length <= 2000 && it.time in 0..System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1) && it.source in listOf("manual", "sms", "csv") && (it.personalMinor == null || it.personalMinor in 0..it.amount) && it.direction in listOf("in", "out") }) { "Invalid backup transactions" }
        val budgets = (0 until rawBudgets.length()).map { index -> rawBudgets.getJSONObject(index).let { Budget(it.getString("category"), it.getLong("limit")) } }
        require(budgets.all { it.category.isNotBlank() && it.category.length <= 80 && it.limitMinor in 1..Money.MAX_MINOR }) { "Invalid backup budgets" }
        fun objects(key: String): List<JSONObject> = root.optJSONArray(key)?.let { a -> require(a.length() <= 50_000); (0 until a.length()).map(a::getJSONObject) } ?: emptyList()
        val bills = objects("bills").map { o -> Bill(o.getString("id"), o.getString("name"), if (o.isNull("amount")) null else o.getLong("amount"), o.getString("due"), o.getString("repeat"), o.getBoolean("paid"), if (o.isNull("payment_id")) null else o.getString("payment_id")) }
        bills.forEach { validateBill(it) }
        val groups = objects("groups").map { o -> PrivateGroup(o.getString("id"), o.getString("name"), o.getString("members")) }
        groups.forEach { validateGroup(it) }
        require(groups.map { it.id }.distinct().size == groups.size)
        val splits = objects("splits").map { o -> Split(o.getString("id"), o.getString("group_id"), o.getString("description"), o.getString("payer"), o.getString("shares"), o.getLong("amount"), o.getLong("time"), if (o.isNull("settlement_to")) null else o.getString("settlement_to"), if (o.isNull("entry_id")) null else o.getString("entry_id"), o.getBoolean("generated")) }
        splits.forEach { validateSplit(it, requireNotNull(groups.find { g -> g.id == it.groupId })) }
        require(splits.map { it.id }.distinct().size == splits.size && splits.mapNotNull { it.entryId }.distinct().size == splits.count { it.entryId != null })
        require(splits.all { it.entryId == null || entries.any { e -> e.id == it.entryId && !e.deleted && e.kind == Kind.EXPENSE.name } })
        val rules = objects("rules").map { MerchantRule(it.getString("merchant"), it.getString("category")) }
        require(rules.all { it.merchant.isNotBlank() && it.merchant.length <= 120 && it.category.isNotBlank() && it.category.length <= 80 })
        val aliases = root.optJSONObject("aliases") ?: JSONObject()
        val aliasKeys = aliases.keys().asSequence().toList()
        require(aliasKeys.size <= 1000 && aliasKeys.all { it.startsWith("account-alias:") && it.length <= 120 && aliases.getString(it) in accounts.map { a -> a.id } })
        aliasKeys.forEach { start ->
            val seen = mutableSetOf<String>(); var key = start
            while (aliases.has(key)) { require(seen.add(key)) { "Account alias cycle" }; key = "account-alias:${aliases.getString(key)}" }
        }
        db.withTransaction {
            require(dao.snapshot().isEmpty() && dao.billSnapshot().isEmpty() && dao.groupSnapshot().isEmpty() && dao.budgetSnapshot().isEmpty() && dao.ruleSnapshot().isEmpty()) { "Restore only into an empty ledger and plans. Existing data has not been changed." }
            dao.clearAccounts()
            dao.clearSmsSettings()
            dao.clearAccountAliases()
            accounts.forEach { dao.putAccount(it) }
            dao.putMetadata(Metadata("ledgerId", id))
            entries.forEach { dao.put(it); dao.enqueue(Change(it.id, it.revision, payload(it))) }
            budgets.forEach { dao.putBudget(it) }
            bills.forEach { dao.putBill(it) }; groups.forEach { dao.putGroup(it) }; splits.forEach { dao.putSplit(it) }; rules.forEach { dao.putRule(it) }
            aliasKeys.forEach { dao.putMetadata(Metadata(it, aliases.getString(it))) }
        }
        scheduleSync()
    }
    suspend fun exportCsv(): String = withContext(Dispatchers.IO) {
        val accounts = dao.accountSnapshot().associateBy { it.id }
        fun safe(value: String): String = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r') || value.startsWith('\t') || value.startsWith('\r') || value.startsWith("'")) "'$value" else value
        Csv.write(listOf(listOf("id", "date", "account_id", "kind", "status", "amount_inr", "description", "category", "tags", "note", "excluded", "direction", "personal_amount_inr", "text_encoding")) +
            dao.snapshot().filterNot { it.deleted }.map { e -> listOf(e.id,
                Instant.ofEpochMilli(e.time).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString(),
                safe(accounts[e.accountId]?.name.orEmpty()), e.kind, e.status, Money.decimal(e.amount), safe(e.merchant), safe(e.category), safe(e.tags), safe(e.note), e.excluded.toString(), e.direction, e.personalMinor?.let(Money::decimal).orEmpty(), "spreadsheet-apostrophe") })
    }
}
