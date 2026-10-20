package com.myexpenses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myexpenses.data.model.BudgetEntity
import com.myexpenses.data.model.CategoryEntity
import com.myexpenses.data.model.IngestionOutcome
import com.myexpenses.data.model.SourceType
import com.myexpenses.data.model.TransactionEntity
import com.myexpenses.data.model.TransactionStatus
import com.myexpenses.data.model.TransactionType
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private enum class AppScreen(val label: String, val icon: ImageVector) {
    DASHBOARD("Overview", Icons.Outlined.Home),
    TRANSACTIONS("Activity", Icons.Outlined.ReceiptLong),
    BUDGETS("Budgets", Icons.Outlined.AccountBalanceWallet),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

private enum class BackupAction {
    EXPORT,
    RESTORE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyExpensesApp(
    state: ExpenseUiState,
    onCompleteOnboarding: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    onScanSms: () -> Unit,
    onRequestGmail: () -> Unit,
    onDisconnectGmail: () -> Unit,
    onSaveGmailQuery: (String) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSearch: (String) -> Unit,
    onAddManual: (String, TransactionType, String, String?, String?, String) -> Unit,
    onUpdateTransaction: (TransactionEntity, String, String?, String, Boolean, TransactionStatus) -> Unit,
    onDeleteTransaction: (String) -> Unit,
    onSetBudget: (String, String) -> Unit,
    onExportCsv: () -> Unit,
    onImportCsv: () -> Unit,
    onExportBackup: (CharArray) -> Unit,
    onRestoreBackup: (CharArray) -> Unit,
    onMessageConsumed: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.DASHBOARD) }
    var showManualEntry by rememberSaveable { mutableStateOf(false) }
    var editingTransactionId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingBudgetCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var backupAction by rememberSaveable { mutableStateOf<BackupAction?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            onMessageConsumed()
        }
    }

    if (!state.settings.onboardingComplete) {
        OnboardingScreen(
            onEnableSms = {
                onCompleteOnboarding()
                onRequestSmsPermission()
            },
            onSkip = onCompleteOnboarding,
        )
        return
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("My Expenses", style = MaterialTheme.typography.titleLarge)
                            Text(
                                currentScreen.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    AppScreen.entries.forEach { screen ->
                        NavigationBarItem(
                            selected = currentScreen == screen,
                            onClick = { currentScreen = screen },
                            icon = { Icon(screen.icon, contentDescription = null) },
                            label = { Text(screen.label) },
                        )
                    }
                }
            },
            floatingActionButton = {
                if (currentScreen == AppScreen.DASHBOARD ||
                    currentScreen == AppScreen.TRANSACTIONS
                ) {
                    ExtendedFloatingActionButton(
                        onClick = { showManualEntry = true },
                        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        text = { Text("Add expense") },
                    )
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                when (currentScreen) {
                    AppScreen.DASHBOARD -> DashboardScreen(
                        state = state,
                        onPreviousMonth = onPreviousMonth,
                        onNextMonth = onNextMonth,
                        onTransactionClick = { editingTransactionId = it.id },
                    )
                    AppScreen.TRANSACTIONS -> TransactionsScreen(
                        state = state,
                        onPreviousMonth = onPreviousMonth,
                        onNextMonth = onNextMonth,
                        onSearch = onSearch,
                        onTransactionClick = { editingTransactionId = it.id },
                    )
                    AppScreen.BUDGETS -> BudgetsScreen(
                        state = state,
                        onPreviousMonth = onPreviousMonth,
                        onNextMonth = onNextMonth,
                        onBudgetClick = { editingBudgetCategoryId = it.id },
                    )
                    AppScreen.SETTINGS -> SettingsScreen(
                        state = state,
                        onRequestSmsPermission = onRequestSmsPermission,
                        onScanSms = onScanSms,
                        onRequestGmail = onRequestGmail,
                        onDisconnectGmail = onDisconnectGmail,
                        onSaveGmailQuery = onSaveGmailQuery,
                        onExportCsv = onExportCsv,
                        onImportCsv = onImportCsv,
                        onExportBackup = { backupAction = BackupAction.EXPORT },
                        onRestoreBackup = { backupAction = BackupAction.RESTORE },
                    )
                }
                if (state.busy) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(shape = CircleShape, tonalElevation = 8.dp) {
                            CircularProgressIndicator(Modifier.padding(18.dp))
                        }
                    }
                }
            }
        }
    }

    if (showManualEntry) {
        ManualTransactionDialog(
            categories = state.categories,
            accounts = state.accounts.map { it.id to it.name },
            onDismiss = { showManualEntry = false },
            onSave = { amount, type, merchant, categoryId, accountId, note ->
                onAddManual(amount, type, merchant, categoryId, accountId, note)
                showManualEntry = false
            },
        )
    }

    editingTransactionId?.let { id ->
        state.transactions.firstOrNull { it.id == id }?.let { transaction ->
            EditTransactionDialog(
                transaction = transaction,
                categories = state.categories,
                onDismiss = { editingTransactionId = null },
                onSave = { merchant, categoryId, note, excluded, status ->
                    onUpdateTransaction(
                        transaction,
                        merchant,
                        categoryId,
                        note,
                        excluded,
                        status,
                    )
                    editingTransactionId = null
                },
                onDelete = {
                    onDeleteTransaction(transaction.id)
                    editingTransactionId = null
                },
            )
        }
    }

    editingBudgetCategoryId?.let { categoryId ->
        val category = state.categories.firstOrNull { it.id == categoryId }
        if (category != null) {
            val budget = state.budgets.firstOrNull { it.categoryId == categoryId }
            BudgetDialog(
                category = category,
                currentBudget = budget,
                onDismiss = { editingBudgetCategoryId = null },
                onSave = {
                    onSetBudget(categoryId, it)
                    editingBudgetCategoryId = null
                },
            )
        }
    }

    backupAction?.let { action ->
        PassphraseDialog(
            title = if (action == BackupAction.EXPORT) {
                "Protect your backup"
            } else {
                "Unlock your backup"
            },
            confirmLabel = if (action == BackupAction.EXPORT) "Create backup" else "Restore",
            onDismiss = { backupAction = null },
            onConfirm = {
                if (action == BackupAction.EXPORT) onExportBackup(it) else onRestoreBackup(it)
                backupAction = null
            },
        )
    }
}

