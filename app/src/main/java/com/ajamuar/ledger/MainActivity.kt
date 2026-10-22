package com.ajamuar.ledger

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

val Ink = Color(0xFF183E38)
val Paper = Color(0xFFF7F7F0)
val Clay = Color(0xFFB65B37)
val Categories = listOf("Uncategorised", "Food & dining", "Groceries", "Transport", "Shopping", "Bills & utilities", "Housing", "Health", "Entertainment", "Education", "Travel", "Income", "Other")
val India: ZoneId = ZoneId.of("Asia/Kolkata")
fun Entry.date(): LocalDate = Instant.ofEpochMilli(time).atZone(India).toLocalDate()

class MainActivity : ComponentActivity() {
    private var unlocked by mutableStateOf(false)
    private var lockEnabled by mutableStateOf(false)
    private val credentials = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> unlocked = result.resultCode == RESULT_OK }
    override fun onStop() {
        super.onStop()
        lockEnabled = getSharedPreferences("ledger", MODE_PRIVATE).getBoolean("appLock", false)
        if (lockEnabled) { unlocked = false; window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockEnabled = getSharedPreferences("ledger", MODE_PRIVATE).getBoolean("appLock", false)
        if (lockEnabled) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Ink, secondary = Clay, background = Paper,
                surface = Paper, surfaceVariant = Color(0xFFE9EDE3), onPrimary = Color.White,
                primaryContainer = Color(0xFFE0EBDD), onPrimaryContainer = Ink,
                secondaryContainer = Color(0xFFE0EBDD), onSecondaryContainer = Ink,
                outline = Color(0xFFBCC7B9), outlineVariant = Color(0xFFE2E5DB))) {
                val result = remember { runCatching { (application as LedgerApplication).repository } }
                if (result.isFailure) Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(28.dp)) {
                        Text("Your ledger could not be unlocked", style = MaterialTheme.typography.headlineSmall)
                        Text("The encrypted data has been kept intact. Do not clear app storage. Restore from a backup after resolving the device key issue.")
                    }
                } else Box {
                    Box(if (lockEnabled && !unlocked) Modifier.clearAndSetSemantics { } else Modifier) { LedgerApp() }
                    if (lockEnabled && !unlocked) {
                    Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.Center) {
                        Text("Your money stays yours.", style = MaterialTheme.typography.headlineMedium, color = Ink)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = {
                            val keyguard = getSystemService(android.app.KeyguardManager::class.java)
                            @Suppress("DEPRECATION")
                            val request = keyguard.createConfirmDeviceCredentialIntent("Unlock Rupee Ledger", "Use your device screen lock")
                            if (request != null) credentials.launch(request)
                        }) { Text("Unlock ledger") }
                    } }
                    }
                }
            }
        }
    }
}

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as LedgerApplication).repository
    val entries = repo.dao.entries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val accounts = repo.dao.accounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val budgets = repo.dao.budgets().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pending = repo.dao.pendingCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val bills = repo.dao.bills().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val groups = repo.dao.groups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val splits = repo.dao.splits().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val rules = repo.dao.rules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val smsSettings = repo.dao.smsSettings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var discovery by mutableStateOf<SmsDiscovery?>(null)
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var scanning by mutableStateOf(false); private set
    var scanProgress by mutableStateOf(""); private set
    private var scanJob: Job? = null
    init { action { repo.initialise() } }
    fun action(success: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { block() }; success() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: "Unable to complete this action" else "Unable to complete this action. Your existing data is intact." }
            finally { busy = false }
        }
    }
    fun discover(days: Int) {
        if (scanning || busy) return
        scanJob = viewModelScope.launch {
            scanning = true
            scanProgress = "Looking for bank accounts and cards…"
            try {
                discovery = discoverSms(getApplication(), repo, days) { scanned, found ->
                    viewModelScope.launch { scanProgress = "$scanned messages checked · $found accounts found" }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Account discovery could not finish. Check SMS permission and try again." }
            finally { scanning = false }
        }
    }
    fun scan(days: Int) {
        if (scanning) return
        scanJob = viewModelScope.launch {
            scanning = true
            try {
                val result = scanSms(getApplication(), repo, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())) { scanned, added ->
                    viewModelScope.launch { scanProgress = "$scanned messages checked · $added new entries" }
                }
                message = "Scan complete: ${result.first} messages checked, ${result.second} new entries. Review imported entries before relying on totals."
            } catch (e: CancellationException) { scanProgress = "Scan paused. Rescanning is safe."; throw e }
            catch (_: Exception) { message = "SMS scan could not finish. Check SMS permission and rescan; existing entries are safe." }
            finally { scanning = false }
        }
    }
    fun cancelScan() { scanJob?.cancel() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LedgerApp(vm: LedgerViewModel = viewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val budgets by vm.budgets.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val bills by vm.bills.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val splits by vm.splits.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val smsSettings by vm.smsSettings.collectAsStateWithLifecycle()
    val tracked = smsSettings.filter { it.key.startsWith("sms-track:") && it.value == "true" }.map { it.key.removePrefix("sms-track:") }.toSet()
    val held = smsSettings.mapNotNull(HeldSms::read)
    var showSmsAccounts by remember { mutableStateOf(false) }
    var showSmsReview by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    val syncState by vm.repo.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now(India).toString()) }
    val month = YearMonth.parse(monthText)
    var query by rememberSaveable { mutableStateOf("") }
    var accountFilter by rememberSaveable { mutableStateOf("") }
    var categoryFilter by rememberSaveable { mutableStateOf("") }
    var reviewOnly by rememberSaveable { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Entry?>(null) }
    var accountEditor by remember { mutableStateOf<Account?>(null) }
    var budgetEditor by remember { mutableStateOf<Budget?>(null) }
    var showPairing by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showBulk by remember { mutableStateOf(false) }
    var importRows by remember { mutableStateOf<List<List<String>>?>(null) }
    var backupMode by remember { mutableStateOf<String?>(null) }
    var backupPassword by remember { mutableStateOf<CharArray?>(null) }
    var planPane by rememberSaveable { mutableStateOf("Budgets") }
    var showRule by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var rangeStart by rememberSaveable { mutableStateOf("") }
    var rangeEnd by rememberSaveable { mutableStateOf("") }
    var showRange by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(ActivityFilter()) }
    var showFilters by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var mergingAccounts by remember { mutableStateOf(false) }
    val preferences = remember { context.getSharedPreferences("ledger", Context.MODE_PRIVATE) }
    var appLock by remember { mutableStateOf(preferences.getBoolean("appLock", false)) }
    var reminders by remember { mutableStateOf(preferences.getBoolean("reminders", false)) }
    var syncPaused by remember { mutableStateOf(preferences.getBoolean("syncPaused", false)) }
    val snackbar = remember { SnackbarHostState() }
    val visible = entries.filter { !it.deleted }
    val period = visible.filter { if (rangeStart.isNotEmpty() && rangeEnd.isNotEmpty()) it.date().toString() in rangeStart..rangeEnd else YearMonth.from(it.date()) == month }
    val budgetPeriod = visible.filter { YearMonth.from(it.date()) == month }
    val filtered = period.filter { e ->
        (accountFilter.isEmpty() || e.accountId == accountFilter) &&
            (categoryFilter.isEmpty() || e.category == categoryFilter) && (!reviewOnly || !e.reviewed) &&
            (query.isBlank() || listOf(e.merchant, e.category, e.tags, e.note, Money.decimal(e.amount)).any { it.contains(query, true) }) && advanced.matches(e)
    }.let { list -> when (advanced.sort) { "Oldest" -> list.sortedBy { it.time }; "Amount" -> list.sortedByDescending { it.amount }; else -> list } }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.READ_SMS] == true) showScan = true
        else vm.message = "Manual entries and CSV export work without SMS access. You can enable access in Android Settings later."
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reminders = granted
        preferences.edit().putBoolean("reminders", granted).apply()
        if (!granted) vm.message = "Reminders are off. Bills and budgets remain available in Plans."
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.action(success = { vm.message = "CSV exported. Keep financial exports private." }) {
            val content = vm.repo.exportCsv()
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(content) }
        }
    }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.action(success = { vm.message = "Monthly PDF report saved." }) {
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output -> pdfReport(period, "Rupee Ledger · $month", output) }
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            var rows = emptyList<List<String>>()
            vm.action(success = { importRows = rows }) {
                val text = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readLimited(8_000_000) }.toString(Charsets.UTF_8)
                rows = Csv.read(text)
                require(rows.size >= 2) { "Choose a CSV with a header and transaction rows" }
            }
        }
    }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = backupPassword; backupPassword = null
        if (uri != null && password != null) vm.action(success = { vm.message = "Encrypted backup saved. Keep its password separately; it cannot be recovered." }) {
            val bytes = vm.repo.backup(password)
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
        } else password?.fill('\u0000')
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val password = backupPassword; backupPassword = null
        if (uri != null && password != null) vm.action(success = { vm.message = "Backup restored. Review account mappings before pairing your computer." }) {
            val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readLimited(16_001_000) }
            vm.repo.restore(bytes, password)
        } else password?.fill('\u0000')
    }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    val titles = listOf("Your money, clearly.", "The full picture.", "Every account.", "Make room for what matters.", "You're in control.")
    val tabs = listOf("Overview", "Activity", "Accounts", "Plans", "Settings")
    val icons = listOf(Icons.Outlined.Home, Icons.AutoMirrored.Outlined.ListAlt, Icons.Outlined.AccountBalanceWallet, Icons.Outlined.PieChart, Icons.Outlined.Tune)
    Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(snackbar) },
        topBar = { Column(Modifier.statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text("RUPEE / LEDGER", fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold, color = Clay)
            Spacer(Modifier.height(8.dp))
            Text(titles[tab], style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = Ink)
            if (vm.busy || vm.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        } },
        bottomBar = { NavigationBar(containerColor = Paper) {
            tabs.forEachIndexed { index, title -> NavigationBarItem(selected = tab == index,
                onClick = { tab = index; selected = emptySet() }, icon = { Icon(icons[index], title) }, label = { Text(title, fontSize = 10.sp) }) }
        } },
        floatingActionButton = { if (tab <= 1) FloatingActionButton(onClick = { editor = Entry(amount = 0, merchant = "") }, containerColor = Ink, contentColor = Color.White) {
            Icon(Icons.Outlined.Add, "Add transaction")
        } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (tab in listOf(0, 1, 3)) item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { rangeStart = ""; rangeEnd = ""; monthText = month.minusMonths(1).toString() }) { Icon(Icons.Outlined.ChevronLeft, "Previous month") }
                    TextButton(onClick = { showRange = true }) { Text(if (rangeStart.isNotEmpty() && tab != 3) "$rangeStart → $rangeEnd" else month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), fontWeight = FontWeight.SemiBold) }
                    IconButton(onClick = { rangeStart = ""; rangeEnd = ""; monthText = month.plusMonths(1).toString() }) { Icon(Icons.Outlined.ChevronRight, "Next month") }
                }
            }
            when (tab) {
                0 -> {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(28.dp)) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("NET SPENDING", color = Color(0xFFBDDAC6), letterSpacing = 2.sp, fontSize = 11.sp)
                                Text(Money.text(period.sumOf { it.personalSpend() }), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.SemiBold)
                                Text("Posted expenses less refunds · ${period.count { !it.reviewed }} to review", color = Color(0xFFBDDAC6), fontSize = 12.sp)
                                HorizontalDivider(color = Color.White.copy(alpha = .15f))
                                Text("Income  ${Money.text(period.filter { it.kind == Kind.INCOME.name && it.status == Status.POSTED.name && !it.excluded }.sumOf { it.amount })}", color = Color.White)
                            }
                        }
                    }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { tab = 1; reviewOnly = true }, label = { Text("${visible.count { !it.reviewed }} need review") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.FactCheck, null, Modifier.size(16.dp)) })
                        AssistChip(onClick = { tab = 4 }, label = { Text("$pending queued") }, leadingIcon = { Icon(Icons.Outlined.Sync, null, Modifier.size(16.dp)) })
                    } }
                    item { SectionLabel("WHERE IT WENT") }
                    val categoryTotals = period.groupBy { it.category }.mapValues { (_, values) -> values.sumOf { it.personalSpend() } }.filterValues { it > 0 }.toList().sortedByDescending { it.second }
                    if (categoryTotals.isEmpty()) item { EmptyCard("A fresh start", "Add your first expense, or import transaction alerts from Settings.") }
                    items(categoryTotals.take(6), key = { it.first }) { (category, amount) ->
                        SurfaceCard(onClick = { categoryFilter = category; tab = 1 }) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(category); Text(Money.text(amount), fontWeight = FontWeight.SemiBold) }
                            LinearProgressIndicator(progress = { (amount.toFloat() / categoryTotals.sumOf { it.second }.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                        }
                    }
                    item { SectionLabel("RECENT ACTIVITY") }
                    items(period.take(5), key = { it.id }) { entry -> EntryRow(entry, accounts, onClick = { editor = entry }) }
                    item { OutlinedButton(onClick = { exportPdf.launch("rupee-ledger-$month.pdf") }, Modifier.fillMaxWidth()) { Text("Export this month's PDF report") } }
                }
                1 -> {
                    item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search merchant, category, tags or notes") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(reviewOnly, { reviewOnly = !reviewOnly }, { Text("Needs review") })
                        if (categoryFilter.isNotEmpty() || accountFilter.isNotEmpty() || query.isNotEmpty() || advanced != ActivityFilter()) AssistChip(onClick = { categoryFilter = ""; accountFilter = ""; query = ""; advanced = ActivityFilter() }, label = { Text("Clear filters") })
                        if (selected.isNotEmpty()) AssistChip(onClick = { showBulk = true }, label = { Text("Edit ${selected.size}") })
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { showFilters = true }, label = { Text("More filters & sorting") })
                        if (selected.size == 2) AssistChip(onClick = { comparing = true }, label = { Text("Compare two entries") })
                    } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { Picker("Account", accountFilter, listOf("" to "All accounts") + accounts.map { it.id to it.name }) { accountFilter = it } }
                        Box(Modifier.weight(1f)) { Picker("Category", categoryFilter, listOf("" to "All categories") + (Categories + visible.map { it.category }).distinct().map { it to it }) { categoryFilter = it } }
                    } }
                    item { Text("${filtered.size} entries · net spending ${Money.text(filtered.sumOf { it.personalSpend() })}", color = Ink, style = MaterialTheme.typography.labelLarge) }
                    if (filtered.isEmpty()) item { EmptyCard("Nothing here yet", "Try another month or clear your filters. Transfers and failed alerts stay visible in the ledger.") }
                    items(filtered, key = { it.id }) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(entry.id in selected, { selected = if (entry.id in selected) selected - entry.id else selected + entry.id })
                            Box(Modifier.weight(1f)) { EntryRow(entry, accounts, onClick = { editor = entry }) }
                        }
                    }
                }
                2 -> {
                    item { OutlinedButton(onClick = { showSmsAccounts = true }, Modifier.fillMaxWidth()) { Text("Choose SMS accounts · ${tracked.size} selected") } }
                    item { Text("Accounts are local labels, not bank connections. SMS imports never imply a live balance.", style = MaterialTheme.typography.bodyMedium) }
                    items(accounts, key = { it.id }) { account -> SurfaceCard(onClick = { accountEditor = account }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text(account.name, fontWeight = FontWeight.SemiBold); Text("${account.type} · ${if (account.archived) "Archived" else "${visible.count { it.accountId == account.id }} entries"}", style = MaterialTheme.typography.bodySmall) }
                            Icon(Icons.Outlined.ChevronRight, "Edit account")
                        }
                        Text(account.calculatedBalance(visible)?.let { "Calculated: ${Money.text(it)} · assumes complete capture" } ?: "Balance unknown · set an opening balance", modifier = Modifier.padding(top = 10.dp))
                        account.observedMinor?.let { observed ->
                            Text("Observed: ${Money.text(observed)} · ${account.observedAt?.let { Instant.ofEpochMilli(it).atZone(India).toLocalDate() }}", style = MaterialTheme.typography.bodySmall)
                            account.calculatedBalance(visible, account.observedAt ?: Long.MAX_VALUE)?.let { calculated ->
                                Text("Difference at observation: ${Money.text(observed - calculated)}", color = Clay, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(if (account.repoId.isBlank()) "Set repo account ID to sync" else "Repo: ${account.repoId}", color = Clay, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    } }
                    item { OutlinedButton(onClick = { accountEditor = Account(name = "") }, Modifier.fillMaxWidth()) { Text("Add account") } }
                    if (accounts.size > 1) item { TextButton(onClick = { mergingAccounts = true }, Modifier.fillMaxWidth()) { Text("Resolve duplicate account labels") } }
                }
                3 -> {
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Budgets", "Bills", "Groups").forEach { name -> FilterChip(planPane == name, { planPane = name }, { Text(name) }) }
                    } }
                    if (planPane == "Budgets") {
                    item { Text("Monthly category limits", style = MaterialTheme.typography.titleLarge, color = Ink) }
                    if (budgets.isEmpty()) item { EmptyCard("Give your money a direction", "Choose a monthly limit for a category. The same limit applies each month until you change it.") }
                    items(budgets, key = { it.category }) { budget ->
                        val spend = budgetPeriod.filter { it.category == budget.category }.sumOf { it.personalSpend() }
                        SurfaceCard(onClick = { budgetEditor = budget }) {
                            Text(budget.category, fontWeight = FontWeight.SemiBold)
                            Text("${Money.text(spend)} of ${Money.text(budget.limitMinor)}", modifier = Modifier.padding(vertical = 10.dp))
                            LinearProgressIndicator(progress = { (spend.toFloat() / budget.limitMinor).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = if (spend > budget.limitMinor) Clay else Ink)
                            Text(if (spend > budget.limitMinor) "${Money.text(spend - budget.limitMinor)} over limit" else "${Money.text(budget.limitMinor - spend)} remaining", color = Clay, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    item { Button(onClick = { budgetEditor = Budget("", 0) }, Modifier.fillMaxWidth()) { Text("Set a category budget") } }
                    } else if (planPane == "Bills") item { BillsPane(bills, visible, vm) }
                    else item { GroupsPane(groups, splits, visible, accounts, vm) }
                }
                4 -> {
                    item { SectionLabel("CAPTURE") }
                    item { SurfaceCard {
                        Text("Transaction SMS", style = MaterialTheme.typography.titleMedium)
                        Text("Discover supported bank accounts and cards, then choose which ones to track. No account is selected automatically; message bodies are never saved.", modifier = Modifier.padding(vertical = 10.dp))
                        Text("Only selected, active accounts can import automatically. Unidentified alerts stay outside your ledger until you review them.", style = MaterialTheme.typography.bodySmall)
                        if (vm.scanning) { Text(vm.scanProgress); TextButton(onClick = { vm.cancelScan() }) { Text("Pause scan") } }
                        else Button(onClick = {
                            if (context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) showScan = true
                            else permissions.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                        }, enabled = !vm.busy) { Text("Scan transaction alerts") }
                        TextButton(onClick = { showSmsAccounts = true }, enabled = !vm.busy && !vm.scanning) { Text("Choose SMS accounts · ${tracked.size} selected") }
                        TextButton(onClick = { showSmsReview = true }, enabled = !vm.busy && !vm.scanning) { Text("Unmatched alerts · ${held.size}") }
                    } }
                    item { SurfaceCard {
                        Text("AI pattern learning & categories", style = MaterialTheme.typography.titleMedium)
                        Text("Use your OpenAI API key to suggest SMS formats and merchant categories. Preview shared data and approve rules before they run locally.", modifier = Modifier.padding(vertical = 10.dp))
                        OutlinedButton(enabled = !vm.busy && !vm.scanning, onClick = { showAi = true }) { Text("Set up AI learning") }
                    } }
                    item { SectionLabel("YOUR DATA") }
                    item { SurfaceCard {
                        Text("Personal-finance sync", style = MaterialTheme.typography.titleMedium)
                        Text(syncState, modifier = Modifier.padding(vertical = 10.dp))
                        Text("$pending changes pending · delivery resumes when the paired computer is reachable.", style = MaterialTheme.typography.bodySmall)
                        Row { TextButton(onClick = { showPairing = true }) { Text("Pair computer") }; TextButton(onClick = { vm.repo.scheduleSync(force = true) }) { Text("Sync now") } }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(syncPaused, { paused -> syncPaused = paused; preferences.edit().putBoolean("syncPaused", paused).apply(); if (!paused) vm.repo.scheduleSync() })
                            Text("Pause automatic delivery", Modifier.padding(start = 8.dp))
                        }
                    } }
                    item { OutlinedButton(onClick = { export.launch("rupee-ledger-${LocalDate.now(India)}.csv") }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Export ledger CSV") } }
                    item { OutlinedButton(onClick = { importFile.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel")) }, Modifier.fillMaxWidth()) { Text("Import CSV · preview and map columns") } }
                    item { OutlinedButton(onClick = { backupMode = "save" }, Modifier.fillMaxWidth()) { Text("Create encrypted backup") } }
                    item { OutlinedButton(onClick = { backupMode = "restore" }, Modifier.fillMaxWidth()) { Text("Restore backup into empty ledger") } }
                    item { SectionLabel("CATEGORISATION RULES") }
                    items(rules, key = { it.merchant }) { rule -> SurfaceCard {
                        Text("${rule.merchant} → ${rule.category}")
                        TextButton(onClick = { vm.action { vm.repo.dao.deleteRule(rule) } }) { Text("Remove rule") }
                    } }
                    item { OutlinedButton(onClick = { showRule = true }, Modifier.fillMaxWidth()) { Text("Add exact merchant rule") } }
                    item { SectionLabel("PRIVACY & RECOVERY") }
                    item { SurfaceCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Lock when app leaves the screen"); Text("Requires a device screen lock", style = MaterialTheme.typography.bodySmall) }
                            Switch(appLock, { checked ->
                                if (context.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure) {
                                    appLock = checked; preferences.edit().putBoolean("appLock", checked).apply()
                                } else vm.message = "Set a PIN or screen lock in Android Settings first."
                            })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Private bill & budget reminders", Modifier.weight(1f))
                            Switch(reminders, { enabled ->
                                if (enabled && android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else { reminders = enabled; preferences.edit().putBoolean("reminders", enabled).apply() }
                            })
                        }
                        Text("Background checks are approximate. Notifications never include amounts or merchant names.", style = MaterialTheme.typography.bodySmall)
                    } }
                    item { OutlinedButton(onClick = { showTrash = true }, Modifier.fillMaxWidth()) { Text("Deleted transactions · restore") } }
                    item { Text("Encrypted on this phone · no ads or analytics\nRupee Ledger 0.1 · independent personal software\nCSV exports are readable files, not encrypted backups.", style = MaterialTheme.typography.bodySmall, color = Ink) }
                }
            }
        }
    }
    editor?.let { current ->
        EntryEditor(current, accounts.filterNot { it.archived && it.id != current.accountId }, visible,
            vm.busy, onDismiss = { editor = null }, onSave = { updated -> vm.action(success = { editor = null }) { vm.repo.save(updated) } },
            onDelete = { vm.action(success = { editor = null; vm.message = "Entry deleted. Deletion is queued for sync." }) { vm.repo.save(current.copy(deleted = true)) } },
            receipt = { if (visible.any { it.id == current.id }) ReceiptPanel(current.id, vm) })
    }
    accountEditor?.let { account -> AccountEditor(account, vm.busy, { accountEditor = null }, {
        accountFilter = account.id; categoryFilter = ""; accountEditor = null; tab = 1
    }) { updated ->
        vm.action(success = { accountEditor = null }) { vm.repo.account(updated) }
    } }
    budgetEditor?.let { budget -> BudgetEditor(budget, vm.busy, { budgetEditor = null }, { updated ->
        vm.action(success = { budgetEditor = null }) { vm.repo.db.withTransaction {
            if (budget.category.isNotEmpty() && budget.category != updated.category) vm.repo.dao.deleteBudget(budget)
            vm.repo.dao.putBudget(updated)
        } }
    }, { vm.action(success = { budgetEditor = null }) { vm.repo.dao.deleteBudget(budget) } }) }
    if (showScan) AlertDialog(onDismissRequest = { showScan = false }, title = { Text("Discover SMS accounts") }, text = {
        Text("Choose a range to discover accounts and preview parsed alerts. No transactions are imported until you confirm your selection.")
    }, confirmButton = { TextButton(onClick = { showScan = false; vm.discover(365) }) { Text("Past year") } }, dismissButton = { TextButton(onClick = { showScan = false; vm.discover(30) }) { Text("Past 30 days") } })
    vm.discovery?.let { discovery -> SmsSelectionDialog(discovery, tracked, vm.busy, { vm.discovery = null }) { selectedAccounts ->
        vm.action(success = { vm.discovery = null; vm.scan(discovery.days) }) {
            vm.repo.db.withTransaction {
                discovery.accounts.forEach { vm.repo.setSmsTracking(it.account.id, it.account.id in selectedAccounts) }
            }
        }
    } }
    if (showSmsAccounts) SmsTrackingDialog(accounts, tracked, vm.busy, { showSmsAccounts = false }) { id, enabled ->
        vm.action { vm.repo.setSmsTracking(id, enabled) }
    }
    if (showSmsReview) SmsReviewDialog(held, accounts, vm.busy, { showSmsReview = false }) { key, account ->
        vm.action { vm.repo.reviewSms(key, account) }
    }
    if (showAi) AiSettingsDialog(vm.repo, smsSettings) { showAi = false }
    if (showPairing) PairingDialog(vm.busy, { showPairing = false }) { json -> vm.action(success = { showPairing = false; vm.repo.scheduleSync(force = true) }) {
        val pairing = Pairing.parse(json)
        vm.repo.vault.write("pairing", pairing.json().toByteArray())
        vm.repo.syncState("Paired. Waiting to send changes.")
    } }
    importRows?.let { rows -> ImportDialog(rows, accounts.filterNot { it.archived }, vm.busy, { importRows = null }) { prepared ->
        var count = 0
        vm.action(success = { importRows = null; vm.message = "$count entries imported. Existing exact matches were skipped; review imported entries." }) {
            count = vm.repo.importEntries(prepared)
        }
    } }
    backupMode?.let { mode -> BackupPasswordDialog(mode == "restore", { backupMode = null }) { password ->
        backupMode = null; backupPassword = password
        if (mode == "restore") restoreBackup.launch(arrayOf("*/*")) else saveBackup.launch("rupee-ledger-${LocalDate.now(India)}.ledgerbackup")
    } }
    if (showRule) RuleDialog({ showRule = false }) { rule -> vm.action(success = { showRule = false }) { vm.repo.dao.putRule(rule) } }
    if (showTrash) AlertDialog(onDismissRequest = { showTrash = false }, title = { Text("Deleted transactions") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entries.none { it.deleted }) Text("No deleted entries.")
            entries.filter { it.deleted }.forEach { entry -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${entry.merchant} · ${Money.text(entry.amount)}", Modifier.weight(1f))
                TextButton(enabled = !vm.busy, onClick = { vm.action { vm.repo.save(entry.copy(deleted = false)) } }) { Text("Restore") }
            } }
        }
    }, confirmButton = { TextButton(onClick = { showTrash = false }) { Text("Done") } })
    if (showBulk) {
        var category by remember { mutableStateOf(Categories.first()) }
        AlertDialog(onDismissRequest = { showBulk = false }, title = { Text("Categorise ${selected.size} entries") }, text = {
            Picker("Category", category, Categories.map { it to it }) { category = it }
        }, confirmButton = { TextButton(enabled = !vm.busy, onClick = {
            vm.action(success = { showBulk = false; selected = emptySet() }) {
                vm.repo.db.withTransaction { entries.filter { it.id in selected && !it.deleted }.forEach { vm.repo.save(it.copy(category = category, reviewed = true)) } }
            }
        }) { Text("Apply") } }, dismissButton = { TextButton(onClick = { showBulk = false }) { Text("Cancel") } })
    }
    if (showRange) DateRangeDialog(rangeStart.ifBlank { month.atDay(1).toString() }, rangeEnd.ifBlank { month.atEndOfMonth().toString() }, { showRange = false }) { start, end ->
        rangeStart = start; rangeEnd = end; showRange = false
    }
    if (showFilters) ActivityFilterDialog(advanced, (Categories + visible.map { it.category }).distinct(), { showFilters = false }) { advanced = it; showFilters = false }
    if (comparing) {
        val pair = visible.filter { it.id in selected }
        if (pair.size == 2) CompareDialog(pair, vm.busy, { comparing = false }) { keep, remove ->
            vm.action(success = { comparing = false; selected = emptySet() }) {
                vm.repo.db.withTransaction {
                    require(keep.amount == remove.amount) { "Only equal-amount entries can be marked duplicates here" }
                    vm.repo.save(remove.copy(deleted = true, linkedId = keep.id))
                }
            }
        }
    }
    if (mergingAccounts) AccountMergeDialog(accounts, entries, vm.busy, { mergingAccounts = false }) { from, to ->
        vm.action(success = { mergingAccounts = false }) { vm.repo.mergeAccounts(from, to) }
    }
}

