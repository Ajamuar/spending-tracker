package com.myexpenses.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myexpenses.backup.BackupManager
import com.myexpenses.data.AppPreferences
import com.myexpenses.data.ExpenseRepository
import com.myexpenses.data.IngestionSettings
import com.myexpenses.data.model.AccountEntity
import com.myexpenses.data.model.BudgetEntity
import com.myexpenses.data.model.CategoryEntity
import com.myexpenses.data.model.IngestionRecordEntity
import com.myexpenses.data.model.TransactionEntity
import com.myexpenses.data.model.TransactionStatus
import com.myexpenses.data.model.TransactionType
import com.myexpenses.ingestion.email.GmailSyncScheduler
import com.myexpenses.ingestion.email.GmailSyncService
import com.myexpenses.ingestion.sms.SmsScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class ExpenseUiState(
    val transactions: List<TransactionEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList(),
    val recentIngestion: List<IngestionRecordEntity> = emptyList(),
    val settings: IngestionSettings = IngestionSettings(),
    val month: YearMonth = YearMonth.now(),
    val searchQuery: String = "",
    val smsPermissionGranted: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val monthTransactions: List<TransactionEntity>
        get() = transactions.filter {
            YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault())) == month
        }

    val visibleTransactions: List<TransactionEntity>
        get() {
            val query = searchQuery.trim()
            return monthTransactions.filter {
                query.isBlank() ||
                    it.merchant.contains(query, ignoreCase = true) ||
                    it.note.contains(query, ignoreCase = true) ||
                    it.accountHint.orEmpty().contains(query, ignoreCase = true)
            }
        }

    val spendingMinor: Long
        get() = monthTransactions.filter {
            !it.isExcluded && it.status == TransactionStatus.POSTED &&
                (it.type == TransactionType.EXPENSE || it.type == TransactionType.CARD_PAYMENT)
        }.sumOf(TransactionEntity::amountMinor)

    val incomeMinor: Long
        get() = monthTransactions.filter {
            !it.isExcluded && it.status == TransactionStatus.POSTED &&
                (it.type == TransactionType.INCOME || it.type == TransactionType.REFUND)
        }.sumOf(TransactionEntity::amountMinor)
}

private data class BaseLedgerState(
    val transactions: List<TransactionEntity>,
    val categories: List<CategoryEntity>,
    val accounts: List<AccountEntity>,
    val settings: IngestionSettings,
)

