package com.ajamuar.ledger

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AiScreenTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun suggestionsAreReviewedBeforeApplyingAndCanBeDeselected() {
        val merchant = AiMerchant("m0", "synthetic cafe", "synthetic cafe")
        val result = AiSuggestions(emptyList(), listOf(merchant to "Food & dining"), 100, 20)
        var applied: Set<String>? = null
        ui.runOnUiThread { ui.activity.setContent { MaterialTheme {
            AiReviewDialog(result, false, {}, apply = { _, selected -> applied = selected })
        } } }
        ui.onNodeWithText("synthetic cafe → Food & dining").assertIsDisplayed()
        ui.runOnIdle { assertNull(applied) }
        ui.onNodeWithContentDescription("Categorise synthetic cafe").performClick()
        ui.onNodeWithText("Apply selected rules").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Categorise synthetic cafe").performClick()
        ui.onNodeWithText("Apply selected rules").performClick()
        ui.runOnIdle { assertEquals(setOf("m0"), applied) }
    }
}