@Composable
private fun OnboardingScreen(
    onEnableSms: () -> Unit,
    onSkip: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .padding(28.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    Icons.Outlined.AccountBalanceWallet,
                    contentDescription = null,
                    modifier = Modifier.padding(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text("Your spending. Your device.", style = MaterialTheme.typography.headlineLarge)
            Text(
                "My Expenses turns transaction alerts into a private ledger. " +
                    "Raw SMS and email bodies are parsed in memory and are never stored.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrivacyPoint(Icons.Outlined.Sms, "Read transaction SMS", "Personal messages and OTPs are ignored.")
            PrivacyPoint(Icons.Outlined.Lock, "Encrypted locally", "The ledger key is protected by Android Keystore.")
            PrivacyPoint(Icons.Outlined.FileDownload, "Always portable", "Export CSV or an encrypted backup whenever you want.")
            Button(onClick = onEnableSms, modifier = Modifier.fillMaxWidth()) {
                Text("Enable automatic SMS tracking")
            }
            TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Start with manual entries")
            }
        }
    }
}

@Composable
private fun PrivacyPoint(icon: ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DashboardScreen(
    state: ExpenseUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onTransactionClick: (TransactionEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { MonthSelector(state.month, onPreviousMonth, onNextMonth) }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("Spent this month", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        formatMoney(state.spendingMinor),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            label = { Text("${state.monthTransactions.size} transactions") },
                            leadingIcon = { Icon(Icons.Outlined.ReceiptLong, null) },
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("${formatMoney(state.incomeMinor)} in") },
                        )
                    }
                }
            }
        }
        item {
            SectionTitle("Where it went", "Posted expenses only")
        }
        val categoryTotals = categoryTotals(state)
        if (categoryTotals.isEmpty()) {
            item {
                EmptyCard(
                    title = "No spending yet",
                    detail = "Add a transaction or scan your SMS inbox to start this month.",
                )
            }
        } else {
            items(categoryTotals.take(5), key = { it.first.id }) { (category, total) ->
                CategoryProgress(
                    category = category,
                    total = total,
                    maximum = categoryTotals.maxOf { it.second }.coerceAtLeast(1),
                )
            }
        }
        item { SectionTitle("Recent activity", "Tap a transaction to correct it") }
        if (state.monthTransactions.isEmpty()) {
            item {
                EmptyCard("A clean slate", "Your imported and manual transactions will appear here.")
            }
        } else {
            items(state.monthTransactions.take(6), key = TransactionEntity::id) {
                TransactionRow(
                    transaction = it,
                    category = state.categories.firstOrNull { category -> category.id == it.categoryId },
                    onClick = { onTransactionClick(it) },
                )
            }
        }
    }
}

