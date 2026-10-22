package com.ajamuar.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable fun BackupPasswordDialog(restore: Boolean, dismiss: () -> Unit, confirm: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (restore) "Restore encrypted backup" else "Protect your backup") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (restore) "Restore only into an empty ledger. Existing transactions will never be replaced. Pairing credentials are not part of a backup."
                else "Use at least 12 characters. Keep the password somewhere safe: it cannot be reset. Receipts and device pairing are not included.")
            OutlinedTextField(password, { password = it }, label = { Text("Backup password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (!restore) OutlinedTextField(repeated, { repeated = it }, label = { Text("Repeat password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
    }, confirmButton = { TextButton(enabled = password.length >= 12 && (restore || password == repeated), onClick = {
        val chars = password.toCharArray(); password = ""; repeated = ""; confirm(chars)
    }) { Text(if (restore) "Choose backup" else "Choose destination") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ImportDialog(rows: List<List<String>>, accounts: List<Account>, busy: Boolean, dismiss: () -> Unit, confirm: (List<Entry>) -> Unit) {
    val header = rows.first()
    fun column(vararg names: String): String = header.indexOfFirst { it.lowercase().trim() in names }.toString()
    var amount by remember { mutableStateOf(column("amount_inr", "amount", "amount (inr)")) }
    var date by remember { mutableStateOf(column("date", "transaction date")) }
    var description by remember { mutableStateOf(column("description", "merchant", "name")) }
    var category by remember { mutableStateOf(column("category")) }
    var kind by remember { mutableStateOf(column("kind", "type", "direction")) }
    var format by remember { mutableStateOf("yyyy-MM-dd") }
    var account by remember { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var preview by remember { mutableStateOf<List<Entry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val columns = header.mapIndexed { index, name -> index.toString() to "${index + 1}. $name" }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Import ${rows.size - 1} rows", style = MaterialTheme.typography.headlineSmall)
            Text("Map columns before importing. Choose one destination account per file. For a multi-account export, split it by account first. Unsupported rows reject the entire import.")
            Picker("Account", account, accounts.map { it.id to it.name }) { account = it; preview = null }
            Picker("Amount", amount, columns) { amount = it; preview = null }
            Picker("Date", date, columns) { date = it; preview = null }
            Picker("Date format", format, listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "dd-MMM-yyyy").map { it to it }) { format = it; preview = null }
            Picker("Description", description, columns) { description = it; preview = null }
            Picker("Category", category, listOf("-1" to "Uncategorised") + columns) { category = it; preview = null }
            Picker("Type / direction", kind, listOf("-1" to "Default expense (positive + means income)") + columns) { kind = it; preview = null }
            Text("Exact CSV rows are deduplicated on replay. With no stable source ID, edited or overlapping exports can still create duplicates; review them before relying on totals.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            preview?.let { prepared ->
                Text("Ready: ${prepared.size} entries · ${Money.text(prepared.sumOf { it.amount })} in total movement", style = MaterialTheme.typography.titleMedium)
                prepared.take(3).forEach { Text("${it.date()} · ${it.merchant} · ${Money.text(it.amount)} · ${it.kind}") }
                Button(onClick = { confirm(prepared) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Confirm import") }
            } ?: Button(enabled = !preparing && !busy && account.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    preparing = true; error = null
                    try {
                        val mapping = CsvMapping(amount.toInt(), date.toInt(), description.toInt(), category.toInt(), kind.toInt(), format,
                            header.indexOfFirst { it.lowercase() in listOf("id", "transaction_id") })
                        preview = withContext(Dispatchers.Default) { LedgerImport.prepare(rows, mapping, account) }
                    } catch (e: Exception) { error = e.message ?: "Check the column mapping" }
                    finally { preparing = false }
                }
            }) { Text(if (preparing) "Checking rows…" else "Validate & preview") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
