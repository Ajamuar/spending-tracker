package com.ajamuar.ledger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

class AiTest {
    private val body = "Avl balance INR 9000.00. INR 249.50 dr from a/c XX4821 to SYNTHETIC CAFE. Ref ABC123456"
    private fun batch(): AiBatch {
        val choice = smsChoices("VM-HDFCBK", body).first { it.pattern.amountIndex == 1 && it.pattern.marker == "to" }
        return AiBatch(listOf(AiSample("s0", "HDFCBK", AiPrivacy.shape(body), listOf(choice))), listOf(AiMerchant("m0", "synthetic cafe", "synthetic cafe")))
    }
    private fun response(sample: String = "s0", merchant: String = "m0", category: String = "Food & dining"): JSONObject {
        val content = JSONObject().put("patterns", JSONArray().put(JSONObject().put("sample_id", sample).put("choice_id", batch().samples.single().choices.single().id)))
            .put("categories", JSONArray().put(JSONObject().put("merchant_id", merchant).put("category", category)))
        return JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject().put("type", "message")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", content.toString())))))
            .put("usage", JSONObject().put("input_tokens", 100).put("output_tokens", 50))
    }

    @Test fun requestsExcludeLocalValuesAndValidateAllReturnedIds() {
        val client = AiClient()
        val request = client.request(batch()).toString()
        listOf("249.50", "9000", "4821", "ABC123456", "account_id", "occurred_at").forEach { assertFalse(it, request.contains(it)) }
        assertFalse(client.request(batch()).getBoolean("store"))
        assertEquals(4000, client.request(batch()).getInt("max_output_tokens"))
        val result = client.decode(response(), batch())
        assertEquals("Food & dining", result.categories.single().second)
        assertEquals(100, result.inputTokens)
        listOf(response(sample = "invented"), response(merchant = "invented"), response(category = "Fake category"), response().put("status", "incomplete")).forEach {
            assertTrue(runCatching { client.decode(it, batch()) }.isFailure)
        }
    }

    @Test fun transportRestrictsDestinationRejectsRedirectsAndDoesNotExposeErrors() = runBlocking {
        val fake = FakeConnection(200, response().toString())
        val client = AiClient { url -> assertEquals("https://api.openai.com/v1/responses", url.toString()); fake }
        assertEquals(1, client.analyse("synthetic-test-key-0000000000", batch()).patterns.size)
        assertFalse(fake.instanceFollowRedirects)
        assertTrue(fake.disconnected)
        assertFalse(fake.payload.toString().contains("synthetic-test-key"))
        for (code in listOf(401, 429, 302, 500)) {
            val rejected = FakeConnection(code, "synthetic-private-error-body")
            val error = runCatching { AiClient { rejected }.analyse("synthetic-test-key-0000000000", batch()) }.exceptionOrNull()!!
            assertFalse(error.message.orEmpty().contains("synthetic-private-error-body"))
            assertFalse(error.message.orEmpty().contains("synthetic-test-key"))
            assertTrue(rejected.disconnected)
        }
    }

    @Test fun approvedRulesRespectSelectionReviewedEditsAndBackupExclusions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ai-test-${UUID.randomUUID()}.db"; val repo = Repository(context, name)
        try {
            repo.initialise()
            val account = repo.discoverAccount("VM-HDFCBK", batch().samples.single().choices.single().candidate)!!
            val suggestions = AiClient().decode(response(), batch())
            val original = Entry(amount = 24950, merchant = "SYNTHETIC CAFE", source = "sms", reviewed = false, accountId = account.id)
            repo.setSmsTracking(account.id, true)
            assertFalse(repo.ingest("VM-HDFCBK", body, 99999))
            assertTrue(repo.dao.snapshot().isEmpty())
            assertTrue(repo.dao.pending().isEmpty())
            repo.save(original)
            val manual = original.copy(id = UUID.randomUUID().toString(), category = "Health")
            repo.save(manual)
            val otherAccount = Account(name = "SYNTHETIC OTHER BANK")
            repo.account(otherAccount)
            val unselected = original.copy(id = UUID.randomUUID().toString(), accountId = otherAccount.id)
            repo.save(unselected)
            val reviewed = original.copy(id = UUID.randomUUID().toString(), reviewed = true)
            repo.save(reviewed)
            repo.setSmsTracking(account.id, true)
            assertEquals(1, repo.applyAiSuggestions(suggestions, suggestions.patterns.map { it.pattern.key }.toSet(), setOf("m0")))
            assertEquals("Food & dining", repo.dao.entry(original.id)!!.category)
            assertFalse(repo.dao.entry(original.id)!!.reviewed)
            assertEquals("Uncategorised", repo.dao.entry(reviewed.id)!!.category)
            assertEquals("Health", repo.dao.entry(manual.id)!!.category)
            assertEquals("Uncategorised", repo.dao.entry(unselected.id)!!.category)
            assertTrue(repo.dao.pending().any { it.entryId == original.id })
            assertTrue(repo.ingest("VM-HDFCBK", body, 100000))
            assertEquals("Food & dining", repo.dao.snapshot().single { it.sourceKey != null }.category)
            repo.setSmsTracking(account.id, false)
            assertFalse(repo.ingest("VM-HDFCBK", body.replace("ABC123456", "DEF654321"), 100001))
            assertEquals(5, repo.dao.snapshot().size)
            repo.vault.write("openai-key-test", "synthetic-test-key-0000000000".toByteArray())
            try {
                val password = "synthetic-backup-password".toCharArray()
                val backup = repo.backup(password.copyOf())
                val plain = Backup.decrypt(backup, password).toString(Charsets.UTF_8)
                assertFalse(plain.contains("synthetic-test-key"))
                assertFalse(plain.contains("sms-pattern:"))
                assertFalse(plain.contains(body))
            } finally { repo.vault.forget("openai-key-test") }
        } finally { repo.db.close(); context.deleteDatabase(name) }
    }

    @Test fun changedMerchantRuleRejectsWholeApprovalAtomically() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ai-test-${UUID.randomUUID()}.db"; val repo = Repository(context, name)
        try {
            repo.initialise()
            val suggestions = AiClient().decode(response(), batch())
            repo.dao.putRule(MerchantRule("synthetic cafe", "Other"))
            assertTrue(runCatching { repo.applyAiSuggestions(suggestions, suggestions.patterns.map { it.pattern.key }.toSet(), setOf("m0")) }.isFailure)
            assertTrue(repo.dao.smsPatterns().isEmpty())
            assertEquals("Other", repo.dao.rule("synthetic cafe")!!.category)
        } finally { repo.db.close(); context.deleteDatabase(name) }
    }

    private class FakeConnection(private val code: Int, private val response: String) : HttpsURLConnection(URL("https://api.openai.com/v1/responses")) {
        val payload = ByteArrayOutputStream()
        var disconnected = false
        override fun getOutputStream() = payload
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
        override fun getResponseCode() = code
        override fun disconnect() { disconnected = true }
        override fun connect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "TLS_AES_128_GCM_SHA256"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
}
