package com.ajamuar.ledger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

class UiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @After fun removeSyntheticRecords() = runBlocking {
        val repo = ApplicationProvider.getApplicationContext<LedgerApplication>().repository
        repo.dao.snapshot().filter { it.merchant == "UI TEST CAFE" && !it.deleted }.forEach { repo.save(it.copy(deleted = true)) }
        repo.dao.billSnapshot().filter { it.name == "UI TEST BILL" }.forEach { repo.dao.deleteBill(it) }
        repo.dao.groupSnapshot().filter { it.name == "UI TEST GROUP" }.forEach { repo.dao.deleteGroup(it) }
    }
    @Test fun addEntryNavigateBillsAndCreatePrivateGroup() {
        ui.onNodeWithContentDescription("Add transaction").performClick()
        ui.onNodeWithText("Amount in rupees").performTextInput("249.50")
        ui.onNodeWithText("Merchant or description").performTextInput("UI TEST CAFE")
        ui.onNodeWithText("Save & mark reviewed").performScrollTo().performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("UI TEST CAFE").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Activity", useUnmergedTree = true).performClick()
        ui.onNodeWithText("UI TEST CAFE").assertIsDisplayed()
        ui.onNodeWithText("Plans", useUnmergedTree = true).performClick()
        ui.onNodeWithText("Bills").performClick()
        ui.onNodeWithText("Add bill or reminder").performScrollTo().performClick()
        ui.onNodeWithText("Biller / obligation").performTextInput("UI TEST BILL")
        ui.onNodeWithText("Save bill").performScrollTo().performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("UI TEST BILL").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Groups").performScrollTo().performClick()
        ui.onNodeWithText("Create private group").performScrollTo().performClick()
        ui.onNodeWithText("Group name").performTextInput("UI TEST GROUP")
        ui.onNodeWithText("Other members · comma separated").performTextInput("Other")
        ui.onNodeWithText("Create", substring = false).performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("UI TEST GROUP").fetchSemanticsNodes().isNotEmpty() }
    }
}
