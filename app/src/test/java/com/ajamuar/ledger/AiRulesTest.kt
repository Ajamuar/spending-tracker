package com.ajamuar.ledger

import org.junit.Assert.*
import org.junit.Test

class AiRulesTest {
    private val body = "Avl balance INR 9000.00. INR 249.50 dr from a/c XX4821 to SYNTHETIC CAFE. Ref ABC123456"
    private fun pattern() = SmsPattern("HDFCBK", "dr", "debit", 1, "to", digest(AiPrivacy.shape(body)))

    @Test fun amountAllowsSentencePunctuationButRejectsExtraDecimals() {
        assertEquals(24950L, SmsParser.parse("VM-HDFCBK", "INR 249.50. debited from a/c XX4821 to TEST STORE")!!.amount)
        assertNull(SmsParser.parse("VM-HDFCBK", "INR 249.501 debited from a/c XX4821 to TEST STORE"))
    }

    @Test fun redactionSharesStructureWithoutIdentifiersOrMerchantNames() {
        val shape = AiPrivacy.shape(body + " Contact synthetic@example.test or user@upi")
        listOf("9000", "249", "4821", "SYNTHETIC", "CAFE", "ABC123456", "synthetic", "example", "user", "upi").forEach { assertFalse(it, shape.contains(it)) }
        assertTrue(shape.contains("balance inr"))
        assertEquals("test cafe [identifier] [identifier]", AiPrivacy.merchant("test cafe 9988776655 user@upi"))
    }

    @Test fun selectedFormatUsesTransactionAmountAndGeneralisesLocally() {
        assertNull(SmsParser.parse("VM-HDFCBK", body))
        val choices = smsChoices("VM-HDFCBK", body)
        assertTrue(choices.any { it.pattern == pattern() })
        val parsed = SmsParser.parse("AD-HDFCBK-S", body, listOf(pattern()))!!
        assertEquals(24950L, parsed.amount)
        assertEquals("4821", parsed.accountHint)
        assertEquals("SYNTHETIC CAFE", parsed.merchant)
        val next = body.replace("249.50", "300.00").replace("SYNTHETIC CAFE", "ANOTHER STORE").replace("ABC123456", "DEF654321")
        assertEquals(30000L, SmsParser.parse("VM-HDFCBK", next, listOf(pattern()))!!.amount)
    }

    @Test fun formatCannotOverrideOtherStructuresBanksOrAuthentication() {
        assertFalse(pattern().matches("VM-ICICIB", body))
        assertFalse(pattern().matches("VM-HDFCBK", body + " refunded"))
        assertNull(SmsParser.parse("VM-HDFCBK", body + " OTP 123456", listOf(pattern())))
        assertNull(SmsParser.parse("VM-SHOPXX", body, listOf(pattern())))
        assertNull(SmsParser.parse("VM-HDFCBK", "INR 249.50 not yet debited from a/c XX4821 to TEST STORE", listOf(pattern())))
        assertNull(SmsParser.parse("VM-HDFCBK", body + " offer eligible", listOf(pattern())))
    }

    @Test fun conflictingOrUnboundedPatternsAreRejected() {
        assertNull(SmsParser.parse("VM-HDFCBK", body, listOf(pattern(), pattern().copy(amountIndex = 0))))
        listOf(pattern().copy(trigger = ".*"), pattern().copy(amountIndex = 10), pattern().copy(marker = "(.*)"), pattern().copy(formatHash = "raw-body")).forEach {
            assertTrue(runCatching { it.validate() }.isFailure)
        }
    }
}
