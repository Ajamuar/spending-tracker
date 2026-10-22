package com.ajamuar.ledger

object Csv {
    fun write(rows: List<List<String>>): String = rows.joinToString("\r\n", postfix = "\r\n") { row ->
        row.joinToString(",") { cell -> "\"${cell.replace("\"", "\"\"")}\"" }
    }
    fun read(text: String): List<List<String>> {
        require(text.length <= 8_000_000) { "CSV is too large" }
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var closed = false; var index = 0
        fun finishCell() { row.add(cell.toString()); cell.setLength(0); closed = false }
        fun finishRow() { finishCell(); if (row.any { it.isNotEmpty() }) rows.add(row); row = mutableListOf() }
        val input = text.removePrefix("\uFEFF")
        while (index < input.length) {
            val ch = input[index]
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < input.length && input[index + 1] == '"') { cell.append('"'); index++ }
                    else { quoted = false; closed = true }
                } else cell.append(ch)
            } else when (ch) {
                '"' -> { require(cell.isEmpty() && !closed) { "Malformed CSV quotation" }; quoted = true }
                ',' -> finishCell()
                '\r', '\n' -> { finishRow(); if (ch == '\r' && index + 1 < input.length && input[index + 1] == '\n') index++ }
                else -> { require(!closed) { "Unexpected text after CSV quotation" }; cell.append(ch) }
            }
            index++
        }
        require(!quoted) { "Unclosed CSV quotation" }
        if (cell.isNotEmpty() || row.isNotEmpty() || closed) finishRow()
        require(rows.size <= 50_001) { "Import at most 50,000 rows at a time" }
        return rows
    }
}
