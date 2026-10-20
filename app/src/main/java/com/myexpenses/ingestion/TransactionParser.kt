package com.myexpenses.ingestion

import com.myexpenses.data.model.SourceType
import com.myexpenses.data.model.TransactionStatus
import com.myexpenses.data.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class ParseInput(
    val externalId: String,
    val source: SourceType,
    val sender: String,
    val subject: String? = null,
    val body: String,
    val receivedAt: Long,
)

data class TransactionCandidate(
    val externalId: String,
    val source: SourceType,
    val amountMinor: Long,
    val type: TransactionType,
    val status: TransactionStatus,
    val merchant: String,
    val accountHint: String?,
    val categoryName: String,
    val occurredAt: Long,
    val reference: String?,
    val confidence: Float,
    val contentFingerprint: String,
)

sealed interface ParseResult {
    data class Parsed(val candidate: TransactionCandidate) : ParseResult
    data class Ignored(val reason: String, val contentFingerprint: String) : ParseResult
}

@Singleton
class TransactionParser @Inject constructor() {
    fun parse(input: ParseInput): ParseResult {
        val plainText = stripHtml(input.body)
        val combined = listOfNotNull(input.subject, plainText)
            .joinToString(" ")
            .replace(whitespace, " ")
            .trim()
        val fingerprint = Hashing.sha256(normalizeForFingerprint(combined))

        if (combined.isBlank()) return ParseResult.Ignored("Empty message", fingerprint)
        if (otpCode.containsMatchIn(combined) || otpInstruction.containsMatchIn(combined)) {
            return ParseResult.Ignored("Authentication or OTP message", fingerprint)
        }

        val amountMatch = amount.find(combined)
            ?: return ParseResult.Ignored("No rupee amount found", fingerprint)
        val amountMinor = amountMatch.groupValues[1].replace(",", "")
            .toBigDecimalOrNull()
            ?.movePointRight(2)
            ?.setScale(0, RoundingMode.HALF_UP)
            ?.longValueExactOrNull()
            ?: return ParseResult.Ignored("Invalid amount", fingerprint)

        val lower = combined.lowercase(Locale.ROOT)
        val status = when {
            failedWords.any(lower::contains) -> TransactionStatus.FAILED
            reversedWords.any(lower::contains) -> TransactionStatus.REVERSED
            pendingWords.any(lower::contains) -> TransactionStatus.PENDING
            else -> TransactionStatus.POSTED
        }
        val type = inferType(lower)
            ?: return ParseResult.Ignored("No posted transaction action found", fingerprint)
        val accountHint = account.find(combined)?.groupValues?.get(1)
        val reference = referencePatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(combined)?.groupValues?.get(1)?.take(32)
        }
        val merchant = inferMerchant(combined, input.sender, type)
        val category = inferCategory(merchant, lower, type)
        val confidence = (
            0.46f +
                (if (accountHint != null) 0.12f else 0f) +
                (if (reference != null) 0.12f else 0f) +
                (if (merchant != input.sender) 0.12f else 0f) +
                (if (status == TransactionStatus.POSTED) 0.08f else 0f)
            ).coerceAtMost(0.92f)