private data class OperationState(
    val searchQuery: String = "",
    val smsPermissionGranted: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ExpenseRepository,
    private val preferences: AppPreferences,
    private val smsScanner: SmsScanner,
    private val gmailSyncService: GmailSyncService,
    private val gmailSyncScheduler: GmailSyncScheduler,
    private val backupManager: BackupManager,
) : ViewModel() {
    private val month = MutableStateFlow(YearMonth.now())
    private val operation = MutableStateFlow(
        OperationState(smsPermissionGranted = smsScanner.hasAutomaticPermissions()),
    )

    private val baseState = combine(
        repository.transactions,
        repository.categories,
        repository.accounts,
        preferences.settings,
    ) { transactions, categories, accounts, settings ->
        BaseLedgerState(transactions, categories, accounts, settings)
    }

    private val monthBudgets = month.flatMapLatest(repository::budgets)

    val uiState = combine(
        baseState,
        month,
        monthBudgets,
        repository.recentIngestion,
        operation,
    ) { base, selectedMonth, budgets, recent, op ->
        ExpenseUiState(
            transactions = base.transactions,
            categories = base.categories,
            accounts = base.accounts,
            budgets = budgets,
            recentIngestion = recent,
            settings = base.settings,
            month = selectedMonth,
            searchQuery = op.searchQuery,
            smsPermissionGranted = op.smsPermissionGranted,
            busy = op.busy,
            message = op.message,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ExpenseUiState(smsPermissionGranted = smsScanner.hasAutomaticPermissions()),
    )

    fun completeOnboarding() = preferences.completeOnboarding()

    fun previousMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun nextMonth() {
        if (month.value < YearMonth.now()) month.value = month.value.plusMonths(1)
    }

    fun setSearchQuery(query: String) {
        operation.value = operation.value.copy(searchQuery = query)
    }

    fun onSmsPermissionResult(granted: Boolean) {
        operation.value = operation.value.copy(smsPermissionGranted = granted)
        if (granted) scanSms()
        else showMessage("SMS permission was not granted. Manual tracking still works.")
    }

    fun scanSms() = launchOperation {
        val summary = smsScanner.scanInbox()
        "SMS scan finished: ${summary.imported} new, ${summary.duplicates} matched"
    }

    fun onGmailAuthorized(accessToken: String) = launchOperation {
        val summary = gmailSyncService.syncWithToken(accessToken)
        gmailSyncScheduler.ensureScheduled()
        "Gmail sync finished: ${summary.imported} new, ${summary.duplicates} matched"
    }

    fun onGmailAuthorizationFailed(message: String) = showMessage(message)

    fun refreshSmsPermission() {
        operation.value = operation.value.copy(
            smsPermissionGranted = smsScanner.hasAutomaticPermissions(),
        )
    }

    fun disconnectGmail() {
        preferences.setGmailConnection(false)
        gmailSyncScheduler.cancel()
        showMessage("Gmail disconnected. Imported transactions remain on this device.")
    }

    fun setGmailQuery(query: String) {
        preferences.setGmailQuery(query)
        showMessage("Gmail filter saved")
    }

    fun addManual(
        amount: String,
        type: TransactionType,
        merchant: String,
        categoryId: String?,
        accountId: String?,
        note: String,
    ) = launchOperation {
        val amountMinor = amount.toBigDecimalOrNull()
            ?.movePointRight(2)
            ?.setScale(0, RoundingMode.HALF_UP)
            ?.longValueExact()
            ?: throw IllegalArgumentException("Enter a valid amount")
        repository.addManual(
            amountMinor = amountMinor,
            type = type,
            merchant = merchant,
            categoryId = categoryId,
            accountId = accountId,
            occurredAt = System.currentTimeMillis(),
            note = note,
        )
        "Transaction added"
    }

    fun updateTransaction(
        transaction: TransactionEntity,
        merchant: String,
        categoryId: String?,
        note: String,
        excluded: Boolean,
        status: TransactionStatus,
    ) = launchOperation {
        repository.updateTransaction(
            transaction.id,
            merchant,
            categoryId,
            note,
            excluded,
            status,
        )
        "Transaction updated"
    }

    fun deleteTransaction(id: String) = launchOperation {
        repository.deleteTransaction(id)
        "Transaction deleted"
    }

    fun setBudget(categoryId: String, amount: String) = launchOperation {
        val amountMinor = amount.toBigDecimalOrNull()
            ?.movePointRight(2)
            ?.setScale(0, RoundingMode.HALF_UP)
            ?.longValueExact()
            ?: 0L
        repository.setBudget(categoryId, month.value, amountMinor)
        if (amountMinor > 0) "Budget saved" else "Budget removed"
    }

    fun exportCsv(uri: Uri) = launchOperation {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            backupManager.exportCsv(it)
        } ?: error("Unable to open the selected file")
        "CSV exported"
    }

    fun importCsv(uri: Uri) = launchOperation {
        val count = context.contentResolver.openInputStream(uri)?.use {
            backupManager.importCsv(it)
        } ?: error("Unable to open the selected file")
        "$count transactions imported from CSV"
    }

    fun exportEncryptedBackup(uri: Uri, passphrase: CharArray) = launchOperation(
        finally = { passphrase.fill('\u0000') },
    ) {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            backupManager.exportEncrypted(it, passphrase)
        } ?: error("Unable to open the selected file")
        "Encrypted backup created"
    }

    fun restoreEncryptedBackup(uri: Uri, passphrase: CharArray) = launchOperation(
        finally = { passphrase.fill('\u0000') },
    ) {
        context.contentResolver.openInputStream(uri)?.use {
            backupManager.restoreEncrypted(it, passphrase)
        } ?: error("Unable to open the selected file")
        "Encrypted backup restored"
    }

    fun consumeMessage() {
        operation.value = operation.value.copy(message = null)
    }

    private fun launchOperation(
        finally: () -> Unit = {},
        block: suspend () -> String,
    ) {
        viewModelScope.launch {
            operation.value = operation.value.copy(busy = true, message = null)
            try {
                showMessage(block())
            } catch (error: Exception) {
                showMessage(error.message ?: "The operation failed safely")
            } finally {
                finally()
                operation.value = operation.value.copy(busy = false)
            }
        }
    }

    private fun showMessage(message: String) {
        operation.value = operation.value.copy(message = message)
    }
}
