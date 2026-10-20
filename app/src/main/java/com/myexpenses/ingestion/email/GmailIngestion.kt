package com.myexpenses.ingestion.email

import android.accounts.Account
import android.content.Context
import android.util.Base64
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.myexpenses.data.AppPreferences
import com.myexpenses.data.ExpenseRepository
import com.myexpenses.data.model.IngestionOutcome
import com.myexpenses.data.model.ParserRuleEntity
import com.myexpenses.data.model.SourceType
import com.myexpenses.ingestion.ParseInput
import com.myexpenses.ingestion.ParseResult
import com.myexpenses.ingestion.TransactionParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"

data class GmailSyncSummary(
    val inspected: Int,
    val imported: Int,
    val duplicates: Int,
    val ignored: Int,
    val errors: Int,
)

@Singleton
class GmailSyncService @Inject constructor(
    private val apiClient: GmailApiClient,
    private val preferences: AppPreferences,
) {
    suspend fun syncWithToken(accessToken: String): GmailSyncSummary {
        val query = preferences.settings.value.gmailQuery
        return try {
            val account = apiClient.getProfileEmail(accessToken)
            preferences.setGmailConnection(true, account)
            apiClient.sync(accessToken, query).also { summary ->
                preferences.recordGmailSync(
                    status = "Gmail sync: ${summary.imported} new, " +
                        "${summary.duplicates} matched, ${summary.ignored} ignored",
                )
            }
        } catch (error: Exception) {
            preferences.recordStatus(
                when (error) {
                    is GmailAuthorizationException -> "Reconnect Gmail to continue syncing"
                    is IOException -> "Gmail sync could not reach the network"
                    else -> "Gmail sync failed safely"
                },
            )
            throw error
        }
    }
}

