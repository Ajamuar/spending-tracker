package com.ajamuar.ledger

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

object Money {
    const val MAX_MINOR = 9_999_999_999_999L
    fun parse(value: String): Long {
        val clean = value.trim().removePrefix("₹").trim()
        require(Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,2}(?:,[0-9]{2})*,[0-9]{3})(?:\\.[0-9]{1,2})?").matches(clean)) {
            "Enter a positive rupee amount with at most two decimal places"
        }
        return clean.replace(",", "").toBigDecimal().movePointRight(2).longValueExact().also {
            require(it in 1..MAX_MINOR) { "Amount is outside the supported range" }
        }
    }
    fun text(minor: Long): String {
        val decimal = BigDecimal.valueOf(minor, 2).abs().setScale(2).toPlainString().split('.')
        val whole = decimal[0]
        val grouped = if (whole.length <= 3) whole else whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
        return "${if (minor < 0) "−" else ""}₹$grouped.${decimal[1]}"
    }
    fun decimal(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString()
    fun signed(value: String): Long {
        val text = value.trim()
        val unsigned = text.removePrefix("-")
        val amount = if (unsigned in listOf("0", "0.0", "0.00")) 0 else parse(unsigned)
        return if (text.startsWith("-")) -amount else amount
    }
}

enum class Kind(val label: String) {
    EXPENSE("Expense"), INCOME("Income"), TRANSFER("Transfer"), CARD_PAYMENT("Card repayment"),
    REFUND("Refund"), INVESTMENT("Investment"), REIMBURSEMENT("Reimbursement")
}
enum class Status { POSTED, PENDING, FAILED, REVERSED }

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String = "Bank",
    val repoId: String = "",
    val archived: Boolean = false,
    val openingMinor: Long? = null,
    val openingAt: Long? = null,
    val observedMinor: Long? = null,
    val observedAt: Long? = null,
)

@Entity(tableName = "entries", indices = [Index(value = ["sourceKey"], unique = true)])
data class Entry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val revision: Long = 1,
    val amount: Long,
    val kind: String = Kind.EXPENSE.name,
    val status: String = Status.POSTED.name,
    val accountId: String = "cash",
    val merchant: String,
    val category: String = "Uncategorised",
    val tags: String = "",
    val note: String = "",
    val time: Long = System.currentTimeMillis(),
    val source: String = "manual",
    val sourceKey: String? = null,
    val reviewed: Boolean = true,
    val excluded: Boolean = false,
    val deleted: Boolean = false,
    val linkedId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val personalMinor: Long? = null,
    @ColumnInfo(defaultValue = "'out'") val direction: String = if (kind in listOf("INCOME", "REFUND", "REIMBURSEMENT")) "in" else "out",
)

@Entity(tableName = "outbox", primaryKeys = ["entryId", "revision"])
data class Change(val entryId: String, val revision: Long, val payload: String)

@Entity(tableName = "budgets")
data class Budget(@PrimaryKey val category: String, val limitMinor: Long)

@Entity(tableName = "metadata")
data class Metadata(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "bills")
data class Bill(@PrimaryKey val id: String = UUID.randomUUID().toString(), val name: String,
    val amount: Long? = null, val due: String, val repeat: String = "None", val paid: Boolean = false,
    val paymentId: String? = null)

@Entity(tableName = "groups")
data class PrivateGroup(@PrimaryKey val id: String = UUID.randomUUID().toString(), val name: String, val members: String)

@Entity(tableName = "splits")
data class Split(@PrimaryKey val id: String = UUID.randomUUID().toString(), val groupId: String,
    val description: String, val payer: String, val shares: String, val amount: Long,
    val time: Long = System.currentTimeMillis(), val settlementTo: String? = null, val entryId: String? = null,
    val generatedEntry: Boolean = false)

