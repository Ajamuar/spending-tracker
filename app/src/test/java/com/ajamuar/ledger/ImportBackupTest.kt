package com.ajamuar.ledger

import org.junit.Assert.*
import org.junit.Test

class ImportBackupTest {
    @Test fun csvRoundTripsNewlinesQuotesAndEmptyCells() {
        val rows = listOf(listOf("date", "description", "amount"), listOf("2026-01-01", "TEST \"CAFE\"\nsecond line", "249.50"), listOf("", "", ""))
        assertEquals(rows.take(2), Csv.read(Csv.write(rows)))
        assertTrue(runCatching { Csv.read("a,b\n\"unclosed,b") }.isFailure)
    }
    @Test fun importKeepsIdenticalPaymentsDistinctButReplayKeysAreStable() {
        val rows = listOf(listOf("amount", "date", "description"), listOf("249.50", "2026-01-01", "TEST CAFE"), listOf("249.50", "2026-01-01", "TEST CAFE"))
        val mapping = CsvMapping(0, 1, 2)
        val first = LedgerImport.prepare(rows, mapping, "cash")
        val second = LedgerImport.prepare(rows, mapping, "cash")
        assertNotEquals(first[0].sourceKey, first[1].sourceKey)
        assertEquals(first.map { it.sourceKey }, second.map { it.sourceKey })
        assertTrue(runCatching { LedgerImport.prepare(rows + listOf(listOf("oops", "2026-01-01", "TEST")), mapping, "cash") }.isFailure)
    }
    @Test fun ownExportImportPreservesNonPostedStatusAndExclusion() {
        val rows = listOf(listOf("amount", "date", "description", "status", "excluded", "note", "tags"), listOf("12", "2026-01-01", "TEST", "FAILED", "true", "A note", "test"))
        val result = LedgerImport.prepare(rows, CsvMapping(0, 1, 2), "cash").single()
        assertEquals(Status.FAILED.name, result.status)
        assertTrue(result.excluded)
        assertEquals("A note", result.note)
    }
    @Test fun encryptedBackupRejectsWrongPasswordAndTampering() {
        val plain = "synthetic ledger only".toByteArray()
        val password = "synthetic-test-password".toCharArray()
        val encrypted = Backup.encrypt(plain, password)
        assertArrayEquals(plain, Backup.decrypt(encrypted, password))
        assertTrue(runCatching { Backup.decrypt(encrypted, "wrong password".toCharArray()) }.isFailure)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertTrue(runCatching { Backup.decrypt(encrypted, password) }.isFailure)
    }
    @Test fun impossibleDatesAreRejectedAndEscapedTextIsRestored() {
        val bad = listOf(listOf("amount", "date", "description"), listOf("12", "2026-02-30", "TEST"))
        assertTrue(runCatching { LedgerImport.prepare(bad, CsvMapping(0, 1, 2), "cash") }.isFailure)
        val safe = listOf(listOf("amount", "date", "description", "text_encoding"), listOf("12", "2026-02-28", "'=TEST", "spreadsheet-apostrophe"))
        assertEquals("=TEST", LedgerImport.prepare(safe, CsvMapping(0, 1, 2), "cash").single().merchant)
    }
}
