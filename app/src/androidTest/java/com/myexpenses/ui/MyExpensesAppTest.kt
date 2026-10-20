package com.myexpenses.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.myexpenses.data.ExpenseRepository
import com.myexpenses.data.IngestionSettings
import com.myexpenses.data.model.AccountEntity
import com.myexpenses.data.model.AccountType
import com.myexpenses.data.model.BudgetEntity
import com.myexpenses.data.model.CategoryEntity
import com.myexpenses.data.model.SourceType
import com.myexpenses.data.model.TransactionEntity
import com.myexpenses.data.model.TransactionType
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

    @Test
    fun dashboardShowsMerchantBreakdownAndBudgetAlert() {
        val category = CategoryEntity(
            id = "food",
            name = "Food & dining",
            icon = "🍲",
            color = 0xFFE76F51,
        )
        composeRule.setContent {
            MyExpensesTheme {
                TestApp(
                    ExpenseUiState(
                        settings = IngestionSettings(onboardingComplete = true),
                        categories = listOf(category),
                        transactions = listOf(
                            TransactionEntity(
                                amountMinor = 9_000,
                                type = TransactionType.EXPENSE,
                                merchant = "TEST CAFE",
                                categoryId = category.id,
                                occurredAt = System.currentTimeMillis(),
                                source = SourceType.SMS,
                            ),
                        ),
                        budgets = listOf(
                            BudgetEntity(
                                categoryId = category.id,
                                monthKey = java.time.YearMonth.now().toString(),
                                limitMinor = 10_000,
                            ),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Budget alerts").performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Top merchants"))
        composeRule.onNodeWithText("Top merchants").assertIsDisplayed()
        composeRule.onAllNodesWithText("TEST CAFE").assertAny(isDisplayed())
    }

    @Test
    fun accountsScreenSupportsLocalAccountManagement() {
        composeRule.setContent {
            MyExpensesTheme {
                TestApp(
                    ExpenseUiState(
                        settings = IngestionSettings(onboardingComplete = true),
                        accounts = listOf(
                            AccountEntity(
                                id = ExpenseRepository.CASH_ACCOUNT_ID,
                                name = "Cash",
                                type = AccountType.CASH,
                            ),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Accounts").performClick()
        composeRule.onNodeWithText("Add account").assertIsDisplayed()
        composeRule.onNodeWithText("Cash").assertIsDisplayed()
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
        onReviewOnly = {},
        onAddManual = { _, _, _, _, _, _ -> },
        onUpdateTransaction = { _, _, _, _, _, _ -> },
        onDeleteTransaction = {},
        onSetBudget = { _, _ -> },
        onSaveAccount = { _, _, _, _, _ -> },
        onExportCsv = {},
        onImportCsv = {},
        onExportBackup = {},
        onRestoreBackup = {},
        onMessageConsumed = {},
    )
}
