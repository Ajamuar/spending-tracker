package com.ajamuar.ledger

import org.junit.Assert.*
import org.junit.Test

class LedgerTest {
    @Test fun moneyIsExactAndValidatesGrouping() {
        assertEquals(12345678L, Money.parse("₹1,23,456.78"))
        assertEquals(100L, Money.parse("1"))
        assertEquals("₹1,23,456.78", Money.text(12345678))
        listOf("0", "-1", "NaN", "1.001", "12,34", "999999999999999999999").forEach {
            assertTrue(it, runCatching { Money.parse(it) }.isFailure)
        }
    }
    @Test fun balancesRespectDirectionAndCardLiabilitySign() {
        val account = Account("test", "Synthetic bank", openingMinor = 10000, openingAt = 0)
        val outgoing = Entry(amount = 2000, merchant = "TEST", accountId = "test", time = 100)
        val incoming = Entry(amount = 500, kind = Kind.TRANSFER.name, merchant = "TEST", accountId = "test", time = 200, direction = "in")
        assertEquals(8500L, account.calculatedBalance(listOf(outgoing, incoming)))
        assertEquals(11500L, account.copy(type = "Card").calculatedBalance(listOf(outgoing, incoming)))
        assertEquals(8000L, account.calculatedBalance(listOf(outgoing, incoming), 150))
        assertNull(account.copy(openingMinor = null).calculatedBalance(listOf(outgoing)))
    }
    @Test fun transfersAndUnpostedAlertsDoNotCountAsSpending() {
        val purchase = Entry(amount = 50000, merchant = "TEST CAFE")
        assertEquals(50000L, purchase.personalSpend())
        listOf(Kind.TRANSFER, Kind.CARD_PAYMENT, Kind.INVESTMENT, Kind.REIMBURSEMENT).forEach {
            assertEquals(0L, purchase.copy(kind = it.name).personalSpend())
        }
        Status.entries.filter { it != Status.POSTED }.forEach {
            assertEquals(0L, purchase.copy(status = it.name).personalSpend())
        }
        assertEquals(-50000L, purchase.copy(kind = Kind.REFUND.name).personalSpend())
        assertEquals(0L, purchase.copy(excluded = true).personalSpend())
    }
    @Test fun parsesSyntheticBankAlertsAndRejectsAuthentication() {
        val result = SmsParser.parse("VM-TESTBK", "Rs.249.50 debited from a/c XX4821 to TEST CAFE on 22-Oct. Ref 123456789012")!!
        assertEquals(24950L, result.amount)
        assertEquals("4821", result.accountHint)
        assertEquals("TEST CAFE", result.merchant)
        assertEquals("123456789012", result.reference)
        assertNull(SmsParser.parse("VM-TESTBK", "OTP 123456 for INR 500 purchase"))
        assertNull(SmsParser.parse("9999999999", "INR 500 paid to TEST"))
        assertNull(SmsParser.parse("VM-TESTBK", "INR 500 payment due tomorrow"))
        assertNull(SmsParser.parse("VM-TESTBK", "INR 1,00,00 payment debited"))
    }
}
