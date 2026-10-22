package com.ajamuar.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

@Composable fun BillsPane(bills: List<Bill>, entries: List<Entry>, vm: LedgerViewModel) {
    var editing by remember { mutableStateOf<Bill?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bills & recurring commitments", style = MaterialTheme.typography.titleLarge)
        Text("A local due-date tracker. Recording a payment does not move money or verify it with your bank.", style = MaterialTheme.typography.bodySmall)
        if (bills.isEmpty()) EmptyCard("Stay ahead of due dates", "Add a utility bill, credit-card due date, rent or renewal.")
        bills.forEach { bill -> SurfaceCard(onClick = { editing = bill }) {
            Text(bill.name, style = MaterialTheme.typography.titleMedium)
            Text("${bill.amount?.let(Money::text) ?: "Amount not known"} · due ${bill.due}")
            Text(if (bill.paid) if (bill.paymentId != null) "Matched to a ledger payment" else "Marked paid by you"
                else if (LocalDate.parse(bill.due).isBefore(LocalDate.now(India))) "Overdue · review status" else "Upcoming · ${bill.repeat}", color = Clay)
        } }
        Button(onClick = { editing = Bill(name = "", due = LocalDate.now(India).toString()) }, Modifier.fillMaxWidth()) { Text("Add bill or reminder") }
    }
    editing?.let { bill -> BillEditor(bill, entries, vm.busy, { editing = null }, { updated ->
        vm.action(success = { editing = null }) { vm.repo.saveBill(updated) }
    }, { vm.action(success = { editing = null }) { vm.repo.dao.deleteBill(bill) } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BillEditor(bill: Bill, entries: List<Entry>, busy: Boolean, dismiss: () -> Unit, save: (Bill) -> Unit, delete: () -> Unit) {
    var name by remember { mutableStateOf(bill.name) }; var amount by remember { mutableStateOf(bill.amount?.let(Money::decimal).orEmpty()) }
    var due by remember { mutableStateOf(bill.due) }; var repeat by remember { mutableStateOf(bill.repeat) }
    var paid by remember { mutableStateOf(bill.paid) }; var payment by remember { mutableStateOf(bill.paymentId.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }; var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Bill details", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Biller / obligation") })
            OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Amount · leave blank if unknown") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(due, { due = it }, Modifier.fillMaxWidth(), label = { Text("Due date · YYYY-MM-DD") })
            Picker("Repeat", repeat, listOf("None", "Monthly", "Yearly").map { it to it }) { repeat = it }
            Row { Checkbox(paid, { paid = it }); Text("Marked paid") }
            Picker("Match payment", payment, listOf("" to "No match · manually tracked") + entries.filter { it.status == Status.POSTED.name && it.kind in listOf(Kind.EXPENSE.name, Kind.CARD_PAYMENT.name, Kind.TRANSFER.name) }.take(100).map { it.id to "${it.merchant} · ${Money.text(it.amount)}" }) { payment = it; if (it.isNotEmpty()) paid = true }
            if (paid && repeat != "None") TextButton(onClick = {
                runCatching { LocalDate.parse(due).let { if (repeat == "Monthly") it.plusMonths(1) else it.plusYears(1) } }
                    .onSuccess { due = it.toString(); paid = false; payment = "" }.onFailure { error = "Check the due date" }
            }) { Text("Advance to next due date") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                runCatching { bill.copy(name = name.trim(), amount = if (amount.isBlank()) null else Money.parse(amount), due = LocalDate.parse(due).toString(), repeat = repeat, paid = paid, paymentId = if (paid) payment.takeIf(String::isNotBlank) else null).also(::validateBill) }
                    .onSuccess(save).onFailure { error = "Check the name, amount and due date" }
            }) { Text("Save bill") }
            if (bill.name.isNotBlank()) TextButton(onClick = { confirmDelete = true }) { Text("Remove bill") }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Remove this bill?") }, text = { Text("Ledger payments remain unchanged.") }, confirmButton = { TextButton(onClick = delete) { Text("Remove") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } })
}

@Composable fun GroupsPane(groups: List<PrivateGroup>, splits: List<Split>, entries: List<Entry>, accounts: List<Account>, vm: LedgerViewModel) {
    var selected by remember { mutableStateOf<PrivateGroup?>(null) }; var creating by remember { mutableStateOf(false) }
    var addExpense by remember { mutableStateOf(false) }; var settle by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Split?>(null) }
    val group = selected
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Private groups & money owed", style = MaterialTheme.typography.titleLarge)
        Text("Local records only. No invitations, messages or notifications are sent to other people.", style = MaterialTheme.typography.bodySmall)
        if (group == null) {
            if (groups.isEmpty()) EmptyCard("Keep shared costs clear", "Create a private group, divide expenses and record settlements.")
            groups.forEach { g -> SurfaceCard(onClick = { selected = g }) {
                Text(g.name, style = MaterialTheme.typography.titleMedium); Text(members(g).joinToString(" · "))
            } }
            Button(onClick = { creating = true }, Modifier.fillMaxWidth()) { Text("Create private group") }
        } else {
            TextButton(onClick = { selected = null }) { Text("← All groups") }
            val history = splits.filter { it.groupId == group.id }
            val balances = SplitMath.balances(members(group), history.map { it to shares(it) })
            SurfaceCard {
                Text(group.name, style = MaterialTheme.typography.titleLarge)
                balances.forEach { (person, balance) -> Text("$person · ${if (balance >= 0) "is owed" else "owes"} ${Money.text(kotlin.math.abs(balance))}") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { addExpense = true }) { Text("Add expense") }
                OutlinedButton(onClick = { settle = true }) { Text("Settle up") }
            }
            history.forEach { split -> SurfaceCard {
                Text(split.description, style = MaterialTheme.typography.titleMedium)
                Text("${Money.text(split.amount)} · ${split.payer} ${if (split.settlementTo == null) "paid" else "paid ${split.settlementTo}"}")
                if (split.settlementTo == null) Text("Your share: ${Money.text(shares(split).getValue("You"))}")
                TextButton(onClick = { removing = split }) { Text("Remove / correct") }
            } }
        }
    }
    if (creating) GroupDialog({ creating = false }) { g -> vm.action(success = { creating = false; selected = g }) { validateGroup(g); vm.repo.dao.putGroup(g) } }
    if (group != null && (addExpense || settle)) SplitEditor(group, entries, accounts, settle, vm.busy, { addExpense = false; settle = false }) { split, account ->
        vm.action(success = { addExpense = false; settle = false }) { vm.repo.saveSplit(split, account) }
    }
    removing?.let { split -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove this group record?") }, text = {
        Text("An attached existing purchase returns to its full personal amount. A purchase created by this group record is deleted. Add a corrected record afterward.")
    }, confirmButton = { TextButton(enabled = !vm.busy, onClick = { vm.action(success = { removing = null }) { vm.repo.removeSplit(split) } }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } }) }
}

