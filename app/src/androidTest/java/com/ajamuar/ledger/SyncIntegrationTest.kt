package com.ajamuar.ledger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncIntegrationTest {
    @Test fun pairedTlsSyncCarriesEditsAndDeletion() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val json = runCatching { testContext.assets.open("pairing.json").bufferedReader().use { it.readText() } }.getOrNull()
        assumeTrue("Generate the disposable local receiver config to run this integration test", json != null)
        val context: Context = ApplicationProvider.getApplicationContext()
        val repo = (context.applicationContext as LedgerApplication).repository
        assumeTrue("Integration test only runs against an empty development ledger", repo.dao.snapshot().all { it.deleted && it.merchant in listOf("SYNTHETIC TLS TEST", "UI TEST CAFE") })
        repo.initialise()
        val old = repo.vault.read("pairing")
        val settings = context.getSharedPreferences("ledger", Context.MODE_PRIVATE)
        val wasPaused = settings.getBoolean("syncPaused", false)
        settings.edit().putBoolean("syncPaused", true).commit()
        repo.vault.write("pairing", requireNotNull(json).toByteArray())
        val entry = Entry(amount = 12345, merchant = "SYNTHETIC TLS TEST")
        suspend fun send() = TestListenableWorkerBuilder<SyncWorker>(context).setInputData(androidx.work.workDataOf("force" to true)).build().doWork()
        try {
            repo.save(entry)
            assertEquals(ListenableWorker.Result.success(), send())
            assertTrue(repo.dao.pending().isEmpty())
            val originalPairing = Pairing.parse(json)
            repo.vault.write("pairing", originalPairing.copy(pin = "0".repeat(64)).json().toByteArray())
            repo.save(repo.dao.entry(entry.id)!!.copy(category = "Travel", note = "Synthetic correction"))
            assertEquals(ListenableWorker.Result.failure(), send())
            assertTrue(repo.dao.pending().isNotEmpty())
            repo.vault.write("pairing", json.toByteArray())
            assertEquals(ListenableWorker.Result.success(), send())
            assertTrue(repo.dao.pending().isEmpty())
            repo.save(repo.dao.entry(entry.id)!!.copy(deleted = true))
            assertEquals(ListenableWorker.Result.success(), send())
            assertTrue(repo.dao.pending().isEmpty())
        } finally {
            repo.dao.entry(entry.id)?.takeIf { !it.deleted }?.let { repo.save(it.copy(deleted = true)); send() }
            if (old == null) repo.vault.forget("pairing") else repo.vault.write("pairing", old)
            repo.syncState(if (old == null) "Not paired" else "Paired")
            settings.edit().putBoolean("syncPaused", wasPaused).commit()
        }
    }
}
