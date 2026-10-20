package com.myexpenses.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import com.myexpenses.data.model.AccountEntity
import com.myexpenses.data.model.AccountType
import com.myexpenses.data.model.BudgetEntity
import com.myexpenses.data.model.CategoryEntity
import com.myexpenses.data.model.IngestionOutcome
import com.myexpenses.data.model.IngestionRecordEntity
import com.myexpenses.data.model.ParserRuleEntity
import com.myexpenses.data.model.SourceType
import com.myexpenses.data.model.TransactionEntity
import com.myexpenses.data.model.TransactionStatus
import com.myexpenses.data.model.TransactionType
import kotlinx.coroutines.flow.Flow

class DatabaseConverters {
    @TypeConverter fun transactionType(value: TransactionType): String = value.name
    @TypeConverter fun transactionType(value: String): TransactionType = TransactionType.valueOf(value)
    @TypeConverter fun transactionStatus(value: TransactionStatus): String = value.name
    @TypeConverter fun transactionStatus(value: String): TransactionStatus = TransactionStatus.valueOf(value)
    @TypeConverter fun sourceType(value: SourceType): String = value.name
    @TypeConverter fun sourceType(value: String): SourceType = SourceType.valueOf(value)
    @TypeConverter fun accountType(value: AccountType): String = value.name
    @TypeConverter fun accountType(value: String): AccountType = AccountType.valueOf(value)
    @TypeConverter fun ingestionOutcome(value: IngestionOutcome): String = value.name
    @TypeConverter fun ingestionOutcome(value: String): IngestionOutcome = IngestionOutcome.valueOf(value)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransactionEntity?

    @Query(
        """
        SELECT * FROM transactions
        WHERE amountMinor = :amountMinor
          AND type = :type
          AND occurredAt BETWEEN :from AND :to
          AND status != 'FAILED'
        ORDER BY ABS(occurredAt - :target)
        LIMIT 20
        """,
    )
    suspend fun findPotentialDuplicates(
        amountMinor: Long,
        type: TransactionType,
        from: Long,
        to: Long,
        target: Long,
    ): List<TransactionEntity>

    @Query(
        """
        SELECT * FROM transactions
        WHERE referenceHash = :referenceHash
          AND amountMinor = :amountMinor
          AND type = :type
          AND status != 'FAILED'
        ORDER BY occurredAt DESC
        LIMIT 1
        """,
    )
    suspend fun findByReference(
        referenceHash: String,
        amountMinor: Long,
        type: TransactionType,
    ): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TransactionEntity): Long

    @Upsert
    suspend fun upsertAll(entities: List<TransactionEntity>)

    @Update
    suspend fun update(entity: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isIncome, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY isIncome, name")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<CategoryEntity>)

    @Upsert
    suspend fun upsertAll(entities: List<CategoryEntity>)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY isArchived, name")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY name")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY name")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): AccountEntity?

    @Query(
        """
        SELECT * FROM accounts
        WHERE type = :type
          AND ((lastFour IS NULL AND :lastFour IS NULL) OR lastFour = :lastFour)
        LIMIT 1
        """,
    )
    suspend fun find(type: AccountType, lastFour: String?): AccountEntity?

    @Upsert
    suspend fun upsert(entity: AccountEntity)

    @Upsert
    suspend fun upsertAll(entities: List<AccountEntity>)

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE monthKey = :monthKey")
    fun observeForMonth(monthKey: String): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets ORDER BY monthKey DESC")
    suspend fun getAll(): List<BudgetEntity>

    @Upsert
    suspend fun upsert(entity: BudgetEntity)

    @Upsert
    suspend fun upsertAll(entities: List<BudgetEntity>)

    @Query("DELETE FROM budgets WHERE categoryId = :categoryId AND monthKey = :monthKey")
    suspend fun delete(categoryId: String, monthKey: String)

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()
}

@Dao
interface IngestionDao {
    @Query("SELECT EXISTS(SELECT 1 FROM ingestion_records WHERE source = :source AND externalIdHash = :hash)")
    suspend fun exists(source: SourceType, hash: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: IngestionRecordEntity): Long

    @Query("SELECT * FROM ingestion_records ORDER BY importedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 25): Flow<List<IngestionRecordEntity>>

    @Query("SELECT COUNT(*) FROM ingestion_records WHERE outcome = 'IMPORTED'")
    fun observeImportedCount(): Flow<Int>

    @Query("DELETE FROM ingestion_records")
    suspend fun deleteAll()
}

@Dao
interface ParserRuleDao {
    @Query("SELECT * FROM parser_rules WHERE isEnabled = 1 ORDER BY priority, name")
    suspend fun getEnabled(): List<ParserRuleEntity>

    @Query("SELECT * FROM parser_rules ORDER BY priority, name")
    suspend fun getAll(): List<ParserRuleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<ParserRuleEntity>)

    @Upsert
    suspend fun upsertAll(entities: List<ParserRuleEntity>)

    @Query("DELETE FROM parser_rules")
    suspend fun deleteAll()
}

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        AccountEntity::class,
        BudgetEntity::class,
        IngestionRecordEntity::class,
        ParserRuleEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun budgetDao(): BudgetDao
    abstract fun ingestionDao(): IngestionDao
    abstract fun parserRuleDao(): ParserRuleDao
}