@Composable fun GroupDialog(dismiss: () -> Unit, confirm: (PrivateGroup) -> Unit) {
    var name by remember { mutableStateOf("") }; var people by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Create a private group") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Group name") })
            OutlinedTextField(people, { people = it }, label = { Text("Other members · comma separated") })
            Text("You are added automatically. Names are local labels, not contacts.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { PrivateGroup(name = name.trim(), members = JSONArray(listOf("You") + people.split(',').map { it.trim() }).toString()).also(::validateGroup) }
            .onSuccess(confirm).onFailure { error = it.message ?: "Check group and unique member names" }
    }) { Text("Create") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SplitEditor(group: PrivateGroup, entries: List<Entry>, accounts: List<Account>, settlement: Boolean, busy: Boolean, dismiss: () -> Unit, save: (Split, String) -> Unit) {
    val people = members(group)
    var amount by remember { mutableStateOf("") }; var description by remember { mutableStateOf(if (settlement) "Settlement" else "") }
    var payer by remember { mutableStateOf("You") }; var recipient by remember { mutableStateOf(people[1]) }
    var account by remember { mutableStateOf(accounts.firstOrNull { !it.archived }?.id.orEmpty()) }
    var attached by remember { mutableStateOf("") }; var custom by remember { mutableStateOf(false) }
    var allocations by remember { mutableStateOf(people.associateWith { "" }) }; var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (settlement) "Record a settlement" else "Split an expense", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description") })
            OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Total in rupees") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Picker(if (settlement) "Paid by" else "Payer", payer, people.map { it to it }) { payer = it; attached = "" }
            if (settlement) {
                Picker("Paid to", recipient, people.filter { it != payer }.map { it to it }) { recipient = it }
                Text("This records group settlement only. Add or classify the actual bank movement as a reimbursement or transfer separately.")
            } else {
                Row { Checkbox(custom, { custom = it }); Text("Custom amounts instead of equal shares") }
                if (custom) people.forEach { person -> OutlinedTextField(allocations.getValue(person), { allocations = allocations + (person to it) }, Modifier.fillMaxWidth(), label = { Text("$person · rupees") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) }
                if (payer == "You") {
                    Picker("Account", account, accounts.filterNot { it.archived }.map { it.id to it.name }) { account = it }
                    Picker("Existing purchase", attached, listOf("" to "Create a new purchase") + entries.filter { it.kind == Kind.EXPENSE.name }.take(100).map { it.id to "${it.merchant} · ${Money.text(it.amount)}" }) {
                        attached = it
                        entries.find { e -> e.id == it }?.let { e -> amount = Money.decimal(e.amount); description = e.merchant; account = e.accountId }
                    }
                }
                Text("Your share counts as personal spending. The full purchase stays visible as money movement. Expenses others pay are tracked in a separate payable account.", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                runCatching {
                    val value = Money.parse(amount)
                    val splitShares = if (settlement) emptyMap() else if (custom) people.associateWith { if (allocations.getValue(it).trim() in listOf("0", "0.00")) 0L else Money.parse(allocations.getValue(it)) }
                        else people.zip(SplitMath.equal(value, people.size)).toMap()
                    Split(groupId = group.id, description = description.trim(), payer = payer, shares = JSONObject(splitShares).toString(), amount = value,
                        settlementTo = if (settlement) recipient else null, entryId = attached.takeIf { it.isNotEmpty() }).also { validateSplit(it, group) }
                }.onSuccess { save(it, account) }.onFailure { error = it.message ?: "Check shares, people and amount" }
            }) { Text("Save record") }
        }
    }
}

@Composable fun RuleDialog(dismiss: () -> Unit, save: (MerchantRule) -> Unit) {
    var merchant by remember { mutableStateOf("") }; var category by remember { mutableStateOf(Categories[1]) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Future SMS categorisation") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(merchant, { merchant = it }, label = { Text("Exact merchant name") })
            Picker("Category", category, Categories.map { it to it }) { category = it }
            Text("Case-insensitive exact match. Existing entries and your manual corrections stay unchanged.")
        }
    }, confirmButton = { TextButton(enabled = merchant.isNotBlank() && merchant.length <= 120, onClick = { save(MerchantRule(merchant.trim().lowercase(), category)) }) { Text("Save rule") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
