package com.ajamuar.ledger

import androidx.room.withTransaction
import org.json.JSONObject
import java.util.Locale

object SmsAccounts {
    // shortcut: only these sender headers are supported; add verified bank templates before extending coverage.
    private val institutions = mapOf(
        "HDFCBK" to "HDFC Bank", "HDFCBN" to "HDFC Bank", "ICICIB" to "ICICI Bank",
        "SBIINB" to "SBI", "SBIPSG" to "SBI", "SBICRD" to "SBI Card",
        "AXISBK" to "Axis Bank", "AXISBN" to "Axis Bank", "KOTAKB" to "Kotak Bank",
        "KOTAKM" to "Kotak Bank", "IDFCFB" to "IDFC FIRST Bank", "IDFCBK" to "IDFC FIRST Bank",
        "INDUSB" to "IndusInd Bank", "YESBNK" to "YES Bank", "PNBSMS" to "PNB",
        "BOBTXN" to "Bank of Baroda", "CANBNK" to "Canara Bank", "UNIONB" to "Union Bank",
        "FEDBNK" to "Federal Bank", "RBLBNK" to "RBL Bank", "AUBANK" to "AU Bank"
    )
    fun institution(sender: String): String? {
        val header = sender.uppercase(Locale.ROOT).replace(Regex("^[A-Z]{2}-"), "").replace(Regex("-[A-Z]$"), "")
        return institutions[header]
    }
    fun id(sender: String, candidate: SmsCandidate): String? = candidate.accountHint?.let {
        "sms-${digest("${institution(sender) ?: return null}|$it|${candidate.card}").take(24)}"
    }
    fun key(sender: String, candidate: SmsCandidate, fallback: String): String = candidate.reference?.let {
        digest("${id(sender, candidate) ?: institution(sender)}|${candidate.kind}|$it|${candidate.amount}|${candidate.direction}")
    } ?: fallback
}

data class HeldSms(val key: String, val sender: String, val time: Long, val candidate: SmsCandidate) {
    fun json(): String = JSONObject().put("sender", sender).put("time", time).put("amount", candidate.amount)
        .put("kind", candidate.kind.name).put("status", candidate.status.name).put("merchant", candidate.merchant)
        .put("card", candidate.card).put("direction", candidate.direction).toString()
    companion object {
        fun read(value: Metadata): HeldSms? {
            if (!value.key.startsWith("sms-review:") || value.value == "ignored") return null
            val o = JSONObject(value.value)
            return HeldSms(value.key.removePrefix("sms-review:"), o.getString("sender"), o.getLong("time"),
                SmsCandidate(o.getLong("amount"), Kind.valueOf(o.getString("kind")), Status.valueOf(o.getString("status")),
                    o.getString("merchant"), null, o.getBoolean("card"), null, o.getString("direction")))
        }
    }
}

suspend fun Repository.resolveSmsAccount(id: String): String {
    var resolved = id
    val seen = mutableSetOf<String>()
    while (true) {
        check(seen.add(resolved)) { "Account alias cycle" }
        resolved = dao.metadata("account-alias:$resolved") ?: return resolved
    }
}

suspend fun Repository.discoverAccount(sender: String, candidate: SmsCandidate): Account? {
    val canonical = SmsAccounts.id(sender, candidate) ?: return null
    var resolved = resolveSmsAccount(canonical)
    if (dao.account(resolved) == null) {
        // Preserve pre-selection account IDs and their source fingerprints on upgrades.
        val legacyId = "sms-${digest("${sender.uppercase()}|${candidate.accountHint}|${candidate.card}").take(24)}"
        val legacy = dao.accountSnapshot().filter { old ->
            val parts = old.name.split(" ")
            old.id == legacyId || (old.id.startsWith("sms-") && parts.size == 2 && parts[1] == candidate.accountHint &&
                SmsAccounts.institution(parts[0]) == SmsAccounts.institution(sender) && (old.type == "Card") == candidate.card)
        }.map { resolveSmsAccount(it.id) }.distinct()
        if (legacy.size > 1) return null
        if (legacy.size == 1) {
            resolved = legacy.single()
            dao.putMetadata(Metadata("account-alias:$canonical", resolved))
        } else dao.insertAccount(Account(canonical, "${SmsAccounts.institution(sender)} ••${candidate.accountHint}", if (candidate.card) "Card" else "Bank"))
    }
    return dao.account(resolved)
}

