package com.ajamuar.ledger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.*

data class Pairing(val url: String, val pin: String, val token: String) {
    fun json(): String = JSONObject().put("url", url).put("pin", pin).put("token", token).toString()
    companion object {
        fun parse(text: String): Pairing {
            val obj = JSONObject(text)
            val url = URI(obj.getString("url").trim().removeSuffix("/"))
            require(url.scheme == "https" && !url.host.isNullOrBlank() && url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null && url.path.isNullOrEmpty()) {
                "Receiver must be an HTTPS origin, without a path or credentials"
            }
            val pin = obj.getString("pin").lowercase()
            val token = obj.getString("token")
            require(Regex("[a-f0-9]{64}").matches(pin) && Regex("[A-Za-z0-9_-]{32,128}").matches(token)) { "Invalid receiver identity or pairing token" }
            return Pairing(url.toString(), pin, token)
        }
    }
}

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = syncLock.withLock { withContext(Dispatchers.IO) {
        val repo = (applicationContext as LedgerApplication).repository
        val settings = applicationContext.getSharedPreferences("ledger", Context.MODE_PRIVATE)
        val forced = inputData.getBoolean("force", false)
        if (!forced && settings.getBoolean("syncPaused", false)) return@withContext Result.success()
        val bytes = try { repo.vault.read("pairing") } catch (_: Exception) {
            repo.syncState("Pairing cannot be unlocked. Pair this computer again."); return@withContext Result.failure()
        } ?: return@withContext Result.success()
        try {
            val pairing = Pairing.parse(bytes.toString(Charsets.UTF_8))
            val ledgerId = repo.identity()
            // A pinned certificate is the receiver identity, including for a local self-signed certificate.
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, type: String) { throw java.security.cert.CertificateException("Not a client trust store") }
                override fun checkServerTrusted(chain: Array<X509Certificate>, type: String) {
                    require(chain.isNotEmpty())
                    chain.first().checkValidity()
                    val actual = MessageDigest.getInstance("SHA-256").digest(chain.first().encoded)
                    val expected = pairing.pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    if (!MessageDigest.isEqual(actual, expected)) throw java.security.cert.CertificateException("Receiver identity changed")
                }
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            repeat(10) {
                if (!forced && settings.getBoolean("syncPaused", false)) return@withContext Result.success()
                val pending = repo.dao.pending()
                if (pending.isEmpty()) {
                    repo.syncState("Up to date · ${Instant.now()}")
                    return@withContext Result.success()
                }
                repo.syncState("Sending ${pending.size} queued changes…")
                val payload = JSONObject().put("schema", 1).put("ledger_id", ledgerId)
                    .put("entries", JSONArray().apply { pending.forEach { put(JSONObject(it.payload)) } })
                    .toString().toByteArray(Charsets.UTF_8)
                val connection = URI("${pairing.url}/v1/sync").toURL().openConnection() as HttpsURLConnection
                try {
                    connection.sslSocketFactory = ssl.socketFactory
                    connection.hostnameVerifier = HostnameVerifier { host, _ -> host == URI(pairing.url).host }
                    connection.connectTimeout = 10_000; connection.readTimeout = 20_000
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer ${pairing.token}")
                    connection.setFixedLengthStreamingMode(payload.size)
                    connection.outputStream.use { it.write(payload) }
                    val code = connection.responseCode
                    if (code == 401) { repo.syncState("Pairing revoked or expired. Pair again."); return@withContext Result.failure() }
                    if (code == 422) { repo.syncState("Receiver rejected a change. Check account mappings and receiver schema."); return@withContext Result.failure() }
                    check(code == 200) { "Receiver unavailable" }
                    val responseBytes = connection.inputStream.use { it.readLimited(1_000_000) }
                    val response = JSONObject(responseBytes.toString(Charsets.UTF_8))
                    require(response.getString("ledger_id") == ledgerId && response.getBoolean("materialized"))
                    val latest = response.getJSONObject("latest")
                    if (pending.any { latest.getLong(it.entryId) > it.revision }) {
                        repo.syncState("Computer has newer revisions. Resolve the older backup or other phone before syncing.")
                        return@withContext Result.failure()
                    }
                    require(pending.all { latest.getLong(it.entryId) == it.revision }) { "Invalid revision acknowledgement" }
                    val ack = response.getJSONArray("ack")
                    val expected = pending.map { "${it.entryId}:${it.revision}" }.toSet()
                    val received = (0 until ack.length()).map { ack.getString(it) }.toSet()
                    require(received == expected) { "Incomplete acknowledgement" }
                    repo.db.withTransaction {
                        pending.forEach { repo.dao.acknowledge(it.entryId, it.revision) }
                    }
                } finally { connection.disconnect() }
            }
            repo.syncState("More changes queued. Continuing shortly.")
            Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (_: SSLException) {
            repo.syncState("Cannot verify receiver identity. Check its certificate and pair again.")
            Result.failure()
        } catch (_: Exception) {
            repo.syncState("Computer unreachable or sync incomplete. Changes are safe on this phone; retry when connected.")
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    } }
    private companion object { val syncLock = Mutex() }
}

fun java.io.InputStream.readLimited(limit: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count == -1) break
        require(result.size() + count <= limit) { "File or response is too large" }
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}
