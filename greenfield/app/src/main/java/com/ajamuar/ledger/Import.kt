package com.ajamuar.ledger

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

data class CsvMapping(val amount: Int, val date: Int, val description: Int, val category: Int = -1,
    val kind: Int = -1, val format: String = "yyyy-MM-dd", val id: Int = -1)

object LedgerImport {
    fun prepare(rows: List<List<String>>, mapping: CsvMapping, accountId: String): List<Entry> {
        require(rows.size >= 2) { "CSV has no transaction rows" }
        val header = rows.first()
        require(listOf(mapping.amount, mapping.date, mapping.description).all { it in header.indices }) { "Map amount, date and description columns" }
        val occurrences = mutableMapOf<String, Int>()
        val formatter = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(mapping.format.replace("yyyy", "uuuu"))
            .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
        return rows.drop(1).mapIndexed { index, row ->
            try {
                require(row.size == header.size)
                val escaped = header.indexOf("text_encoding").takeIf { it >= 0 }?.let { row[it] == "spreadsheet-apostrophe" } == true
                fun text(value: String): String = if (escaped && value.startsWith("'")) value.drop(1) else value
                val rawAmount = row[mapping.amount].trim().removePrefix("₹").removePrefix("INR").removePrefix("Rs.").trim()
                val amount = Money.parse(rawAmount.removePrefix("-").removePrefix("+"))
                val date = LocalDate.parse(row[mapping.date].trim(), formatter)
                require(!date.isAfter(LocalDate.now(India)) && !date.isBefore(LocalDate.of(1970, 1, 1)))
                val merchant = text(row[mapping.description]).trim()
                require(merchant.isNotEmpty() && merchant.length <= 120)
                val rawKind = row.getOrNull(mapping.kind)?.trim()?.uppercase(Locale.ROOT)
                val kind = when (rawKind) {
                    null, "" -> if (rawAmount.startsWith("+")) Kind.INCOME else Kind.EXPENSE
                    "DEBIT", "OUT", "DR" -> Kind.EXPENSE
                    "CREDIT", "IN", "CR" -> Kind.INCOME
                    else -> Kind.valueOf(rawKind)
                }
                val category = row.getOrNull(mapping.category)?.let(::text)?.trim()?.ifBlank { "Uncategorised" } ?: "Uncategorised"
                require(category.length <= 80)
                val canonical = Csv.write(listOf(header, row))
                val occurrence = occurrences.getOrDefault(canonical, 0)
                occurrences[canonical] = occurrence + 1
                val externalId = row.getOrNull(mapping.id)?.trim()?.takeIf { it.isNotEmpty() }
                val key = digest(if (externalId == null) "csv|$canonical|$occurrence" else "csv-id|$externalId")
                fun cell(name: String): String? = header.indexOf(name).takeIf { it >= 0 }?.let { row[it] }
                val status = cell("status")?.let { Status.valueOf(it) } ?: Status.POSTED
                val note = text(cell("note").orEmpty()); val tags = text(cell("tags").orEmpty())
                require(note.length <= 2000 && tags.length <= 500)
                val excluded = cell("excluded")?.toBooleanStrict() ?: false
                val direction = cell("direction")?.lowercase()?.also { require(it in listOf("in", "out")) }
                    ?: if (kind in listOf(Kind.INCOME, Kind.REFUND, Kind.REIMBURSEMENT)) "in" else "out"
                val personal = cell("personal_amount_inr")?.takeIf { it.isNotBlank() }?.let(Money::signed)
                require(personal == null || personal in 0..amount)
                Entry(id = externalId?.takeIf { runCatching { java.util.UUID.fromString(it).toString().equals(it, true) }.getOrDefault(false) } ?: java.util.UUID.randomUUID().toString(),
                    amount = amount, kind = kind.name, status = status.name, merchant = merchant, category = category, accountId = accountId,
                    time = date.atStartOfDay(India).toInstant().toEpochMilli(), source = "csv", sourceKey = key, reviewed = false,
                    note = note, tags = tags, excluded = excluded, direction = direction, personalMinor = personal)
            } catch (e: Exception) { throw IllegalArgumentException("Row ${index + 2}: check mapped amount, date, description and transaction type", e) }
        }
    }
}