@Composable fun SectionLabel(text: String) { Text(text, letterSpacing = 2.sp, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Clay, modifier = Modifier.padding(top = 8.dp)) }
@Composable fun SurfaceCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), content = content)
    }
}
@Composable fun EmptyCard(title: String, body: String) { SurfaceCard { Text(title, style = MaterialTheme.typography.titleMedium, color = Ink); Text(body, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium) } }
@Composable fun EntryRow(entry: Entry, accounts: List<Account>, onClick: () -> Unit) {
    SurfaceCard(onClick) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = if (entry.kind == Kind.INCOME.name) Color(0xFFE0EBDD) else Color(0xFFF3E8DF)) {
                Text(entry.merchant.take(1).uppercase(), Modifier.padding(horizontal = 14.dp, vertical = 12.dp), fontWeight = FontWeight.Bold, color = Ink)
            }
            Column(Modifier.weight(1f)) {
                Text(entry.merchant, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text("${entry.category} · ${entry.date().format(DateTimeFormatter.ofPattern("d MMM"))}", style = MaterialTheme.typography.bodySmall)
                Text(accounts.find { it.id == entry.accountId }?.name ?: "Unknown account", style = MaterialTheme.typography.labelSmall, color = Ink)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Money.text(entry.amount), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (!entry.reviewed) Text("Review", color = Clay, fontSize = 11.sp)
                if (entry.excluded || entry.status != Status.POSTED.name || entry.kind !in listOf(Kind.EXPENSE.name, Kind.INCOME.name, Kind.REFUND.name))
                    Text(if (entry.excluded) "Excluded" else if (entry.status != Status.POSTED.name) entry.status.lowercase() else Kind.valueOf(entry.kind).label, fontSize = 10.sp, color = Clay)
            }
        }
    }
}

