package com.myexpenses.ingestion

import com.myexpenses.data.model.SourceType
import com.myexpenses.data.model.ParserRuleEntity
import com.myexpenses.data.model.TransactionStatus
import com.myexpenses.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionParserTest {
    private val parser = TransactionParser()

    @Test
    fun parsesIndianUpiDebit() {
        val result = parser.parse(
            input(
                sender = "VM-HDFCBK",
                body = "Rs.1,249.50 debited from a/c XX4821 and paid to SWIGGY on 20-10. " +
                    "UPI Ref 435678901234.",
            ),
        )

        assertTrue(result is ParseResult.Parsed)
        val candidate = (result as ParseResult.Parsed).candidate
        assertEquals(124_950L, candidate.amountMinor)
        assertEquals(TransactionType.EXPENSE, candidate.type)
        assertEquals(TransactionStatus.POSTED, candidate.status)
        assertEquals("4821", candidate.accountHint)
        assertEquals("Food & dining", candidate.categoryName)
        assertTrue(candidate.merchant.contains("SWIGGY", ignoreCase = true))
    }

    @Test
    fun parsesSalaryCredit() {
        val result = parser.parse(
            input(
                sender = "AD-ICICIB",
                body = "INR 85,000.00 credited to account ending 9912 from ACME SERVICES " +
                    "on 20-Oct. Ref SAL123456.",
            ),
        ) as ParseResult.Parsed

        assertEquals(TransactionType.INCOME, result.candidate.type)
        assertEquals(8_500_000L, result.candidate.amountMinor)
        assertEquals("Income", result.candidate.categoryName)
    }

    @Test
    fun rejectsOtpEvenWhenItContainsAmount() {
        val result = parser.parse(
            input(
                sender = "VM-BANK",
                body = "OTP 348921 is for transaction of INR 4,000.00. Do not share this OTP.",
            ),
        )

        assertTrue(result is ParseResult.Ignored)
        assertEquals("Authentication or OTP message", (result as ParseResult.Ignored).reason)
    }

    @Test
    fun marksReversedTransactionExcludedByStatus() {
        val result = parser.parse(
            input(
                sender = "VK-AXISBK",
                body = "Transaction of Rs 499.00 at METRO MART on card XX1234 has been reversed.",
            ),
        ) as ParseResult.Parsed

        assertEquals(TransactionStatus.REVERSED, result.candidate.status)
        assertEquals(TransactionType.EXPENSE, result.candidate.type)
    }

    @Test
    fun parsesHtmlEmailWithoutRetainingMarkup() {
        val result = parser.parse(
            ParseInput(
                externalId = "gmail:synthetic",
                source = SourceType.EMAIL,
                sender = "alerts@examplebank.in",
                subject = "Card transaction alert",
                body = "<html><style>.x{color:red}</style><body><b>INR 799.00</b> " +
                    "spent at NETFLIX on card ending 7788.</body></html>",
                receivedAt = 1_700_000_000_000,
            ),
        ) as ParseResult.Parsed

        assertEquals(79_900L, result.candidate.amountMinor)
        assertEquals("Entertainment", result.candidate.categoryName)
        assertEquals(SourceType.EMAIL, result.candidate.source)
    }

    @Test
    fun returnsStableFingerprintForSameNormalizedMessage() {
        val first = parser.parse(input(body = "Rs 100 debited from a/c 1234 at CAFE"))
        val second = parser.parse(input(body = "  rs 100   DEBITED from a/c 1234 at cafe  "))

        val firstHash = (first as ParseResult.Parsed).candidate.contentFingerprint
        val secondHash = (second as ParseResult.Parsed).candidate.contentFingerprint
        assertEquals(firstHash, secondHash)
    }

    @Test
    fun producesSameReferenceHashForSmsAndEmailDuplicate() {
        val sms = parser.parse(
            input(body = "Rs 349 debited from a/c 4821 at TEST CAFE. UPI Ref 435678901234"),
        ) as ParseResult.Parsed
        val email = parser.parse(
            ParseInput(
                externalId = "gmail:duplicate",
                source = SourceType.EMAIL,
                sender = "alerts@examplebank.in",
                subject = "Transaction alert",
                body = "INR 349.00 spent at TEST CAFE using account 4821. UPI Ref 435678901234",
                receivedAt = 1_700_000_300_000,
            ),
        ) as ParseResult.Parsed

        assertEquals(sms.candidate.referenceHash, email.candidate.referenceHash)
    }

    @Test
    fun parsesOwnAccountTransferExplicitly() {
        val result = parser.parse(
            input(
                body = "INR 5,000.00 sent from a/c 1234 to your own account 9876. " +
                    "Txn SELF123456.",
            ),
        ) as ParseResult.Parsed

        assertEquals(TransactionType.TRANSFER, result.candidate.type)
        assertEquals("Transfers", result.candidate.categoryName)
    }

    @Test
    fun marksFailedDebitAsExcludedStatus() {
        val result = parser.parse(
            input(
                body = "INR 850.00 debited from card XX1234 at TEST STORE but transaction failed. " +
                    "Ref FAIL123456.",
            ),
        ) as ParseResult.Parsed

        assertEquals(TransactionType.EXPENSE, result.candidate.type)
        assertEquals(TransactionStatus.FAILED, result.candidate.status)
    }

    @Test
    fun rejectsMalformedTransactionContent() {
        val result = parser.parse(
            input(body = "Your account was debited, but this alert has no amount."),
        )

        assertTrue(result is ParseResult.Ignored)
        assertEquals("No rupee amount found", (result as ParseResult.Ignored).reason)
    }

    @Test
    fun persistedRuleCanOverrideMerchantAndCategory() {
        val result = parser.parse(
            input(body = "INR 799.00 spent at STREAMCO on card XX7788."),
            rules = listOf(
                ParserRuleEntity(
                    id = "custom-streaming",
                    name = "Streaming subscription",
                    bodyRegex = """(?i)\bSTREAMCO\b""",
                    merchantRegex = """(?i)spent\s+at\s+([A-Z]+)""",
                    categoryName = "Education",
                    priority = 1,
                ),
            ),
        ) as ParseResult.Parsed

        assertEquals("STREAMCO", result.candidate.merchant)
        assertEquals("Education", result.candidate.categoryName)
    }

    @Test
    fun keywordFilterRejectsPersonalAndOtpMessages() {
        assertTrue(
            TransactionMessageFilter.looksTransactional(
                "Rs 250.00 debited from a/c 1234 at TEST CAFE",
            ),
        )
        assertTrue(!TransactionMessageFilter.looksTransactional("Dinner split is Rs 250.00"))
        assertTrue(
            !TransactionMessageFilter.looksTransactional(
                "OTP 123456 is for INR 250.00 payment",
            ),
        )
    }

    private fun input(
        sender: String = "VM-BANKAL",
        body: String,
    ) = ParseInput(
        externalId = "sms:test-${body.hashCode()}",
        source = SourceType.SMS,
        sender = sender,
        body = body,
        receivedAt = 1_700_000_000_000,
    )
}
