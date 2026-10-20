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
import com.myexpenses.data.model.AccountEntity
import com.myexpenses.data.model.AccountType
import com.myexpenses.data.model.BudgetEntity
import com.myexpenses.data.model.CategoryEntity
import com.myexpenses.data.model.IngestionOutcome
import com.myexpenses.data.model.IngestionRecordEntity
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
        LIMIT 1
        """,
    )
    suspend fun findPotentialDuplicate(
        amountMinor: Long,
        type: TransactionType,
        from: Long,
        to: Long,
        target: Long,
    ): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TransactionEntity>)

    @Update
    suspend fun update(entity: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: String)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CategoryEntity>)
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY name")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY name")
    suspend fun getAll(): List<AccountEntity>

    @Query(
        """
        SELECT * FROM accounts
        WHERE type = :type
          AND ((lastFour IS NULL AND :lastFour IS NULL) OR lastFour = :lastFour)
        LIMIT 1
        """,
    )
    suspend fun find(type: AccountType, lastFour: String?): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AccountEntity>)
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE monthKey = :monthKey")
    fun observeForMonth(monthKey: String): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets ORDER BY monthKey DESC")
    suspend fun getAll(): List<BudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BudgetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<BudgetEntity>)

    @Query("DELETE FROM budgets WHERE categoryId = :categoryId AND monthKey = :monthKey")
    suspend fun delete(categoryId: String, monthKey: String)
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
}

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        AccountEntity::class,
        BudgetEntity::class,
        IngestionRecordEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun budgetDao(): BudgetDao
    abstract fun ingestionDao(): IngestionDao
}