@Composable
private fun TransactionsScreen(
    state: ExpenseUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSearch: (String) -> Unit,
    onTransactionClick: (TransactionEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MonthSelector(state.month, onPreviousMonth, onNextMonth) }
        item {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearch,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search merchant, note or account") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
            )
        }
        if (state.visibleTransactions.isEmpty()) {
            item {
                EmptyCard(
                    if (state.searchQuery.isBlank()) "No transactions" else "No matches",
                    if (state.searchQuery.isBlank()) {
                        "Imported SMS, Gmail and manual entries will show here."
                    } else {
                        "Try a different merchant or account search."
                    },
                )
            }
        } else {
            items(state.visibleTransactions, key = TransactionEntity::id) { transaction ->
                TransactionRow(
                    transaction = transaction,
                    category = state.categories.firstOrNull { it.id == transaction.categoryId },
                    onClick = { onTransactionClick(transaction) },
                )
            }
        }
    }
}

@Composable
private fun BudgetsScreen(
    state: ExpenseUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onBudgetClick: (CategoryEntity) -> Unit,
) {
    val budgetable = state.categories.filter {
        !it.isIncome && it.name !in setOf("Refunds", "Transfers", "Card payment")
    }
    LazyColumn(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MonthSelector(state.month, onPreviousMonth, onNextMonth) }
        item {
            Text(
                "Set a limit for the categories you want to watch. " +
                    "A zero or empty amount removes the limit.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(budgetable, key = CategoryEntity::id) { category ->
            val spent = spentForCategory(state, category.id)
            val budget = state.budgets.firstOrNull { it.categoryId == category.id }
            BudgetRow(category, spent, budget, onClick = { onBudgetClick(category) })
        }
    }
}