        return ParseResult.Parsed(
            TransactionCandidate(
                externalId = input.externalId,
                source = input.source,
                amountMinor = amountMinor,
                type = type,
                status = status,
                merchant = merchant.ifBlank { "Unknown transaction" },
                accountHint = accountHint,
                categoryName = category,
                occurredAt = input.receivedAt,
                reference = reference,
                confidence = confidence,
                contentFingerprint = fingerprint,
            ),
        )
    }

    private fun inferType(text: String): TransactionType? = when {
        text.contains("credit card") &&
            listOf("payment received", "payment towards", "card payment").any(text::contains) ->
            TransactionType.CARD_PAYMENT
        listOf("self transfer", "own account", "between your accounts").any(text::contains) ->
            TransactionType.TRANSFER
        refundWords.any(text::contains) -> TransactionType.REFUND
        creditWords.any(text::contains) -> TransactionType.INCOME
        debitWords.any(text::contains) -> TransactionType.EXPENSE
        else -> null
    }

    private fun inferMerchant(text: String, sender: String, type: TransactionType): String {
        val patterns = when (type) {
            TransactionType.INCOME, TransactionType.REFUND -> fromPatterns
            else -> toPatterns
        }
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.groupValues?.get(1)
                ?.trim(' ', '.', ',', '-', ':')
                ?.takeIf { it.length >= 2 }
                ?.take(48)
        } ?: sender.substringAfterLast('<').substringBefore('>').trim().take(48)
    }

    private fun inferCategory(
        merchant: String,
        content: String,
        type: TransactionType,
    ): String {
        if (type == TransactionType.INCOME) return "Income"
        if (type == TransactionType.REFUND) return "Refunds"
        if (type == TransactionType.TRANSFER) return "Transfers"
        if (type == TransactionType.CARD_PAYMENT) return "Card payment"
        val text = "$merchant $content".lowercase(Locale.ROOT)
        return categoryRules.firstOrNull { (_, words) -> words.any(text::contains) }?.first
            ?: "Other"
    }

    private fun stripHtml(value: String): String = value
        .replace(scriptTag, " ")
        .replace(styleTag, " ")
        .replace(htmlTag, " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun normalizeForFingerprint(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(whitespace, " ")
        .trim()

    private companion object {
        val whitespace = Regex("\\s+")
        val htmlTag = Regex("<[^>]+>", RegexOption.DOT_MATCHES_ALL)
        val scriptTag = Regex("<script.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val styleTag = Regex("<style.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val amount = Regex(
            "(?:₹|INR|Rs\\.?)(?:\\s|&nbsp;)*([0-9]+(?:,[0-9]{2,3})*(?:\\.[0-9]{1,2})?)",
            RegexOption.IGNORE_CASE,
        )
        val account = Regex(
            "(?:a/?c|acct|account|card|wallet)(?:\\s+(?:no\\.?|ending))?[\\s:*xX-]*([0-9]{3,4})\\b",
            RegexOption.IGNORE_CASE,
        )
        val referencePatterns = listOf(
            Regex("(?:ref(?:erence)?|txn|transaction|utr)[\\s:#-]*(?:no\\.?\\s*)?([A-Z0-9-]{6,})", RegexOption.IGNORE_CASE),
            Regex("\\bUPI[/:-]([A-Z0-9-]{6,})", RegexOption.IGNORE_CASE),
        )
        val toPatterns = listOf(
            Regex("(?:paid|sent|debited|spent)\\s+(?:to|at)\\s+([\\p{L}0-9 .&@_'/-]+?)(?=\\s+(?:on|via|using|from|ref|txn|upi|avl|available|$))", RegexOption.IGNORE_CASE),
            Regex("\\b(?:at|to|towards)\\s+([\\p{L}0-9 .&@_'/-]+?)(?=\\s+(?:on|via|using|from|ref|txn|upi|avl|available|$))", RegexOption.IGNORE_CASE),
            Regex("\\bmerchant[\\s:-]+([\\p{L}0-9 .&@_'/-]{2,48})", RegexOption.IGNORE_CASE),
        )
        val fromPatterns = listOf(
            Regex("\\bfrom\\s+([\\p{L}0-9 .&@_'/-]+?)(?=\\s+(?:on|via|ref|txn|upi|into|to|$))", RegexOption.IGNORE_CASE),
            Regex("\\bby\\s+([\\p{L}0-9 .&@_'/-]+?)(?=\\s+(?:on|via|ref|txn|upi|$))", RegexOption.IGNORE_CASE),
        )
        val otpCode = Regex("\\b(?:otp|one[ -]?time password)[\\s:#-]*(?:is\\s*)?[0-9]{4,8}\\b", RegexOption.IGNORE_CASE)
        val otpInstruction = Regex("\\b(?:use|enter|share)\\s+(?:this\\s+)?otp\\b", RegexOption.IGNORE_CASE)

        val failedWords = listOf("failed", "declined", "unsuccessful", "could not be processed")
        val reversedWords = listOf("reversed", "reversal", "has been reversed")
        val pendingWords = listOf("pending", "being processed", "processing")
        val refundWords = listOf("refund", "refunded", "cashback", "reversal credit")
        val creditWords = listOf("credited", "credit of", "received", "deposited", "salary")
        val debitWords = listOf(
            "debited",
            "debit of",
            "spent",
            "paid",
            "purchase",
            "withdrawn",
            "sent",
            "transaction of",
        )
        val categoryRules = listOf(
            "Food & dining" to listOf("swiggy", "zomato", "restaurant", "cafe", "food"),
            "Groceries" to listOf("blinkit", "bigbasket", "zepto", "grocery", "supermarket"),
            "Transport" to listOf("uber", "ola", "rapido", "metro", "fuel", "petrol"),
            "Shopping" to listOf("amazon", "flipkart", "myntra", "shopping", "retail"),
            "Bills & utilities" to listOf("electricity", "broadband", "airtel", "jio", "utility", "recharge"),
            "Housing" to listOf("rent", "maintenance", "housing"),
            "Health" to listOf("pharmacy", "hospital", "clinic", "medical", "apollo"),
            "Entertainment" to listOf("netflix", "spotify", "cinema", "bookmyshow"),
            "Education" to listOf("school", "college", "course", "udemy"),
        )
    }
}

object Hashing {
    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun BigDecimal.longValueExactOrNull(): Long? = try {
    longValueExact()
} catch (_: ArithmeticException) {
    null
}
