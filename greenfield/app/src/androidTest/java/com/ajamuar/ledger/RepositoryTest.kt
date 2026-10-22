package com.ajamuar.ledger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var repo: Repository
    private lateinit var context: Context
    private lateinit var name: String
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        name = "test-${UUID.randomUUID()}.db"
        repo = Repository(context, name)
        repo.initialise()
    }
    @After fun cleanup() { repo.db.close(); context.deleteDatabase(name) }

    @Test fun queueCoalescesMappingsAndDoesNotLoseNewerEdits() = runBlocking {
        val initial = Entry(amount = 24950, merchant = "TEST CAFE")
        repo.save(initial)
        val first = repo.dao.pending().single()
        repo.account(Account("cash", "Cash", "Cash", "test-cash"))
        val mapped = repo.dao.pending().single()
        assertTrue(mapped.revision > first.revision)
        assertTrue(mapped.payload.contains("test-cash"))
        repo.save(repo.dao.entry(initial.id)!!.copy(note = "Synthetic correction"))
        repo.dao.acknowledge(mapped.entryId, mapped.revision)
        assertEquals(mapped.revision + 1, repo.dao.pending().single().revision)
        assertTrue(repo.dao.pending().single().payload.contains("Synthetic correction"))
    }

    @Test fun rescanPreservesEditsDeletesAndHandlesReversal() = runBlocking {
        val body = "Rs.249.50 debited from a/c XX4821 to TEST CAFE. Ref 123456789012"
        assertTrue(repo.ingest("VM-TESTBK", body, 100000))
        assertFalse(repo.ingest("VM-TESTBK", body, 100001))
        val entry = repo.dao.snapshot().single()
        repo.save(entry.copy(category = "Food", reviewed = true))
        assertFalse(repo.ingest("VM-TESTBK", body, 100002))
        assertEquals("Food", repo.dao.snapshot().single().category)
        assertTrue(repo.ingest("VM-TESTBK", "$body Transaction reversed.", 100003))
        val reversed = repo.dao.snapshot().single()
        assertEquals(Status.REVERSED.name, reversed.status)
        assertEquals(0L, reversed.personalSpend())
        repo.save(reversed.copy(deleted = true))
        assertFalse(repo.ingest("VM-TESTBK", body, 100004))
        assertTrue(repo.dao.snapshot().single().deleted)
    }

    @Test fun backupRestoresIdentityAndHistoryOnlyIntoEmptyLedger() = runBlocking {
        repo.save(Entry(amount = 12345, merchant = "TEST STORE", note = "Synthetic note"))
        val bytes = repo.backup("synthetic-test-password".toCharArray())
        val restoredName = "test-${UUID.randomUUID()}.db"
        val restored = Repository(context, restoredName)
        try {
            restored.initialise()
            restored.restore(bytes, "synthetic-test-password".toCharArray())
            assertEquals(repo.identity(), restored.identity())
            assertEquals(repo.dao.snapshot(), restored.dao.snapshot())
            assertEquals(1, restored.dao.pending().size)
            assertTrue(runCatching { restored.restore(bytes, "synthetic-test-password".toCharArray()) }.isFailure)
            assertEquals(1, restored.dao.snapshot().size)
        } finally { restored.db.close(); context.deleteDatabase(restoredName) }
    }
    @Test fun privateSplitUpdatesPersonalSpendAndRemovalRestoresIt() = runBlocking {
        val group = PrivateGroup(name = "SYNTHETIC GROUP", members = "[\"You\",\"Other\"]")
        repo.dao.putGroup(group)
        val purchase = Entry(amount = 10001, merchant = "SYNTHETIC MEAL")
        repo.save(purchase)
        val split = Split(groupId = group.id, description = "SYNTHETIC MEAL", payer = "You", shares = "{\"You\":5001,\"Other\":5000}", amount = 10001, entryId = purchase.id)
        repo.saveSplit(split, "cash")
        assertEquals(5001L, repo.dao.entry(purchase.id)!!.personalSpend())
        assertTrue(runCatching { repo.save(repo.dao.entry(purchase.id)!!.copy(deleted = true)) }.isFailure)
        repo.removeSplit(repo.dao.split(split.id)!!)
        assertEquals(10001L, repo.dao.entry(purchase.id)!!.personalSpend())
    }
    @Test fun accountMergeRedirectsFutureImportsWithoutRecreatingHistory() = runBlocking {
        val body = "Rs.249.50 debited from a/c XX4821 to TEST CAFE. Ref 123456789012"
        repo.ingest("VM-TESTBK", body, 100000)
        val original = repo.dao.snapshot().single().accountId
        val target = Account(name = "Synthetic bank", repoId = "test-bank")
        repo.account(target)
        repo.mergeAccounts(original, target.id)
        assertTrue(repo.dao.account(original)!!.archived)
        assertFalse(repo.ingest("VM-TESTBK", body, 100001))
        assertTrue(repo.ingest("VM-TESTBK", body.replace("123456789012", "123456789013"), 100002))
        assertEquals(listOf(target.id), repo.dao.snapshot().map { it.accountId }.distinct())
        val backup = repo.backup("synthetic-test-password".toCharArray())
        val restoredName = "test-${UUID.randomUUID()}.db"
        val restored = Repository(context, restoredName)
        try {
            restored.initialise(); restored.restore(backup, "synthetic-test-password".toCharArray())
            assertEquals(repo.dao.aliases(), restored.dao.aliases())
        } finally { restored.db.close(); context.deleteDatabase(restoredName) }
    }
}