suspend fun Repository.holdSms(sender: String, candidate: SmsCandidate, time: Long, fallback: String) {
    val key = SmsAccounts.key(sender, candidate, fallback)
    if (dao.source(key) != null) return
    val previous = dao.metadata("sms-review:$key")
    if (previous == "ignored") return
    val old = previous?.let { HeldSms.read(Metadata("sms-review:$key", it)) }?.candidate?.status
    if (old == null || (old == Status.PENDING && candidate.status != Status.PENDING) ||
        (old == Status.POSTED && candidate.status == Status.REVERSED))
        dao.putMetadata(Metadata("sms-review:$key", HeldSms(key, sender, time, candidate).json()))
}

suspend fun Repository.setSmsTracking(accountId: String, enabled: Boolean) = db.withTransaction {
    val account = requireNotNull(dao.account(accountId))
    require(!enabled || (!account.archived && account.type in listOf("Bank", "Card"))) { "Choose an active bank account or card" }
    dao.putMetadata(Metadata("sms-track:$accountId", enabled.toString()))
}

suspend fun Repository.insertSms(sender: String, candidate: SmsCandidate, time: Long, fallback: String, accountId: String): Boolean {
    val sourceKey = SmsAccounts.key(sender, candidate, fallback)
    val oldIds = dao.aliases().filter { resolveSmsAccount(it.value) == accountId }.map { it.key.removePrefix("account-alias:") } +
        "sms-${digest("${sender.uppercase()}|${candidate.accountHint}|${candidate.card}").take(24)}" + accountId
    val legacyKeys = candidate.reference?.let { ref -> oldIds.flatMap { id -> listOf(
        digest("$id|${candidate.kind}|$ref|${candidate.amount}|${candidate.direction}"), digest("$id|${candidate.kind}|$ref|${candidate.amount}")) } } ?: emptyList()
    val prior = dao.source(sourceKey) ?: legacyKeys.firstNotNullOfOrNull { dao.source(it) }
    if (prior != null) {
        val advances = !prior.deleted && ((prior.status == Status.PENDING.name && candidate.status in listOf(Status.POSTED, Status.REVERSED, Status.FAILED)) ||
            (prior.status == Status.POSTED.name && candidate.status == Status.REVERSED))
        if (advances) save(prior.copy(status = candidate.status.name, reviewed = false))
        return advances
    }
    save(Entry(amount = candidate.amount, kind = candidate.kind.name, status = candidate.status.name,
        accountId = accountId, merchant = candidate.merchant,
        category = dao.rule(candidate.merchant.lowercase())?.category ?: "Uncategorised", time = time,
        source = "sms", sourceKey = sourceKey, reviewed = false, direction = candidate.direction))
    return true
}

suspend fun Repository.reviewSms(key: String, accountId: String?) {
    db.withTransaction {
        val value = requireNotNull(dao.metadata("sms-review:$key")) { "Alert already handled" }
        val alert = requireNotNull(HeldSms.read(Metadata("sms-review:$key", value))) { "Alert already handled" }
        if (accountId != null) {
            require(dao.account(accountId)?.archived == false) { "Choose an active account" }
            // Explicit review binds this structured alert only, never future unidentified alerts.
            if (dao.source(key) == null) save(Entry(amount = alert.candidate.amount, merchant = alert.candidate.merchant,
                kind = alert.candidate.kind.name, status = alert.candidate.status.name, direction = alert.candidate.direction,
                accountId = accountId, time = alert.time, source = "sms", sourceKey = key, reviewed = true))
        }
        dao.putMetadata(Metadata("sms-review:$key", "ignored"))
    }
}
