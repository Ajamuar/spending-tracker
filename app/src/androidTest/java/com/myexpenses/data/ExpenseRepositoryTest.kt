package com.myexpenses.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myexpenses.data.local.AppDatabase
import com.myexpenses.data.model.AccountType
import com.myexpenses.data.model.IngestionOutcome
import com.myexpenses.data.model.SourceType
import com.myexpenses.data.model.TransactionStatus
import com.myexpenses.data.model.TransactionType
import com.myexpenses.ingestion.Hashing
import com.myexpenses.ingestion.TransactionCandidate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExpenseRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ExpenseRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ExpenseRepository(
            database,
            database.transactionDao(),
            database.categoryDao(),
            database.accountDao(),
            database.budgetDao(),
            database.ingestionDao(),
            database.parserRuleDao(),
        )
        repository.initialize()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun initializationSeedsCategoriesCashAndExtensibleParserRules() = runBlocking {
        assertTrue(database.categoryDao().getAll().any { it.name == "Food & dining" })
        assertTrue(database.accountDao().getAll().any { it.type == AccountType.CASH })
        val rules = database.parserRuleDao().getEnabled()
        assertTrue(rules.any { it.id == "system-upi" })
        assertTrue(rules.any { it.id == "system-card" })
        assertTrue(rules.any { it.id == "system-bank" })
    }

    @Test
    fun sameReferenceDeduplicatesAcrossSourcesEvenHoursApart() = runBlocking {
        val referenceHash = Hashing.sha256("435678901234")
        val first = repository.import(
            candidate(
                externalId = "sms:1",
                source = SourceType.SMS,
                occurredAt = 1_700_000_000_000,
                referenceHash = referenceHash,
            ),
        )
        val duplicate = repository.import(
            candidate(
                externalId = "gmail:1",
                source = SourceType.EMAIL,
                occurredAt = 1_700_021_600_000,
                referenceHash = referenceHash,
            ),
        )

        assertEquals(IngestionOutcome.IMPORTED, first.outcome)
        assertEquals(IngestionOutcome.DUPLICATE, duplicate.outcome)
        assertEquals(first.transactionId, duplicate.transactionId)
        assertEquals(1, database.transactionDao().getAll().size)
    }

    @Test
    fun accountCanBeAddedAndArchivedWithoutDeletingHistory() = runBlocking {
        repository.saveAccount(
            id = null,
            name = "Everyday card",
            type = AccountType.CREDIT_CARD,
            lastFour = "7788",
            archived = false,
        )
        val added = database.accountDao().getAll().single { it.name == "Everyday card" }
        assertFalse(added.isArchived)

        repository.saveAccount(
            id = added.id,
            name = added.name,
            type = added.type,
            lastFour = added.lastFour,
            archived = true,
        )

        assertTrue(database.accountDao().getById(added.id)!!.isArchived)
    }

    private fun candidate(
        externalId: String,
        source: SourceType,
        occurredAt: Long,
        referenceHash: String?,
    ) = TransactionCandidate(
        externalId = externalId,
        source = source,
        amountMinor = 34_900,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.POSTED,
        merchant = "TEST CAFE",
        accountHint = "4821",
        categoryName = "Food & dining",
        occurredAt = occurredAt,
        referenceHash = referenceHash,
        confidence = 0.92f,
        contentFingerprint = Hashing.sha256(externalId),
    )
}
