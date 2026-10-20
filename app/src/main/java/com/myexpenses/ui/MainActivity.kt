package com.myexpenses.ui

import android.Manifest
import android.accounts.Account
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.myexpenses.ingestion.email.GMAIL_READONLY_SCOPE
import com.myexpenses.ui.theme.MyExpensesTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val authorizationClient by lazy { Identity.getAuthorizationClient(this) }
    private var pendingBackupPassphrase: CharArray? = null
    private var pendingRestorePassphrase: CharArray? = null

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        viewModel.onSmsPermissionResult(
            grants[Manifest.permission.READ_SMS] == true &&
                grants[Manifest.permission.RECEIVE_SMS] == true,
        )
    }

    private val gmailAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            viewModel.onGmailAuthorizationFailed("Gmail connection was cancelled")
            return@registerForActivityResult
        }
        runCatching {
            authorizationClient.getAuthorizationResultFromIntent(result.data!!)
        }.onSuccess(::handleAuthorizationResult)
            .onFailure {
                viewModel.onGmailAuthorizationFailed("Gmail authorization could not be completed")
            }
    }

    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let(viewModel::exportCsv) }

    private val importCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importCsv) }

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val passphrase = pendingBackupPassphrase
        pendingBackupPassphrase = null
        if (uri != null && passphrase != null) {
            viewModel.exportEncryptedBackup(uri, passphrase)
        } else {
            passphrase?.fill('\u0000')
        }
    }

    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val passphrase = pendingRestorePassphrase
        pendingRestorePassphrase = null
        if (uri != null && passphrase != null) {
            viewModel.restoreEncryptedBackup(uri, passphrase)
        } else {
            passphrase?.fill('\u0000')
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            MyExpensesTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                MyExpensesApp(
                    state = state,
                    onCompleteOnboarding = viewModel::completeOnboarding,
                    onRequestSmsPermission = {
                        smsPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_SMS,
                                Manifest.permission.RECEIVE_SMS,
                            ),
                        )
                    },
                    onScanSms = viewModel::scanSms,
                    onRequestGmail = ::requestGmailAuthorization,
                    onDisconnectGmail = ::disconnectGmail,
                    onSaveGmailQuery = viewModel::setGmailQuery,
                    onPreviousMonth = viewModel::previousMonth,
                    onNextMonth = viewModel::nextMonth,
                    onSearch = viewModel::setSearchQuery,
                    onAddManual = viewModel::addManual,
                    onUpdateTransaction = viewModel::updateTransaction,
                    onDeleteTransaction = viewModel::deleteTransaction,
                    onSetBudget = viewModel::setBudget,
                    onExportCsv = {
                        exportCsvLauncher.launch("my-expenses-${java.time.LocalDate.now()}.csv")
                    },
                    onImportCsv = {
                        importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values"))
                    },
                    onExportBackup = {
                        pendingBackupPassphrase?.fill('\u0000')
                        pendingBackupPassphrase = it
                        exportBackupLauncher.launch(
                            "my-expenses-${java.time.LocalDate.now()}.myexpenses",
                        )
                    },
                    onRestoreBackup = {
                        pendingRestorePassphrase?.fill('\u0000')
                        pendingRestorePassphrase = it
                        restoreBackupLauncher.launch(
                            arrayOf("application/octet-stream", "application/*"),
                        )
                    },
                    onMessageConsumed = viewModel::consumeMessage,
                )
            }
        }
    }

    override fun onDestroy() {
        pendingBackupPassphrase?.fill('\u0000')
        pendingRestorePassphrase?.fill('\u0000')
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSmsPermission()
    }

    private fun requestGmailAuthorization() {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GMAIL_READONLY_SCOPE)))
        viewModel.uiState.value.settings.gmailAccount?.let {
            builder.setAccount(Account(it, "com.google"))
        }
        authorizationClient.authorize(builder.build())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        viewModel.onGmailAuthorizationFailed("Gmail did not return a consent screen")
                    } else {
                        gmailAuthorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                        )
                    }
                } else {
                    handleAuthorizationResult(result)
                }
            }
            .addOnFailureListener {
                viewModel.onGmailAuthorizationFailed(
                    "Gmail authorization is unavailable. Check Google Play services.",
                )
            }
    }

    private fun handleAuthorizationResult(result: AuthorizationResult) {
        val accessToken = result.accessToken
        if (GMAIL_READONLY_SCOPE !in result.grantedScopes || accessToken.isNullOrBlank()) {
            viewModel.onGmailAuthorizationFailed("Read-only Gmail access was not granted")
            return
        }
        viewModel.onGmailAuthorized(accessToken)
    }

    private fun disconnectGmail() {
        val settings = viewModel.uiState.value.settings
        val builder = RevokeAccessRequest.builder()
            .setScopes(listOf(Scope(GMAIL_READONLY_SCOPE)))
        settings.gmailAccount?.let { builder.setAccount(Account(it, "com.google")) }
        authorizationClient.revokeAccess(builder.build())
            .addOnSuccessListener { viewModel.disconnectGmail() }
            .addOnFailureListener {
                viewModel.onGmailAuthorizationFailed(
                    "Gmail could not be disconnected. Check your connection and try again.",
                )
            }
    }
}
