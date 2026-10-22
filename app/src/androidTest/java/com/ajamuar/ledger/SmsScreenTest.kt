package com.ajamuar.ledger

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SmsScreenTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun discoveryRequiresSelectionAndConfirmation() {
        val account = Account("synthetic-bank", "Synthetic bank ••4821")
        val candidate = SmsCandidate(24950, Kind.EXPENSE, Status.POSTED, "SYNTHETIC CAFE", "4821", false, null, "out")
        var confirmed: Set<String>? = null
        ui.runOnUiThread {
            ui.activity.setContent { MaterialTheme {
                SmsSelectionDialog(SmsDiscovery(30, 5, listOf(SmsPreview(account, 2, listOf(100000L to candidate), needsLearning = 1))) , emptySet(), false, {}, { confirmed = it })
            } }
        }
        ui.onNodeWithText("1 alerts need format setup", substring = true).assertExists()
        ui.onNodeWithText("Import selected accounts").assertIsNotEnabled()
        ui.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        ui.runOnIdle { assertNull(confirmed) }
        ui.onNodeWithText("Import selected accounts").assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals(setOf(account.id), confirmed) }
    }
}