@Composable
private fun SettingsScreen(
    state: ExpenseUiState,
    onRequestSmsPermission: () -> Unit,
    onScanSms: () -> Unit,
    onRequestGmail: () -> Unit,
    onDisconnectGmail: () -> Unit,
    onSaveGmailQuery: (String) -> Unit,
    onExportCsv: () -> Unit,
    onImportCsv: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
) {
    var gmailQuery by rememberSaveable(state.settings.gmailQuery) {
        mutableStateOf(state.settings.gmailQuery)
    }
    LazyColumn(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SectionTitle("Automatic sources", "Nothing is uploaded to a My Expenses server") }
        item {
            SourceCard(
                icon = Icons.Outlined.Sms,
                title = "Transaction SMS",
                status = if (state.smsPermissionGranted) "Ready" else "Permission needed",
                enabled = state.smsPermissionGranted,
                actionLabel = if (state.smsPermissionGranted) "Rescan inbox" else "Grant access",
                onAction = if (state.smsPermissionGranted) onScanSms else onRequestSmsPermission,
            )
        }
        item {
            SourceCard(
                icon = Icons.Outlined.Email,
                title = "Gmail",
                status = if (state.settings.gmailConnected) {
                    state.settings.gmailAccount ?: "Connected"
                } else {
                    "Not connected"
                },
                enabled = state.settings.gmailConnected,
                actionLabel = if (state.settings.gmailConnected) "Sync now" else "Connect Gmail",
                onAction = onRequestGmail,
                secondaryLabel = if (state.settings.gmailConnected) "Disconnect" else null,
                onSecondary = onDisconnectGmail,
            )
        }
        item {
            OutlinedTextField(
                value = gmailQuery,
                onValueChange = { gmailQuery = it },
                label = { Text("Gmail search filter") },
                supportingText = {
                    Text("Use Gmail search syntax. Add from:bank.example to create an allowlist.")
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        }
        item {
            OutlinedButton(
                onClick = { onSaveGmailQuery(gmailQuery) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save Gmail filter")
            }
        }
        state.settings.lastStatus?.let { status ->
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(16.dp),
                        )
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.Info, null)
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            SectionTitle(
                "Your data",
                "CSV is plaintext; use an encrypted backup for private storage.",
            )
        }
        item {
            ActionGridRow(
                first = Triple(Icons.Outlined.FileDownload, "Export CSV", onExportCsv),
                second = Triple(Icons.Outlined.FileUpload, "Import CSV", onImportCsv),
            )
        }
        item {
            ActionGridRow(
                first = Triple(Icons.Outlined.Lock, "Encrypted backup", onExportBackup),
                second = Triple(Icons.Outlined.Refresh, "Restore backup", onRestoreBackup),
            )
        }
        item { SectionTitle("Privacy design", null) }
        item {
            OutlinedCard(shape = RoundedCornerShape(20.dp)) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Local by default", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The ledger database is encrypted. Raw SMS and email content is discarded " +
                            "after a normalized transaction is extracted. No analytics, ads, bank " +
                            "passwords, UPI PINs or OTPs are collected.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.recentIngestion.isNotEmpty()) {
            item { SectionTitle("Recent imports", "Only outcomes and fingerprints are retained") }
            items(state.recentIngestion.take(8), key = { it.id }) { record ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (record.outcome == IngestionOutcome.IMPORTED) {
                            Icons.Outlined.CheckCircle
                        } else {
                            Icons.Outlined.Info
                        },
                        null,
                        tint = if (record.outcome == IngestionOutcome.IMPORTED) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(record.detail ?: record.outcome.name.lowercase().replaceFirstChar(Char::uppercase))
                        Text(
                            record.source.name.lowercase().replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthSelector(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "Previous month")
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                month.year.toString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onNext, enabled = month < YearMonth.now()) {
            Icon(Icons.Outlined.ArrowForward, contentDescription = "Next month")
        }
    }
}

@Composable
private fun TransactionRow(
    transaction: TransactionEntity,
    category: CategoryEntity?,
    onClick: () -> Unit,
) {
    val incoming = transaction.type == TransactionType.INCOME ||
        transaction.type == TransactionType.REFUND
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(
                        category?.let { Color(it.color) }?.copy(alpha = 0.16f)
                            ?: MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(category?.icon ?: "•", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    transaction.merchant,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(formatDate(transaction.occurredAt))
                        append(" · ")
                        append(transaction.source.name.lowercase().replaceFirstChar(Char::uppercase))
                        if (transaction.status != TransactionStatus.POSTED) {
                            append(" · ")
                            append(transaction.status.name.lowercase().replaceFirstChar(Char::uppercase))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                (if (incoming) "+" else "−") + formatMoney(transaction.amountMinor),
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    transaction.isExcluded -> MaterialTheme.colorScheme.outline
                    incoming -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun CategoryProgress(category: CategoryEntity, total: Long, maximum: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${category.icon}  ${category.name}", style = MaterialTheme.typography.titleMedium)
            Text(formatMoney(total), fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(
            progress = { (total.toFloat() / maximum).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            color = Color(category.color),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun BudgetRow(
    category: CategoryEntity,
    spent: Long,
    budget: BudgetEntity?,
    onClick: () -> Unit,
) {
    OutlinedCard(onClick = onClick, shape = RoundedCornerShape(20.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${category.icon}  ${category.name}", style = MaterialTheme.typography.titleMedium)
                Text(
                    budget?.let { "${formatMoney(spent)} / ${formatMoney(it.limitMinor)}" }
                        ?: "${formatMoney(spent)} spent",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (budget != null) {
                val ratio = spent.toFloat() / budget.limitMinor.coerceAtLeast(1)
                LinearProgressIndicator(
                    progress = { ratio.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = if (ratio > 1f) MaterialTheme.colorScheme.error else Color(category.color),
                )
                Text(
                    if (ratio > 1f) {
                        "${formatMoney(spent - budget.limitMinor)} over budget"
                    } else {
                        "${formatMoney(budget.limitMinor - spent)} remaining"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ratio > 1f) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            } else {
                Text(
                    "Tap to add a monthly limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    title: String,
    status: String,
    enabled: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    OutlinedCard(shape = RoundedCornerShape(20.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(icon, null, Modifier.padding(10.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = null)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAction, modifier = Modifier.weight(1f)) {
                    Text(actionLabel)
                }
                if (secondaryLabel != null && onSecondary != null) {
                    OutlinedButton(onClick = onSecondary) {
                        Icon(Icons.Outlined.LinkOff, null)
                        Spacer(Modifier.width(6.dp))
                        Text(secondaryLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionGridRow(
    first: Triple<ImageVector, String, () -> Unit>,
    second: Triple<ImageVector, String, () -> Unit>,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(first, second).forEach { (icon, label, action) ->
            OutlinedCard(
                onClick = action,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String?) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyCard(title: String, detail: String) {
    OutlinedCard(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ManualTransactionDialog(
    categories: List<CategoryEntity>,
    accounts: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSave: (String, TransactionType, String, String?, String?, String) -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf("") }
    var merchant by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(TransactionType.EXPENSE) }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var accountId by rememberSaveable { mutableStateOf<String?>(accounts.firstOrNull()?.first) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add transaction") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(TransactionType.EXPENSE, TransactionType.INCOME).forEach {
                            FilterChip(
                                selected = type == it,
                                onClick = {
                                    type = it
                                    categoryId = null
                                },
                                label = { Text(it.name.lowercase().replaceFirstChar(Char::uppercase)) },
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        amount,
                        { amount = it },
                        label = { Text("Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        merchant,
                        { merchant = it },
                        label = { Text(if (type == TransactionType.INCOME) "Source" else "Merchant") },
                        singleLine = true,
                    )
                }
                item {
                    Text("Category", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories.filter { it.isIncome == (type == TransactionType.INCOME) }) {
                            FilterChip(
                                selected = categoryId == it.id,
                                onClick = { categoryId = it.id },
                                label = { Text("${it.icon} ${it.name}") },
                            )
                        }
                    }
                }
                if (accounts.isNotEmpty()) {
                    item {
                        Text("Account", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(accounts) { (id, name) ->
                                FilterChip(
                                    selected = accountId == id,
                                    onClick = { accountId = id },
                                    label = { Text(name) },
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(amount, type, merchant, categoryId, accountId, note) },
                enabled = amount.toBigDecimalOrNull()?.signum() == 1,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditTransactionDialog(
    transaction: TransactionEntity,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (String, String?, String, Boolean, TransactionStatus) -> Unit,
    onDelete: () -> Unit,
) {
    var merchant by rememberSaveable(transaction.id) { mutableStateOf(transaction.merchant) }
    var note by rememberSaveable(transaction.id) { mutableStateOf(transaction.note) }
    var categoryId by rememberSaveable(transaction.id) { mutableStateOf(transaction.categoryId) }
    var excluded by rememberSaveable(transaction.id) { mutableStateOf(transaction.isExcluded) }
    var status by rememberSaveable(transaction.id) { mutableStateOf(transaction.status) }
    var confirmDelete by rememberSaveable(transaction.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review transaction") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(
                        formatMoney(transaction.amountMinor),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        "${transaction.source.name.lowercase().replaceFirstChar(Char::uppercase)} · " +
                            "${(transaction.sourceConfidence * 100).toInt()}% parser confidence",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    OutlinedTextField(
                        merchant,
                        { merchant = it },
                        label = { Text("Merchant") },
                        singleLine = true,
                    )
                }
                item {
                    Text("Category", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories.filter { !it.isIncome }) {
                            FilterChip(
                                selected = categoryId == it.id,
                                onClick = { categoryId = it.id },
                                label = { Text("${it.icon} ${it.name}") },
                            )
                        }
                    }
                }
                item {
                    Text("Status", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(TransactionStatus.entries) {
                            FilterChip(
                                selected = status == it,
                                onClick = { status = it },
                                label = { Text(it.name.lowercase().replaceFirstChar(Char::uppercase)) },
                            )
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Exclude from totals")
                            Text(
                                "Keep it visible without affecting reports",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = excluded, onCheckedChange = { excluded = it })
                    }
                }
                item {
                    OutlinedTextField(note, { note = it }, label = { Text("Note") })
                }
                item {
                    TextButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete permanently")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(merchant, categoryId, note, excluded, status) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?") },
            text = {
                Text(
                    "This transaction will stay tombstoned and will not be imported again " +
                        "from the same SMS or email.",
                )
            },
            confirmButton = {
                Button(onClick = onDelete) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun BudgetDialog(
    category: CategoryEntity,
    currentBudget: BudgetEntity?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var amount by rememberSaveable(category.id, currentBudget?.limitMinor) {
        mutableStateOf(
            currentBudget?.limitMinor?.toBigDecimal()?.movePointLeft(2)?.toPlainString().orEmpty(),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${category.icon} ${category.name}") },
        text = {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Monthly limit (₹)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = { Text("Leave empty or enter 0 to remove this budget.") },
            )
        },
        confirmButton = { Button(onClick = { onSave(amount) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PassphraseDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {
            passphrase = ""
            confirmation = ""
            onDismiss()
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )
                if (confirmLabel == "Create backup") {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Confirm passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                }
                Text(
                    "Use at least 8 characters. This passphrase cannot be recovered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val characters = passphrase.toCharArray()
                    passphrase = ""
                    confirmation = ""
                    onConfirm(characters)
                },
                enabled = passphrase.length >= 8 &&
                    (confirmLabel != "Create backup" || passphrase == confirmation),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    passphrase = ""
                    confirmation = ""
                    onDismiss()
                },
            ) { Text("Cancel") }
        },
    )
}

private fun categoryTotals(state: ExpenseUiState): List<Pair<CategoryEntity, Long>> {
    val categories = state.categories.associateBy(CategoryEntity::id)
    return state.monthTransactions
        .filter {
            !it.isExcluded && it.status == TransactionStatus.POSTED &&
                (it.type == TransactionType.EXPENSE || it.type == TransactionType.CARD_PAYMENT)
        }
        .groupBy(TransactionEntity::categoryId)
        .mapNotNull { (categoryId, transactions) ->
            categories[categoryId]?.let { it to transactions.sumOf(TransactionEntity::amountMinor) }
        }
        .sortedByDescending { it.second }
}

private fun spentForCategory(state: ExpenseUiState, categoryId: String): Long =
    state.monthTransactions.filter {
        it.categoryId == categoryId &&
            !it.isExcluded &&
            it.status == TransactionStatus.POSTED &&
            (it.type == TransactionType.EXPENSE || it.type == TransactionType.CARD_PAYMENT)
    }.sumOf(TransactionEntity::amountMinor)

private fun formatMoney(amountMinor: Long): String =
    NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(amountMinor / 100.0)

private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM, h:mm a"))