@Entity(tableName = "merchant_rules")
data class MerchantRule(@PrimaryKey val merchant: String, val category: String)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM entries ORDER BY time DESC, id") fun entries(): Flow<List<Entry>>
    @Query("SELECT * FROM entries ORDER BY time DESC, id") suspend fun snapshot(): List<Entry>
    @Query("SELECT * FROM entries WHERE id = :id") suspend fun entry(id: String): Entry?
    @Query("SELECT * FROM entries WHERE sourceKey = :key LIMIT 1") suspend fun source(key: String): Entry?
    @Upsert suspend fun put(entry: Entry)
    @Query("SELECT * FROM accounts ORDER BY name") fun accounts(): Flow<List<Account>>
    @Query("SELECT * FROM accounts ORDER BY name") suspend fun accountSnapshot(): List<Account>
    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun account(id: String): Account?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAccount(account: Account)
    @Upsert suspend fun putAccount(account: Account)
    @Upsert suspend fun enqueue(change: Change)
    @Query("SELECT * FROM outbox AS ob WHERE revision = (SELECT max(revision) FROM outbox WHERE entryId = ob.entryId) ORDER BY entryId LIMIT 100") suspend fun pending(): List<Change>
    @Query("SELECT count(*) FROM outbox") fun pendingCount(): Flow<Int>
    @Query("DELETE FROM outbox WHERE entryId = :id AND revision <= :revision") suspend fun acknowledge(id: String, revision: Long)
    @Query("SELECT * FROM budgets ORDER BY category") fun budgets(): Flow<List<Budget>>
    @Upsert suspend fun putBudget(budget: Budget)
    @Delete suspend fun deleteBudget(budget: Budget)
    @Query("SELECT * FROM budgets") suspend fun budgetSnapshot(): List<Budget>
    @Query("SELECT value FROM metadata WHERE `key` = :key") suspend fun metadata(key: String): String?
    @Upsert suspend fun putMetadata(metadata: Metadata)
    @Query("SELECT * FROM metadata WHERE `key` LIKE 'sms-%'") fun smsSettings(): Flow<List<Metadata>>
    @Query("SELECT * FROM metadata WHERE `key` LIKE 'account-alias:%'") suspend fun aliases(): List<Metadata>
    @Query("DELETE FROM accounts") suspend fun clearAccounts()
    @Query("DELETE FROM metadata WHERE `key` LIKE 'account-alias:%'") suspend fun clearAccountAliases()
    @Query("DELETE FROM metadata WHERE `key` LIKE 'sms-%'") suspend fun clearSmsSettings()
    @Query("SELECT * FROM bills ORDER BY due") fun bills(): Flow<List<Bill>>
    @Query("SELECT * FROM bills ORDER BY due") suspend fun billSnapshot(): List<Bill>
    @Upsert suspend fun putBill(bill: Bill)
    @Delete suspend fun deleteBill(bill: Bill)
    @Query("SELECT * FROM groups ORDER BY name") fun groups(): Flow<List<PrivateGroup>>
    @Query("SELECT * FROM groups") suspend fun groupSnapshot(): List<PrivateGroup>
    @Query("SELECT * FROM groups WHERE id = :id") suspend fun group(id: String): PrivateGroup?
    @Upsert suspend fun putGroup(group: PrivateGroup)
    @Delete suspend fun deleteGroup(group: PrivateGroup)
    @Query("SELECT * FROM splits ORDER BY time DESC") fun splits(): Flow<List<Split>>
    @Query("SELECT * FROM splits") suspend fun splitSnapshot(): List<Split>
    @Query("SELECT * FROM splits WHERE id = :id") suspend fun split(id: String): Split?
    @Query("SELECT * FROM splits WHERE entryId = :id") suspend fun entrySplit(id: String): Split?
    @Upsert suspend fun putSplit(split: Split)
    @Delete suspend fun deleteSplit(split: Split)
    @Query("SELECT * FROM merchant_rules ORDER BY merchant") fun rules(): Flow<List<MerchantRule>>
    @Query("SELECT * FROM merchant_rules") suspend fun ruleSnapshot(): List<MerchantRule>
    @Query("SELECT * FROM merchant_rules WHERE merchant = :merchant") suspend fun rule(merchant: String): MerchantRule?
    @Upsert suspend fun putRule(rule: MerchantRule)
    @Delete suspend fun deleteRule(rule: MerchantRule)
}

@Database(entities = [Account::class, Entry::class, Change::class, Budget::class, Metadata::class, Bill::class, PrivateGroup::class, Split::class, MerchantRule::class], version = 2, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() { abstract fun ledger(): LedgerDao }

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN direction TEXT NOT NULL DEFAULT 'out'")
        db.execSQL("UPDATE entries SET direction = 'in' WHERE kind IN ('INCOME','REFUND','REIMBURSEMENT')")
        listOf("openingMinor", "openingAt", "observedMinor", "observedAt").forEach { db.execSQL("ALTER TABLE accounts ADD COLUMN $it INTEGER") }
    }
}

fun Account.calculatedBalance(entries: List<Entry>, until: Long = Long.MAX_VALUE): Long? {
    val opening = openingMinor ?: return null
    val at = openingAt ?: return null
    return entries.filter { !it.deleted && it.status == Status.POSTED.name && it.accountId == id && it.time >= at && it.time <= until }
        .fold(opening) { balance, entry -> Math.addExact(balance, entry.amount * (if (entry.direction == "in") 1 else -1) * (if (type == "Card") -1 else 1)) }
}

fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    .joinToString("") { "%02x".format(it) }

fun Entry.personalSpend(): Long = when {
    deleted || excluded || status != Status.POSTED.name -> 0
    kind == Kind.EXPENSE.name -> personalMinor ?: amount
    kind == Kind.REFUND.name -> -(personalMinor ?: amount)
    else -> 0
}

