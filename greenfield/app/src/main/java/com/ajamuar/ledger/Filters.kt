package com.ajamuar.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate

data class ActivityFilter(val categories: Set<String> = emptySet(), val excludedCategories: Set<String> = emptySet(),
    val kind: String = "", val status: String = "", val source: String = "", val min: Long? = null,
    val max: Long? = null, val tag: String = "", val sort: String = "Newest") {
    fun matches(entry: Entry): Boolean = (categories.isEmpty() || entry.category in categories) && entry.category !in excludedCategories &&
        (kind.isEmpty() || entry.kind == kind) && (status.isEmpty() || entry.status == status) &&
        (source.isEmpty() || entry.source == source) && (min == null || entry.amount >= min) && (max == null || entry.amount <= max) &&
        (tag.isBlank() || entry.tags.split(',').any { it.trim().equals(tag.trim(), true) })
}

@Composable fun DateRangeDialog(start: String, end: String, dismiss: () -> Unit, confirm: (String, String) -> Unit) {
    var from by remember { mutableStateOf(start) }; var to by remember { mutableStateOf(end) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Report period") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(from, { from = it }, label = { Text("From · YYYY-MM-DD") })
            OutlinedTextField(to, { to = it }, label = { Text("Through · YYYY-MM-DD") })
            TextButton(onClick = {
                val today = LocalDate.now(India); val year = today.year - if (today.monthValue < 4) 1 else 0
                from = "$year-04-01"; to = "${year + 1}-03-31"
            }) { Text("Current Indian financial year") }
            Text("Budgets always use their selected calendar month.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { val a = LocalDate.parse(from); val b = LocalDate.parse(to); require(!a.isAfter(b)); a.toString() to b.toString() }
            .onSuccess { confirm(it.first, it.second) }.onFailure { error = "Enter valid dates with From before Through" }
    }) { Text("Apply") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ActivityFilterDialog(initial: ActivityFilter, categories: List<String>, dismiss: () -> Unit, confirm: (ActivityFilter) -> Unit) {
    var filter by remember { mutableStateOf(initial) }; var min by remember { mutableStateOf(initial.min?.let(Money::decimal).orEmpty()) }
    var max by remember { mutableStateOf(initial.max?.let(Money::decimal).orEmpty()) }; var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Filter your ledger", style = MaterialTheme.typography.headlineSmall)
            Picker("Type", filter.kind, listOf("" to "All") + Kind.entries.map { it.name to it.label }) { filter = filter.copy(kind = it) }
            Picker("Status", filter.status, listOf("" to "All") + Status.entries.map { it.name to it.name }) { filter = filter.copy(status = it) }
            Picker("Source", filter.source, listOf("" to "All", "manual" to "Manual", "sms" to "SMS", "csv" to "CSV")) { filter = filter.copy(source = it) }
            Picker("Order", filter.sort, listOf("Newest", "Oldest", "Amount").map { it to it }) { filter = filter.copy(sort = it) }
            OutlinedTextField(min, { min = it }, label = { Text("Minimum rupees · optional") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(max, { max = it }, label = { Text("Maximum rupees · optional") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(filter.tag, { filter = filter.copy(tag = it) }, label = { Text("Exact tag · optional") }, modifier = Modifier.fillMaxWidth())
            Text("Include categories · none means all", style = MaterialTheme.typography.titleMedium)
            categories.forEach { name -> Row {
                Checkbox(name in filter.categories, { checked -> filter = filter.copy(categories = if (checked) filter.categories + name else filter.categories - name) })
                Text(name, Modifier.padding(top = 12.dp))
            } }
            Picker("Exclude a category", "", listOf("" to "Choose") + categories.map { it to it }) { if (it.isNotBlank()) filter = filter.copy(excludedCategories = filter.excludedCategories + it) }
            if (filter.excludedCategories.isNotEmpty()) TextButton(onClick = { filter = filter.copy(excludedCategories = emptySet()) }) { Text("Clear exclusions: ${filter.excludedCategories.joinToString()}") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                runCatching {
                    val lower = min.takeIf(String::isNotBlank)?.let(Money::signed); val upper = max.takeIf(String::isNotBlank)?.let(Money::signed)
                    require((lower == null || lower >= 0) && (upper == null || upper >= 0) && (lower == null || upper == null || lower <= upper))
                    filter.copy(min = lower, max = upper)
                }.onSuccess(confirm).onFailure { error = "Check the minimum and maximum amounts" }
            }, Modifier.fillMaxWidth()) { Text("Apply filters") }
            TextButton(onClick = { confirm(ActivityFilter()) }, Modifier.fillMaxWidth()) { Text("Reset all advanced filters") }
        }
    }
}

@Composable fun CompareDialog(entries: List<Entry>, busy: Boolean, dismiss: () -> Unit, keep: (Entry, Entry) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Compare possible duplicates") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Two equal amounts can be legitimate payments. Only mark one as a duplicate if you have checked its source and account. Removed records remain in Deleted transactions.")
            entries.forEachIndexed { index, entry ->
                Text("${index + 1}. ${entry.merchant}\n${entry.date()} · ${Money.text(entry.amount)}\n${entry.source} · ${entry.kind} · ${entry.accountId}")
                TextButton(enabled = !busy && entries[0].amount == entries[1].amount, onClick = { keep(entry, entries[1 - index]) }) { Text("Keep ${index + 1}; mark the other duplicate") }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Keep both") } })
}

@Composable fun AccountMergeDialog(accounts: List<Account>, entries: List<Entry>, busy: Boolean, dismiss: () -> Unit, merge: (String, String) -> Unit) {
    var from by remember { mutableStateOf(accounts.first().id) }; var to by remember { mutableStateOf("") }
    val source = accounts.find { it.id == from }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Resolve duplicate account labels") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Picker("From", from, accounts.map { it.id to it.name }) { from = it; to = "" }
            Picker("Into", to, listOf("" to "Choose destination") + accounts.filter { !it.archived && it.id != from && it.type == source?.type }.map { it.id to it.name }) { to = it }
            Text("${entries.count { it.accountId == from }} entries will be reassigned and the old label archived. Future SMS imports use the destination. Balances are not combined and duplicate transactions are not removed. Check that both labels identify the same real account.")
        }
    }, confirmButton = { TextButton(enabled = !busy && to.isNotEmpty(), onClick = { merge(from, to) }) { Text("Confirm reassignment") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
