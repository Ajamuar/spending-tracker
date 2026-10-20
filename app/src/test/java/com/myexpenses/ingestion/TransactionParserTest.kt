package com.myexpenses.ingestion

import com.myexpenses.data.model.SourceType
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
