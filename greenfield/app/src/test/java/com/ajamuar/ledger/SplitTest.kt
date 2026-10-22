package com.ajamuar.ledger

import org.junit.Assert.*
import org.junit.Test

class SplitTest {
    @Test fun equalSplitAllocatesEveryPaise() {
        assertEquals(listOf(34L, 33L, 33L), SplitMath.equal(100, 3))
        assertEquals(100L, SplitMath.equal(100, 7).sum())
    }
    @Test fun reimbursementSettlesDebtWithoutChangingPersonalShare() {
        val expense = Split(groupId = "synthetic", description = "TEST MEAL", payer = "You", shares = "", amount = 10000)
        val settlement = Split(groupId = "synthetic", description = "TEST SETTLEMENT", payer = "Other", shares = "", amount = 5000, settlementTo = "You")
        val balances = SplitMath.balances(listOf("You", "Other"), listOf(expense to mapOf("You" to 5000L, "Other" to 5000L), settlement to emptyMap()))
        assertEquals(mapOf("You" to 0L, "Other" to 0L), balances)
        assertEquals(5000L, Entry(amount = 10000, merchant = "TEST", personalMinor = 5000).personalSpend())
        assertEquals(0L, Entry(amount = 10000, merchant = "TEST", personalMinor = 0).personalSpend())
    }
}
