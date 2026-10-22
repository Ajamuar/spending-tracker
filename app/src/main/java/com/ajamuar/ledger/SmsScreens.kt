package com.ajamuar.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.time.Instant

@Composable fun SmsSelectionDialog(discovery: SmsDiscovery, tracked: Set<String>, busy: Boolean, dismiss: () -> Unit, confirm: (Set<String>) -> Unit) {
    var selected by remember(discovery) { mutableStateOf(tracked.intersect(discovery.accounts.filterNot { it.account.archived }.map { it.account.id }.toSet())) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Choose accounts to import") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${discovery.scanned} messages checked. Preview shows up to three recent parsed alerts per account; counts include repeat alerts and previously imported entries.")
            Text("Confirming enables future SMS capture for selected accounts. Unselected accounts in this list stop importing. Existing history stays unchanged.")
            if (discovery.accounts.isEmpty()) Text("No supported accounts found. Check Unmatched alerts, or use CSV/manual entries for unsupported banks.")
            discovery.accounts.forEach { preview ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(preview.account.id in selected, { checked -> selected = if (checked) selected + preview.account.id else selected - preview.account.id }, enabled = !busy && !preview.account.archived, modifier = Modifier.semantics { contentDescription = "Track ${preview.account.name}" })
                    Text("${preview.account.name} · ${preview.account.type}\n${preview.count} matching alerts${if (preview.account.archived) " · Archived" else ""}")
                }
                if (preview.needsLearning > 0) Text("${preview.needsLearning} alerts need format setup before they can import. Select this account, then use Settings → Set up AI learning.", style = MaterialTheme.typography.bodySmall)
                preview.samples.forEach { (time, candidate) -> Text("${Instant.ofEpochMilli(time).atZone(India).toLocalDate()} · ${candidate.merchant}\n${Money.text(candidate.amount)} · ${candidate.kind.label} · ${candidate.status.name.lowercase()}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }, confirmButton = { TextButton(enabled = !busy && selected.isNotEmpty(), onClick = { confirm(selected) }) { Text("Import selected accounts") } },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancel") } })
}

@Composable fun SmsTrackingDialog(accounts: List<Account>, tracked: Set<String>, busy: Boolean, dismiss: () -> Unit, toggle: (String, Boolean) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Accounts to track by SMS") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Off stops future imports; it never deletes history. Scan transaction alerts to discover accounts and import past messages. New accounts always start off.")
            val eligible = accounts.filter { it.type in listOf("Bank", "Card") }
            if (eligible.isEmpty()) Text("No bank accounts or cards yet. Start with Scan transaction alerts.")
            eligible.forEach { account -> Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(account.id in tracked, { toggle(account.id, it) }, enabled = !busy && !account.archived, modifier = Modifier.semantics { contentDescription = "Track ${account.name}" })
                Text("${account.name}${if (account.archived) " · Archived" else ""}", Modifier.padding(start = 8.dp))
            } }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}

@Composable fun SmsReviewDialog(alerts: List<HeldSms>, accounts: List<Account>, busy: Boolean, dismiss: () -> Unit, resolve: (String, String?) -> Unit) {
    var account by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Unmatched SMS alerts") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("These parsed alerts could not be matched safely to one account. They are outside totals and sync. Choose an account and explicitly add an alert, or ignore it. Original messages are not saved.")
            Picker("Assign to account", account, listOf("" to "Choose account") + accounts.filterNot { it.archived }.map { it.id to it.name }) { account = it }
            if (alerts.isEmpty()) Text("No unmatched alerts.")
            alerts.take(50).forEach { alert ->
                Text("${SmsAccounts.institution(alert.sender)} · ${Instant.ofEpochMilli(alert.time).atZone(India).toLocalDate()}\n${alert.candidate.merchant} · ${Money.text(alert.candidate.amount)}\n${alert.candidate.kind.label} · ${alert.candidate.status.name.lowercase()}")
                Row {
                    TextButton(enabled = !busy && account.isNotBlank(), onClick = { resolve(alert.key, account) }) { Text("Add to account") }
                    TextButton(enabled = !busy, onClick = { resolve(alert.key, null) }) { Text("Ignore") }
                }
            }
            if (alerts.size > 50) Text("Showing 50 of ${alerts.size}. Resolve these to see more.")
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}