@Singleton
class GmailApiClient @Inject constructor(
    private val parser: TransactionParser,
    private val repository: ExpenseRepository,
) {
    suspend fun getProfileEmail(accessToken: String): String = withContext(Dispatchers.IO) {
        getJson("$BASE_URL/profile", accessToken).getString("emailAddress")
    }

    suspend fun sync(
        accessToken: String,
        query: String,
        maxMessages: Int = 300,
    ): GmailSyncSummary = withContext(Dispatchers.IO) {
        var inspected = 0
        var imported = 0
        var duplicates = 0
        var ignored = 0
        var errors = 0
        var pageToken: String? = null
        var pages = 0
        val parserRules = repository.enabledParserRules()

        while (inspected < maxMessages && pages < MAX_PAGES_PER_SYNC) {
            pages++
            val listUrl = buildString {
                append("$BASE_URL/messages?maxResults=100&q=")
                append(URLEncoder.encode(query, StandardCharsets.UTF_8.name()))
                pageToken?.let {
                    append("&pageToken=")
                    append(URLEncoder.encode(it, StandardCharsets.UTF_8.name()))
                }
            }
            val response = getJson(listUrl, accessToken)
            val messages = response.optJSONArray("messages") ?: break
            for (index in 0 until messages.length()) {
                if (inspected >= maxMessages) break
                val id = messages.getJSONObject(index).getString("id")
                val externalId = "gmail:$id"
                if (repository.isProcessed(SourceType.EMAIL, externalId)) {
                    continue
                }
                inspected++
                try {
                    when (processMessage(id, accessToken, parserRules)) {
                        IngestionOutcome.IMPORTED -> imported++
                        IngestionOutcome.DUPLICATE -> duplicates++
                        IngestionOutcome.REJECTED -> ignored++
                        IngestionOutcome.ERROR -> errors++
                    }
                } catch (authorization: GmailAuthorizationException) {
                    throw authorization
                } catch (network: IOException) {
                    throw network
                } catch (_: Exception) {
                    errors++
                }
            }
            pageToken = response.optString("nextPageToken").takeIf(String::isNotBlank)
            if (pageToken == null) break
        }

        GmailSyncSummary(inspected, imported, duplicates, ignored, errors)
    }

    private suspend fun processMessage(
        id: String,
        accessToken: String,
        parserRules: List<ParserRuleEntity>,
    ): IngestionOutcome {
        val message = getJson("$BASE_URL/messages/$id?format=full", accessToken)
        val payload = message.getJSONObject("payload")
        val headers = payload.optJSONArray("headers")
        var sender = "Gmail"
        var subject: String? = null
        if (headers != null) {
            for (index in 0 until headers.length()) {
                val header = headers.getJSONObject(index)
                when (header.optString("name").lowercase()) {
                    "from" -> sender = header.optString("value", "Gmail")
                    "subject" -> subject = header.optString("value")
                }
            }
        }
        val body = extractBody(payload)
        val timestamp = message.optString("internalDate").toLongOrNull()
            ?: System.currentTimeMillis()
        val externalId = "gmail:$id"
        return when (
            val parsed = parser.parse(
                ParseInput(
                    externalId = externalId,
                    source = SourceType.EMAIL,
                    sender = sender,
                    subject = subject,
                    body = body,
                    receivedAt = timestamp,
                ),
                rules = parserRules,
            )
        ) {
            is ParseResult.Parsed -> repository.import(parsed.candidate).outcome
            is ParseResult.Ignored -> {
                repository.recordIgnored(
                    source = SourceType.EMAIL,
                    externalId = externalId,
                    fingerprint = parsed.contentFingerprint,
                    reason = parsed.reason,
                )
                IngestionOutcome.REJECTED
            }
        }
    }

    private fun extractBody(payload: JSONObject): String {
        if (payload.optString("filename").isNotBlank()) return ""
        val mimeType = payload.optString("mimeType")
        val direct = payload.optJSONObject("body")?.optString("data").orEmpty()
        if (direct.isNotBlank() &&
            (mimeType.equals("text/plain", true) || mimeType.equals("text/html", true))
        ) {
            return decodeBody(direct)
        }
        val parts = payload.optJSONArray("parts") ?: return ""
        var htmlFallback = ""
        for (index in 0 until parts.length()) {
            val part = parts.getJSONObject(index)
            val mime = part.optString("mimeType")
            val nested = extractBody(part)
            if (nested.isBlank()) continue
            if (mime.equals("text/plain", ignoreCase = true)) return nested
            if (htmlFallback.isBlank()) htmlFallback = nested
        }
        return htmlFallback
    }

    private fun decodeBody(encoded: String): String = String(
        Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
        Charsets.UTF_8,
    )

    private fun getJson(url: String, accessToken: String): JSONObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED ||
                status == HttpURLConnection.HTTP_FORBIDDEN
            ) {
                throw GmailAuthorizationException()
            }
            if (status !in 200..299) {
                throw IOException("Gmail API returned HTTP $status")
            }
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"
        const val MAX_PAGES_PER_SYNC = 20
    }
}

class GmailAuthorizationException : IOException("Gmail authorization is required")

@Singleton
class GmailSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
) {
    fun ensureScheduled() {
        if (!preferences.settings.value.gmailConnected) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<GmailSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel() = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)

    private companion object {
        const val WORK_NAME = "gmail-transaction-sync"
    }
}

@HiltWorker
class GmailSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val preferences: AppPreferences,
    private val syncService: GmailSyncService,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val settings = preferences.settings.value
        if (!settings.gmailConnected) return Result.success()
        val requestBuilder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GMAIL_READONLY_SCOPE)))
        settings.gmailAccount?.let {
            requestBuilder.setAccount(Account(it, "com.google"))
        }
        return try {
            val authorization = Identity.getAuthorizationClient(applicationContext)
                .authorize(requestBuilder.build())
                .await()
            if (authorization.hasResolution() || authorization.accessToken.isNullOrBlank()) {
                preferences.recordStatus("Open My Expenses to reconnect Gmail")
                Result.failure()
            } else {
                syncService.syncWithToken(authorization.accessToken!!)
                Result.success()
            }
        } catch (_: GmailAuthorizationException) {
            preferences.recordStatus("Open My Expenses to reconnect Gmail")
            Result.failure()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            preferences.recordStatus("Background Gmail sync failed safely")
            Result.failure()
        }
    }
}
