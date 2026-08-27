package com.mrquentinet.matrixcontroller.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mrquentinet.matrixcontroller.ui.theme.MatrixControllerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests the pure rendering half of the local network permission gate. The stateful half
 * (`ContextCompat.checkSelfPermission` + `rememberLauncherForActivityResult`) is Android's own
 * permission machinery, exercised through the platform's own tests, not re-tested here.
 */
@RunWith(AndroidJUnit4::class)
class LocalNetworkPermissionScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun showsTheBodyAndInvokesTheActionOnTap() {
        var tapCount = 0
        rule.setContent {
            MatrixControllerTheme {
                LocalNetworkPermissionScreen(
                    body = "Explain why this is needed.",
                    actionLabel = "Grant access",
                    onAction = { tapCount++ },
                )
            }
        }

        rule.onNodeWithText("Explain why this is needed.").assertIsDisplayed()
        rule.onNodeWithText("Grant access").performClick()

        assertEquals(1, tapCount)
    }

    @Test
    fun rendersTheSettingsVariantWithItsOwnLabel() {
        var tapCount = 0
        rule.setContent {
            MatrixControllerTheme {
                LocalNetworkPermissionScreen(
                    body = "Permission was denied permanently.",
                    actionLabel = "Open settings",
                    onAction = { tapCount++ },
                )
            }
        }

        rule.onNodeWithText("Permission was denied permanently.").assertIsDisplayed()
        rule.onNodeWithText("Open settings").performClick()

        assertEquals(1, tapCount)
    }
}
