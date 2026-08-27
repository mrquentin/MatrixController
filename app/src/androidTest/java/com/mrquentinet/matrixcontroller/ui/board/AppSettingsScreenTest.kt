package com.mrquentinet.matrixcontroller.ui.board

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mrquentinet.matrixcontroller.R
import com.mrquentinet.matrixcontroller.domain.AppSettingSchema
import com.mrquentinet.matrixcontroller.domain.AppSettingType
import com.mrquentinet.matrixcontroller.domain.BoardApp
import com.mrquentinet.matrixcontroller.domain.BoardApps
import com.mrquentinet.matrixcontroller.domain.SettingValue
import com.mrquentinet.matrixcontroller.testing.FakeMatrixApi
import com.mrquentinet.matrixcontroller.testing.InMemoryBoardRepository
import com.mrquentinet.matrixcontroller.testing.TEST_CREDENTIALS
import com.mrquentinet.matrixcontroller.ui.theme.MatrixControllerTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val MIXED_SCHEMA = listOf(
    AppSettingSchema("on", "Power", AppSettingType.Bool),
    AppSettingSchema("size", "Text scale", AppSettingType.IntType, min = 1, max = 2),
    AppSettingSchema("label", "Display text", AppSettingType.StringType, maxLen = 31),
    AppSettingSchema("color", "Text color", AppSettingType.ColorType, min = 0, max = 16_777_215),
    AppSettingSchema("mode", "Mode", AppSettingType.Unknown("enum")),
)

private val MIXED_VALUES = mapOf(
    "on" to SettingValue.BoolValue(true),
    "size" to SettingValue.IntValue(1),
    "label" to SettingValue.StringValue("Hello"),
    "color" to SettingValue.IntValue(0x00B4FF),
    "mode" to SettingValue.RawValue("\"rainbow\""),
)

/**
 * Everything here is driven purely from [AppSettingSchema] — no widget references a specific app
 * name or setting key, matching the "never hardcode a specific app" requirement.
 */
@RunWith(AndroidJUnit4::class)
class AppSettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = InMemoryBoardRepository()
    private val api = FakeMatrixApi()
    private lateinit var boardId: String

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    @Before
    fun setUp() = runBlocking {
        boardId = repository.add("Matrix", "192.168.1.50", 80)!!.id
        repository.saveCredentials(boardId, TEST_CREDENTIALS)
        api.apps = {
            BoardApps(
                apps = listOf(BoardApp(0, "Clock", MIXED_SCHEMA), BoardApp(1, "Blank", emptyList())),
                activeIndex = 0,
            )
        }
        api.appSettings = { index -> if (index == 0) MIXED_VALUES else emptyMap() }
    }

    private fun start(appIndex: Int) {
        rule.setContent {
            val viewModel = remember { AppSettingsViewModel(boardId, appIndex, repository, api) }
            MatrixControllerTheme {
                AppSettingsScreen(viewModel = viewModel, onBack = {})
            }
        }
    }

    @Test
    fun rendersOneWidgetPerSchemaEntryPrefilledFromTheBoard() {
        start(appIndex = 0)

        rule.onNodeWithText("Power").assertIsDisplayed()
        rule.onNode(isToggleable()).assertIsOn()
        rule.onNodeWithText("Text scale").assertIsDisplayed()
        rule.onNodeWithText("1").assertIsDisplayed()
        rule.onNodeWithText("Display text").assertIsDisplayed()
        rule.onNodeWithText("Hello").assertIsDisplayed()
        rule.onNodeWithText("Text color").assertIsDisplayed()
        rule.onNodeWithText("Mode").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.settings_unsupported, "enum")).assertIsDisplayed()
    }

    @Test
    fun anAppWithNoSettingsShowsThePlaceholderInsteadOfAnEmptyList() {
        start(appIndex = 1)

        rule.onNodeWithText(string(R.string.settings_empty)).assertIsDisplayed()
    }

    @Test
    fun togglingASwitchAndSavingSendsOnlyThatKey() {
        var savedChanges: Map<String, SettingValue>? = null
        api.updateAppSettings = { _, changes ->
            savedChanges = changes
            MIXED_VALUES + changes
        }
        start(appIndex = 0)

        rule.onNode(isToggleable()).performClick()
        rule.onNode(isToggleable()).assertIsOff()
        rule.onNodeWithContentDescription(string(R.string.action_save)).performClick()

        assertEquals(mapOf("on" to SettingValue.BoolValue(false)), savedChanges)
        // The save action disappears once there is nothing dirty left to save.
        rule.onNodeWithContentDescription(string(R.string.action_save)).assertDoesNotExist()
        assertEquals(listOf("apps", "appSettings 0", "updateAppSettings 0"), api.calls)
    }
}
