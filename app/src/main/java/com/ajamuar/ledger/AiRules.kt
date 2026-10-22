package com.ajamuar.ledger

import java.util.Locale

// shortcut: learned formats select bounded parsing options; new field syntaxes still need parser support.
data class SmsPattern(val header: String, val trigger: String, val event: String, val amountIndex: Int, val marker: String, val formatHash: String) {
    val key get() = "sms-pattern:${digest("$header|$trigger|$formatHash")}"
    fun validate() {
        require(Regex("[a-f0-9]{64}").matches(formatHash))
        require(SmsAccounts.institution(header) != null && header == SmsAccounts.header(header))
        require(trigger in triggers.getValue(event) && amountIndex in 0..2 && marker in listOf("to", "at", "from"))
    }
    fun matches(sender: String, body: String): Boolean = header == SmsAccounts.header(sender) && formatHash == digest(AiPrivacy.shape(body)) &&
        Regex("\\b${Regex.escape(trigger)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)
    val kind get() = when (event) {
        "credit" -> Kind.INCOME
        "refund" -> Kind.REFUND
        "card_payment" -> Kind.CARD_PAYMENT
        else -> Kind.EXPENSE
    }
    val direction get() = if (event in listOf("credit", "refund", "card_payment")) "in" else "out"
    companion object {
        val triggers = mapOf(
            "debit" to listOf("debited", "debit", "dr", "spent", "paid", "withdrawn", "purchase"),
            "credit" to listOf("credited", "credit", "cr", "received", "deposited"),
            "refund" to listOf("refund", "refunded"),
            "card_payment" to listOf("payment received")
        )
    }
}

data class AiChoice(val id: String, val pattern: SmsPattern, val candidate: SmsCandidate)
data class AiSample(val id: String, val header: String, val shape: String, val choices: List<AiChoice>)
data class AiMerchant(val id: String, val merchant: String, val sharedName: String)
data class AiBatch(val samples: List<AiSample>, val merchants: List<AiMerchant>) {
    val empty get() = samples.isEmpty() && merchants.isEmpty()
}
data class AiSuggestions(val patterns: List<AiChoice>, val categories: List<Pair<AiMerchant, String>>, val inputTokens: Int, val outputTokens: Int)

object AiPrivacy {
    private val words = ("inr rs a c ac acct account card no ending in on at from to ref reference utr txn transaction avl available balance " +
        "debited debit dr spent paid withdrawn purchase credited credit cr received deposited refund refunded payment towards using via " +
        "pending processing failed declined unsuccessful reversed reversal self transfer own bank credit card is has been was your for of not never yet").split(" ").toSet()
    private val tokens = Regex("[\\p{L}\\p{N}_]+|₹|[^\\s\\p{L}\\p{N}_]")
    private val unknownRuns = Regex("(?:\\[text]\\s*){2,}")
    fun shape(body: String): String = tokens.findAll(body).joinToString(" ") {
        val token = it.value.lowercase(Locale.ROOT)
        when {
            token in words || token == "₹" -> token
            token.any(Char::isDigit) -> "[number]"
            token.any(Char::isLetter) -> "[text]"
            else -> token
        }
    }.replace(unknownRuns, "[text] ").trim()
    fun merchant(value: String): String = value
        .replace(Regex("https?://\\S+|[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+", RegexOption.IGNORE_CASE), "[identifier]")
        .replace(Regex("[A-Za-z0-9_-]*[0-9][A-Za-z0-9_-]*"), "[identifier]")
        .take(120)
}

fun smsChoices(sender: String, body: String): List<AiChoice> {
    if (!SmsParser.eligible(sender, body) || body.length > 2000) return emptyList()
    val baseline = SmsParser.parse(sender, body)
    val header = SmsAccounts.header(sender)
    val shape = AiPrivacy.shape(body)
    if (shape.length > 4000) return emptyList()
    val formatHash = digest(shape)
    val result = mutableListOf<AiChoice>()
    SmsPattern.triggers.forEach { (event, triggers) -> triggers.filter { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(body) }.forEach { trigger ->
        for (index in 0..2) for (marker in listOf("to", "at", "from")) {
            val pattern = SmsPattern(header, trigger, event, index, marker, formatHash)
            if (pattern.matches(sender, body)) SmsParser.parse(sender, body, listOf(pattern))?.takeIf { it.accountHint != null && (baseline == null || (it.kind == baseline.kind && it.direction == baseline.direction)) }?.let {
                // Don't propose an account transfer as spending or an ordinary credit as card repayment.
                if (!(event == "debit" && Regex("\\b(?:self transfer|own account)\\b", RegexOption.IGNORE_CASE).containsMatchIn(body)))
                    result += AiChoice("c${result.size}", pattern, it)
            }
        }
    } }
    return result
}