@Composable fun Picker(label: String, value: String, choices: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${choices.find { it.first == value }?.second ?: value}", maxLines = 2, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded, { expanded = false }) { choices.forEach { (key, text) ->
            DropdownMenuItem(text = { Text(text) }, onClick = { onChange(key); expanded = false })
        } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EntryEditor(entry: Entry, accounts: List<Account>, entries: List<Entry>, busy: Boolean, onDismiss: () -> Unit, onSave: (Entry) -> Unit, onDelete: () -> Unit, receipt: @Composable () -> Unit = {}) {
    var amount by remember(entry.id) { mutableStateOf(if (entry.amount > 0) Money.decimal(entry.amount) else "") }
    var merchant by remember(entry.id) { mutableStateOf(entry.merchant) }
    var category by remember(entry.id) { mutableStateOf(entry.category) }
    var kind by remember(entry.id) { mutableStateOf(entry.kind) }
    var status by remember(entry.id) { mutableStateOf(entry.status) }
    var account by remember(entry.id) { mutableStateOf(entry.accountId) }
    var date by remember(entry.id) { mutableStateOf(entry.date().toString()) }
    var tags by remember(entry.id) { mutableStateOf(entry.tags) }
    var note by remember(entry.id) { mutableStateOf(entry.note) }
    var excluded by remember(entry.id) { mutableStateOf(entry.excluded) }
    var linked by remember(entry.id) { mutableStateOf(entry.linkedId.orEmpty()) }
    var direction by remember(entry.id) { mutableStateOf(entry.direction) }
    var personal by remember(entry.id) { mutableStateOf(entry.personalMinor?.let(Money::decimal).orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (entry.amount == 0L) "Add a transaction" else "Transaction details", style = MaterialTheme.typography.headlineSmall, color = Ink)
            Text("${entry.source.uppercase()} · ${if (entry.reviewed) "Reviewed" else "Needs review"} · revision ${entry.revision}", style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Amount in rupees") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(merchant, { merchant = it }, Modifier.fillMaxWidth(), label = { Text("Merchant or description") }, singleLine = true)
            Picker("Account", account, accounts.map { it.id to it.name }) { account = it }
            Picker("Type", kind, Kind.entries.map { it.name to it.label }) {
                kind = it
                direction = if (it in listOf(Kind.INCOME.name, Kind.REFUND.name, Kind.REIMBURSEMENT.name)) "in" else "out"
            }
            Picker("Direction", direction, listOf("out" to "Outgoing", "in" to "Incoming")) { direction = it }
            Picker("Status", status, Status.entries.map { it.name to it.name.lowercase().replaceFirstChar(Char::uppercase) }) { status = it }
            OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), label = { Text("Category · editable") }, singleLine = true)
            Picker("Suggested category", category, Categories.map { it to it }) { category = it }
            OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text("Date · YYYY-MM-DD") }, singleLine = true)
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("Tags · comma separated") })
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Private note") })
            entry.personalMinor?.let { Text("Personal share: ${Money.text(it)}. Change the allocation from the linked private group.", color = Clay) }
            if (kind == Kind.REFUND.name) {
                OutlinedTextField(personal, { personal = it }, Modifier.fillMaxWidth(), label = { Text("Your refund share · optional") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("For a shared purchase, enter only your share of the refund. Blank counts the full refund; the bank movement always keeps the full amount.", style = MaterialTheme.typography.bodySmall)
            }
            receipt()
            if (kind in listOf(Kind.TRANSFER.name, Kind.CARD_PAYMENT.name, Kind.REFUND.name, Kind.REIMBURSEMENT.name))
                Picker("Related entry", linked, listOf("" to "None / unmatched") + entries.filter { it.id != entry.id }.take(100).map { it.id to "${it.merchant} · ${Money.text(it.amount)} · ${it.date()}" }) { linked = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(excluded, { excluded = it }); Text("Exclude from spending and income totals") }
            Text("Transfers, investments and card repayments do not count as personal spending. Linking preserves the original entries; set their types correctly.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy && accounts.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                runCatching {
                    val parsedDate = LocalDate.parse(date)
                    require(!parsedDate.isAfter(LocalDate.now(India))) { "Choose today or an earlier date" }
                    require(merchant.isNotBlank()) { "Enter a merchant or description" }
                    require(category.isNotBlank()) { "Enter a category" }
                    val time = if (parsedDate == entry.date()) entry.time else parsedDate.atStartOfDay(India).toInstant().toEpochMilli()
                    entry.copy(amount = Money.parse(amount), merchant = merchant.trim(), category = category.trim(), kind = kind,
                        status = status, accountId = account, time = time, tags = tags, note = note, excluded = excluded,
                        reviewed = true, linkedId = linked.takeIf { it.isNotBlank() }, direction = direction,
                        personalMinor = if (kind == Kind.REFUND.name) personal.takeIf(String::isNotBlank)?.let(Money::signed) else entry.personalMinor)
                }.onSuccess(onSave).onFailure { error = it.message ?: "Check the amount and date" }
            }) { Text("Save & mark reviewed") }
            if (entries.any { it.id == entry.id }) TextButton(onClick = { deleting = true }, Modifier.fillMaxWidth()) { Text("Delete transaction", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete this transaction?") },
        text = { Text("It will leave your totals and the synced ledger. Rescanning the same alert will not recreate it.") },
        confirmButton = { TextButton(enabled = !busy, onClick = onDelete) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep") } })
}

@Composable fun AccountEditor(account: Account, busy: Boolean, dismiss: () -> Unit, history: () -> Unit, save: (Account) -> Unit) {
    var name by remember { mutableStateOf(account.name) }; var type by remember { mutableStateOf(account.type) }
    var repoId by remember { mutableStateOf(account.repoId) }; var archived by remember { mutableStateOf(account.archived) }
    var opening by remember { mutableStateOf(account.openingMinor?.let(Money::decimal).orEmpty()) }
    var observed by remember { mutableStateOf(account.observedMinor?.let(Money::decimal).orEmpty()) }
    var openingDate by remember { mutableStateOf(account.openingAt?.let { Instant.ofEpochMilli(it).atZone(India).toLocalDate().toString() } ?: LocalDate.now(India).toString()) }
    var observedDate by remember { mutableStateOf(account.observedAt?.let { Instant.ofEpochMilli(it).atZone(India).toLocalDate().toString() } ?: LocalDate.now(India).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Account settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            Picker("Type", type, listOf("Bank", "Card", "Cash", "Wallet", "Meal card", "Other").map { it to it }) { type = it }
            OutlinedTextField(repoId, { repoId = it }, label = { Text("Repo account ID") }, supportingText = { Text("Use the account_id from your personal-finance repo. Required for sync.") }, singleLine = true)
            OutlinedTextField(opening, { opening = it }, label = { Text("Opening balance · optional") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(openingDate, { openingDate = it }, label = { Text("Opening date · YYYY-MM-DD") })
            Text("Opening balance is before transactions on that date. For cards, positive means owed. Calculated balances assume no missing transactions.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(observed, { observed = it }, label = { Text("Observed statement balance · optional") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(observedDate, { observedDate = it }, label = { Text("Observed date · YYYY-MM-DD") })
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(archived, { archived = it }); Text("Archive account") }
            TextButton(onClick = history) { Text("View account activity") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = {
        runCatching {
            val from = LocalDate.parse(openingDate); val asOf = LocalDate.parse(observedDate)
            require(!from.isAfter(LocalDate.now(India)) && !asOf.isAfter(LocalDate.now(India)))
            account.copy(name = name.trim(), type = type, repoId = repoId.trim(), archived = archived,
                openingMinor = opening.takeIf(String::isNotBlank)?.let(Money::signed), openingAt = if (opening.isBlank()) null else from.atStartOfDay(India).toInstant().toEpochMilli(),
                observedMinor = observed.takeIf(String::isNotBlank)?.let(Money::signed), observedAt = if (observed.isBlank()) null else if (asOf == LocalDate.now(India)) System.currentTimeMillis() else asOf.plusDays(1).atStartOfDay(India).toInstant().toEpochMilli() - 1)
        }.onSuccess(save).onFailure { error = "Check balance amounts and dates" }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable fun BudgetEditor(budget: Budget, busy: Boolean, dismiss: () -> Unit, save: (Budget) -> Unit, delete: () -> Unit) {
    var category by remember { mutableStateOf(budget.category.ifBlank { Categories[1] }) }
    var amount by remember { mutableStateOf(if (budget.limitMinor > 0) Money.decimal(budget.limitMinor) else "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Monthly category budget") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Picker("Category", category, Categories.map { it to it }) { category = it }
            OutlinedTextField(amount, { amount = it }, label = { Text("Monthly limit in rupees") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (budget.limitMinor > 0) TextButton(onClick = delete, enabled = !busy) { Text("Remove budget") }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { runCatching { Budget(category, Money.parse(amount)) }.onSuccess(save).onFailure { error = "Enter a valid positive limit" } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable fun PairingDialog(busy: Boolean, dismiss: () -> Unit, pair: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Pair your computer") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Start the desktop receiver, then paste its private pairing.json contents. This includes the receiver address, certificate fingerprint and revocable secret. Never share it.")
            OutlinedTextField(text, { text = it }, label = { Text("Pairing configuration") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("The computer must be reachable over your private network. No GitHub credentials are stored on this phone.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = !busy && text.isNotBlank(), onClick = { pair(text) }) { Text("Pair") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
