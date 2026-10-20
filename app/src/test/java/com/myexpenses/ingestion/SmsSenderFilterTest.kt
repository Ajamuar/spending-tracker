package com.myexpenses.ingestion

import com.myexpenses.ingestion.sms.SmsScanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSenderFilterTest {
    @Test
    fun acceptsTypicalIndianTransactionalSenders() {
        assertTrue(SmsScanner.looksTransactionalSender("VM-HDFCBK"))
        assertTrue(SmsScanner.looksTransactionalSender("AD-ICICIB"))
        assertTrue(SmsScanner.looksTransactionalSender("BANKALERT"))
    }

    @Test
    fun rejectsPhoneNumbersAndBlankSenders() {
        assertFalse(SmsScanner.looksTransactionalSender("+919876543210"))
        assertFalse(SmsScanner.looksTransactionalSender("9876543210"))
        assertFalse(SmsScanner.looksTransactionalSender(" "))
    }
}
