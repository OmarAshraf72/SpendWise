package com.example.spendwise.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.example.spendwise.screens.NavigationCustomizationScreen

class NavigationCustomizationInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun longPressDragReordersAndVisibleMoveButtonsAreAbsent() {
        var savedOrder: List<String>? = null
        composeRule.setContent {
            MaterialTheme {
                NavigationCustomizationScreen(
                    configuration = NavigationConfiguration.DEFAULT,
                    onBack = {},
                    onSaveOrder = { savedOrder = it },
                    onSetPinned = { _, _ -> },
                    onReset = {}
                )
            }
        }
        composeRule.onNodeWithText("Move up").assertDoesNotExist()
        composeRule.onNodeWithText("Move down").assertDoesNotExist()
        composeRule.onNodeWithTag("navigation-row-ASSETS").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, -size.height * 1.4f))
            advanceEventTime(200)
            up()
        }
        composeRule.runOnIdle {
            assertTrue((savedOrder?.indexOf("ASSETS") ?: 4) < 4)
        }
    }
}