object SplitMath {
    fun equal(amount: Long, count: Int): List<Long> {
        require(amount in 1..Money.MAX_MINOR && count in 2..50)
        return List(count) { amount / count + if (it < amount % count) 1 else 0 }
    }
    fun balances(members: List<String>, splits: List<Pair<Split, Map<String, Long>>>): Map<String, Long> {
        val result = members.associateWith { 0L }.toMutableMap()
        splits.forEach { (split, shares) ->
            if (split.settlementTo != null) {
                result[split.payer] = Math.addExact(result.getValue(split.payer), split.amount)
                result[split.settlementTo] = Math.subtractExact(result.getValue(split.settlementTo), split.amount)
            } else {
                result[split.payer] = Math.addExact(result.getValue(split.payer), split.amount)
                shares.forEach { (person, amount) -> result[person] = Math.subtractExact(result.getValue(person), amount) }
            }
        }
        return result
    }
}

data class SmsCandidate(val amount: Long, val kind: Kind, val status: Status, val merchant: String,
    val accountHint: String?, val card: Boolean, val reference: String?, val direction: String)

object SmsParser {
    private val amount = Regex("(?:₹|INR|Rs\\.?)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?![0-9.])", RegexOption.IGNORE_CASE)
    private val auth = Regex("\\b(otp|passcode|pin|cvv|verification|one.time.password)\\b", RegexOption.IGNORE_CASE)
    private val account = Regex("(?:a/?c|acct|account|card)(?:\\s+(?:no\\.?|ending(?:\\s+in)?))?[ :*xX.-]*([0-9]{3,4})\\b", RegexOption.IGNORE_CASE)
    private val ref = Regex("(?:ref(?:erence)?|utr|txn)[ :#.-]*(?:no\\.?\\s*)?([A-Z0-9]{6,})\\b", RegexOption.IGNORE_CASE)
    fun parse(sender: String, body: String): SmsCandidate? {
        // Only business sender headers; never interpret personal-number messages as bank alerts.
        if (!Regex("(?:[A-Za-z]{2}-)?[A-Za-z][A-Za-z0-9]{2,15}(?:-[A-Za-z])?").matches(sender)) return null
        if (SmsAccounts.institution(sender) == null) return null
        if (auth.containsMatchIn(body) || body.length > 10_000) return null
        val lower = body.lowercase(Locale.ROOT)
        if (Regex("\\b(?:will be|scheduled|due|minimum due|bill generated|offer|eligible)\\b").containsMatchIn(lower)) return null
        val found = amount.find(body) ?: return null
        val value = runCatching { Money.parse(found.groupValues[1]) }.getOrNull() ?: return null
        val kind = when {
            "credit card" in lower && ("payment received" in lower || "payment towards" in lower) -> Kind.CARD_PAYMENT
            Regex("\\brefund(?:ed)?\\b").containsMatchIn(lower) -> Kind.REFUND
            Regex("\\b(?:self transfer|own account)\\b").containsMatchIn(lower) -> Kind.TRANSFER
            Regex("\\b(?:debited|spent|paid|withdrawn|purchase)\\b").containsMatchIn(lower) -> Kind.EXPENSE
            Regex("\\b(?:credited|received|deposited)\\b").containsMatchIn(lower) -> Kind.INCOME
            else -> return null
        }
        val status = when {
            Regex("\\b(?:failed|declined|unsuccessful)\\b").containsMatchIn(lower) -> Status.FAILED
            Regex("\\b(?:reversed|reversal)\\b").containsMatchIn(lower) -> Status.REVERSED
            Regex("\\b(?:pending|processing)\\b").containsMatchIn(lower) -> Status.PENDING
            else -> Status.POSTED
        }
        val markers = if (kind in listOf(Kind.INCOME, Kind.REFUND)) listOf("from", "to", "at") else listOf("to", "at", "from")
        val merchant = markers.firstNotNullOfOrNull { marker ->
            Regex("\\b$marker\\s+(.+?)(?=\\s+(?:on|via|using|ref|txn|a/c|avl|available)\\b|[.;]|$)", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.length in 2..80 &&
                    !Regex("^(?:your\\b|a/c\\b|account\\b|card\\b)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        } ?: sender
        val matches = account.findAll(body).toList()
        val identified = matches.singleOrNull()
        return SmsCandidate(value, kind, status, merchant, identified?.groupValues?.get(1),
            identified?.value?.contains("card", true) == true, ref.find(body)?.groupValues?.get(1)?.uppercase(Locale.ROOT),
            if (kind in listOf(Kind.INCOME, Kind.REFUND) || (kind == Kind.CARD_PAYMENT && "received" in lower) ||
                (kind == Kind.TRANSFER && "credited" in lower && "debited" !in lower)) "in" else "out")
    }
}
