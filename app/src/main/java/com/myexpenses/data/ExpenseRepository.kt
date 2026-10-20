package com.myexpenses.data

import androidx.room.withTransaction
import com.myexpenses.data.local.AccountDao
import com.myexpenses.data.local.AppDatabase
import com.myexpenses.data.local.BudgetDao
import com.myexpenses.data.local.CategoryDao
import com.myexpenses.data.local.IngestionDao
import com.myexpenses.data.local.TransactionDao
import com.myexpenses.data.model.AccountEntity
import com.myexpenses.data.model.AccountType
import com.myexpenses.data.model.BudgetEntity
import com.myexpenses.data.model.CategoryEntity
import com.myexpenses.data.model.IngestionOutcome
import com.myexpenses.data.model.IngestionRecordEntity
import com.myexpenses.data.model.LedgerSnapshot
import com.myexpenses.data.model.SourceType
import com.myexpenses.data.model.TransactionEntity
import com.myexpenses.data.model.TransactionStatus
import com.myexpenses.data.model.TransactionType
import com.myexpenses.ingestion.Hashing
import com.myexpenses.ingestion.TransactionCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ImportResult(
    val outcome: IngestionOutcome,
    val transactionId: String? = null,
    val detail: String,
)

@Singleton
class ExpenseRepository @Inject constructor(
    private val database: AppDatabase,
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val accountDao: AccountDao,
    private val budgetDao: BudgetDao,
    private val ingestionDao: IngestionDao,
) {
    private val importMutex = Mutex()

    val transactions: Flow<List<TransactionEntity>> = transactionDao.observeAll()
    val categories: Flow<List<CategoryEntity>> = categoryDao.observeAll()
    val accounts: Flow<List<AccountEntity>> = accountDao.observeActive()
    val recentIngestion: Flow<List<IngestionRecordEntity>> = ingestionDao.observeRecent()
    val importedCount: Flow<Int> = ingestionDao.observeImportedCount()

    fun budgets(month: YearMonth): Flow<List<BudgetEntity>> =
        budgetDao.observeForMonth(month.toString())

    suspend fun initialize() {
        categoryDao.insertAll(DEFAULT_CATEGORIES)
        if (accountDao.find(AccountType.CASH, null) == null) {
            accountDao.upsert(
                AccountEntity(
                    id = CASH_ACCOUNT_ID,
                    name = "Cash",
                    type = AccountType.CASH,
                ),
            )
        }
    }

    suspend fun import(candidate: TransactionCandidate): ImportResult = importMutex.withLock {
        val externalHash = Hashing.sha256(candidate.externalId)
        if (ingestionDao.exists(candidate.source, externalHash)) {
            return ImportResult(IngestionOutcome.DUPLICATE, detail = "Already processed")
        }

        val duplicateWindow = 10 * 60 * 1000L
        val duplicate = transactionDao.findPotentialDuplicate(
            amountMinor = candidate.amountMinor,
            type = candidate.type,
            from = candidate.occurredAt - duplicateWindow,
            to = candidate.occurredAt + duplicateWindow,
            target = candidate.occurredAt,
        )?.takeIf {
            accountHintsMatch(it.accountHint, candidate.accountHint) &&
                merchantsMatch(it.merchant, candidate.merchant)
        }
        if (duplicate != null) {
            ingestionDao.insert(
                candidate.toRecord(
                    externalHash = externalHash,
                    outcome = IngestionOutcome.DUPLICATE,
                    transactionId = duplicate.id,
                    detail = "Matched ${duplicate.source.name.lowercase()} transaction",
                ),
            )
            return ImportResult(
                IngestionOutcome.DUPLICATE,
                duplicate.id,
                "Matched an existing transaction",
            )
        }

        val category = categoryDao.getByName(candidate.categoryName)
            ?: categoryDao.getByName("Other")
        val account = candidate.accountHint?.let { hint ->
            accountDao.find(AccountType.BANK, hint) ?: AccountEntity(
                name = "Account •$hint",
                type = AccountType.BANK,
                lastFour = hint,
            ).also(accountDao::upsert)
        }
        val transaction = TransactionEntity(
            amountMinor = candidate.amountMinor,
            type = candidate.type,
            status = candidate.status,
            merchant = candidate.merchant,
            accountId = account?.id,
            accountHint = candidate.accountHint,
            categoryId = category?.id,
            occurredAt = candidate.occurredAt,
            source = candidate.source,
            sourceConfidence = candidate.confidence,
            reference = candidate.reference,
            isExcluded = candidate.status != TransactionStatus.POSTED,
        )
        database.withTransaction {
            transactionDao.insert(transaction)
            ingestionDao.insert(
                candidate.toRecord(
                    externalHash = externalHash,
                    outcome = IngestionOutcome.IMPORTED,
                    transactionId = transaction.id,
                    detail = "Imported ${candidate.source.name.lowercase()} alert",
                ),
            )
        }
        ImportResult(IngestionOutcome.IMPORTED, transaction.id, "Transaction imported")
    }

    suspend fun recordIgnored(
        source: SourceType,
        externalId: String,
        fingerprint: String,
        reason: String,
    ) {
        val externalHash = Hashing.sha256(externalId)
        if (ingestionDao.exists(source, externalHash)) return
        ingestionDao.insert(
            IngestionRecordEntity(
                source = source,
                externalIdHash = externalHash,
                contentFingerprint = fingerprint,
                outcome = IngestionOutcome.REJECTED,
                detail = reason,
            ),
        )
    }

    suspend fun addManual(
        amountMinor: Long,
        type: TransactionType,
        merchant: String,
        categoryId: String?,
        accountId: String?,
        occurredAt: Long,
        note: String,
    ) {
        require(amountMinor > 0) { "Amount must be greater than zero" }
        transactionDao.insert(
            TransactionEntity(
                amountMinor = amountMinor,
                type = type,
                merchant = merchant.trim().ifEmpty { "Manual transaction" },
                categoryId = categoryId,
                accountId = accountId,
                occurredAt = occurredAt,
                source = SourceType.MANUAL,
                note = note.trim(),
            ),
        )
    }

    suspend fun updateTransaction(
        id: String,
        merchant: String,
        categoryId: String?,
        note: String,
        excluded: Boolean,
        status: TransactionStatus,
    ) {
        val current = transactionDao.getById(id) ?: return
        transactionDao.update(
            current.copy(
                merchant = merchant.trim().ifEmpty { current.merchant },
                categoryId = categoryId,
                note = note.trim(),
                isExcluded = excluded,
                status = status,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteTransaction(id: String) = transactionDao.delete(id)

    suspend fun setBudget(categoryId: String, month: YearMonth, limitMinor: Long) {
        if (limitMinor <= 0) {
            budgetDao.delete(categoryId, month.toString())
            return
        }
        budgetDao.upsert(
            BudgetEntity(
                id = "$categoryId:${month}",
                categoryId = categoryId,
                monthKey = month.toString(),
                limitMinor = limitMinor,
            ),
        )
    }

    suspend fun snapshot(): LedgerSnapshot = LedgerSnapshot(
        transactions = transactionDao.getAll(),
        accounts = accountDao.getAll(),
        categories = categoryDao.getAll(),
        budgets = budgetDao.getAll(),
    )

    suspend fun importCsv(transactions: List<TransactionEntity>) {
        transactions.forEach { transactionDao.insert(it.copy(source = SourceType.CSV)) }
    }

    suspend fun restore(snapshot: LedgerSnapshot) {
        database.withTransaction {
            categoryDao.upsertAll(snapshot.categories)
            accountDao.upsertAll(snapshot.accounts)
            transactionDao.upsertAll(snapshot.transactions)
            budgetDao.upsertAll(snapshot.budgets)
        }
    }

    private fun accountHintsMatch(first: String?, second: String?): Boolean =
        first == null || second == null || first == second

    private fun merchantsMatch(first: String, second: String): Boolean {
        val left = first.lowercase().filter(Char::isLetterOrDigit)
        val right = second.lowercase().filter(Char::isLetterOrDigit)
        return left == right || left.contains(right) || right.contains(left)
    }

    private fun TransactionCandidate.toRecord(
        externalHash: String,
        outcome: IngestionOutcome,
        transactionId: String?,
        detail: String,
    ) = IngestionRecordEntity(
        source = source,
        externalIdHash = externalHash,
        contentFingerprint = contentFingerprint,
        transactionId = transactionId,
        outcome = outcome,
        detail = detail,
    )

    companion object {
        const val CASH_ACCOUNT_ID = "account-cash"

        val DEFAULT_CATEGORIES = listOf(
            category("category-food", "Food & dining", "🍲", 0xFFE76F51),
            category("category-groceries", "Groceries", "🛒", 0xFF2A9D8F),
            category("category-transport", "Transport", "🚕", 0xFFE9C46A),
            category("category-shopping", "Shopping", "🛍", 0xFFF4A261),
            category("category-bills", "Bills & utilities", "💡", 0xFF457B9D),
            category("category-housing", "Housing", "🏠", 0xFF6D597A),
            category("category-health", "Health", "💊", 0xFF43AA8B),
            category("category-entertainment", "Entertainment", "🎬", 0xFF9B5DE5),
            category("category-education", "Education", "📚", 0xFF277DA1),
            category("category-card", "Card payment", "💳", 0xFF577590),
            category("category-transfer", "Transfers", "↔", 0xFF90A4AE),
            category("category-refund", "Refunds", "↩", 0xFF52B788),
            category("category-income", "Income", "₹", 0xFF2D6A4F, isIncome = true),
            category("category-other", "Other", "•", 0xFF7D8597),
        )

        private fun category(
            id: String,
            name: String,
            icon: String,
            color: Long,
            isIncome: Boolean = false,
        ) = CategoryEntity(
            id = id,
            name = name,
            icon = icon,
            color = color,
            isIncome = isIncome,
            isSystem = true,
        )
    }
}
