package com.myexpenses.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.myexpenses.data.IngestionSettings
import com.myexpenses.ui.theme.MyExpensesTheme
import org.junit.Rule
import org.junit.Test

class MyExpensesAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onboardingExplainsSensitivePermissionBeforeRequestingIt() {
        composeRule.setContent {
            MyExpensesTheme {
                TestApp(ExpenseUiState())
            }
        }

        composeRule.onNodeWithText("Your spending. Your device.").assertIsDisplayed()
        composeRule.onNodeWithText("Enable automatic SMS tracking").assertIsDisplayed()
        composeRule.onNodeWithText("Personal messages and OTPs are ignored.").assertIsDisplayed()
    }

    @Test
    fun settingsShowsDisconnectedSourcesWithoutBlockingManualUse() {
        composeRule.setContent {
            MyExpensesTheme {
                TestApp(
                    ExpenseUiState(
                        settings = IngestionSettings(onboardingComplete = true),
                        smsPermissionGranted = false,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Permission needed").assertIsDisplayed()
        composeRule.onNodeWithText("Not connected").assertIsDisplayed()
        composeRule.onNodeWithText("Connect Gmail").assertIsDisplayed()
    }
}

@androidx.compose.runtime.Composable
private fun TestApp(state: ExpenseUiState) {
    MyExpensesApp(
        state = state,
        onCompleteOnboarding = {},
        onRequestSmsPermission = {},
        onScanSms = {},
        onRequestGmail = {},
        onDisconnectGmail = {},
        onSaveGmailQuery = {},
        onPreviousMonth = {},
        onNextMonth = {},
        onSearch = {},
        onAddManual = { _, _, _, _, _, _ -> },
        onUpdateTransaction = { _, _, _, _, _, _ -> },
        onDeleteTransaction = {},
        onSetBudget = { _, _ -> },
        onExportCsv = {},
        onImportCsv = {},
        onExportBackup = {},
        onRestoreBackup = {},
        onMessageConsumed = {},
    )
}
