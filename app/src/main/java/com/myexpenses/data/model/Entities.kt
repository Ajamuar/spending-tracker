package com.myexpenses.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class TransactionType {
    EXPENSE,
    INCOME,
    REFUND,
    TRANSFER,
    CARD_PAYMENT,
}

enum class TransactionStatus {
    POSTED,
    PENDING,
    FAILED,
    REVERSED,
}

enum class SourceType {
    MANUAL,
    SMS,
    EMAIL,
    CSV,
    BACKUP,
}

enum class AccountType {
    BANK,
    CREDIT_CARD,
    WALLET,
    CASH,
    OTHER,
}

enum class IngestionOutcome {
    IMPORTED,
    DUPLICATE,
    REJECTED,
    ERROR,
}

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["type", "lastFour"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: AccountType,
    val lastFour: String? = null,
    val balanceMinor: Long? = null,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val icon: String,
    val color: Long,
    val isIncome: Boolean = false,
    val isSystem: Boolean = false,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("occurredAt"),
        Index("accountId"),
        Index("categoryId"),
        Index(value = ["amountMinor", "occurredAt", "type"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val amountMinor: Long,
    val type: TransactionType,
    val status: TransactionStatus = TransactionStatus.POSTED,
    val merchant: String,
    val accountId: String? = null,
    val accountHint: String? = null,
    val categoryId: String? = null,
    val occurredAt: Long,
    val source: SourceType,
    val sourceConfidence: Float = 1f,
    val referenceHash: String? = null,
    val note: String = "",
    val isExcluded: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["categoryId", "monthKey"], unique = true)],
)
data class BudgetEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val categoryId: String,
    val monthKey: String,
    val limitMinor: Long,
)

@Entity(
    tableName = "ingestion_records",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["source", "externalIdHash"], unique = true),
        Index("transactionId"),
        Index("importedAt"),
    ],
)
data class IngestionRecordEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val source: SourceType,
    val externalIdHash: String,
    val contentFingerprint: String,
    val transactionId: String? = null,
    val outcome: IngestionOutcome,
    val detail: String? = null,
    val importedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "parser_rules",
    indices = [Index("priority")],
)
data class ParserRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val senderRegex: String? = null,
    val bodyRegex: String,
    val merchantRegex: String? = null,
    val categoryName: String? = null,
    val priority: Int = 100,
    val isEnabled: Boolean = true,
    val isSystem: Boolean = false,
)

data class LedgerSnapshot(
    val transactions: List<TransactionEntity>,
    val accounts: List<AccountEntity>,
    val categories: List<CategoryEntity>,
    val budgets: List<BudgetEntity>,
    val parserRules: List<ParserRuleEntity>,
)
