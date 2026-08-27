package com.mrquentinet.matrixcontroller.ui.board

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mrquentinet.matrixcontroller.R
import com.mrquentinet.matrixcontroller.domain.BoardApp
import com.mrquentinet.matrixcontroller.domain.BoardApps
import com.mrquentinet.matrixcontroller.domain.BoardError
import com.mrquentinet.matrixcontroller.domain.BoardException
import com.mrquentinet.matrixcontroller.testing.FakeMatrixApi
import com.mrquentinet.matrixcontroller.testing.InMemoryBoardRepository
import com.mrquentinet.matrixcontroller.domain.DeviceInfo
import com.mrquentinet.matrixcontroller.testing.TEST_CREDENTIALS
import com.mrquentinet.matrixcontroller.ui.theme.MatrixControllerTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The core interactions: the active app is the selected list item; a short press opens that
 * app's settings; a long press makes it the active app.
 */
@RunWith(AndroidJUnit4::class)
class BoardScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = InMemoryBoardRepository()
    private val api = FakeMatrixApi()
    private lateinit var boardId: String

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    @Before
    fun setUp() = runBlocking {
        val board = repository.add("Matrix", "192.168.1.50", 80)!!
        boardId = board.id
        repository.saveCredentials(boardId, TEST_CREDENTIALS)
    }

    private fun start(
        onOpenInfo: (String) -> Unit = {},
        onOpenAppSettings: (String, Int) -> Unit = { _, _ -> },
    ) {
        rule.setContent {
            val viewModel = remember { BoardViewModel(boardId, repository, api) }
            MatrixControllerTheme {
                BoardScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onOpenInfo = onOpenInfo,
                    onOpenAppSettings = onOpenAppSettings,
                )
            }
        }
    }

    @Test
    fun theActiveAppIsRenderedAsTheSelectedItem() {
        start()

        rule.onNode(hasText("Clock")).assertIsSelected()
        rule.onNode(hasText("Weather")).assertIsNotSelected()
        rule.onNodeWithText(string(R.string.app_active)).assertIsDisplayed()
    }

    @Test
    fun longPressingAnInactiveAppSwitchesTheBoardAndMovesTheSelection() {
        // The board is the source of truth for what became active.
        api.setActiveApp = { index ->
            api.apps = { BoardApps(FakeMatrixApi.DEFAULT_APPS, activeIndex = index) }
            FakeMatrixApi.DEFAULT_APPS[index]
        }
        start()

        rule.onNode(hasText("Weather")).performTouchInput { longClick() }

        rule.onNode(hasText("Weather")).assertIsSelected()
        rule.onNode(hasText("Clock")).assertIsNotSelected()
        assertEquals(listOf("apps", "setActiveApp 1"), api.calls)
    }

    @Test
    fun theSelectionFollowsTheBoardNotTheLongPress() {
        // The board refuses the switch and reports index 0 as still active.
        api.setActiveApp = { BoardApp(0, "Clock") }
        start()

        rule.onNode(hasText("Weather")).performTouchInput { longClick() }

        rule.onNode(hasText("Clock")).assertIsSelected()
        rule.onNode(hasText("Weather")).assertIsNotSelected()
    }

    @Test
    fun tappingAnAppOpensItsSettingsWithoutSwitchingIt() {
        var openedBoardId: String? = null
        var openedIndex: Int? = null
        start(onOpenAppSettings = { id, index ->
            openedBoardId = id
            openedIndex = index
        })

        rule.onNode(hasText("Weather")).performClick()

        assertEquals(boardId, openedBoardId)
        assertEquals(1, openedIndex)
        // A short press never issues the board's active-app switch call.
        assertEquals(listOf("apps"), api.calls)
    }

    @Test
    fun aRevokedBoardFallsBackToThePairingPanelRatherThanAnError() {
        api.apps = { throw BoardException(BoardError.CredentialsRejected) }
        api.deviceInfo = { REACHABLE_WINDOW_CLOSED }
        start()

        rule.onNodeWithText(string(R.string.pair_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_pair)).assertIsDisplayed()
        // The dead secret is dropped so the board is not left in a half-paired state.
        assertEquals(null, repository.credentialsFor(boardId))
    }

    @Test
    fun theInfoActionNavigatesWithTheBoardId() {
        var opened: String? = null
        start(onOpenInfo = { opened = it })

        rule.onNodeWithContentDescription(string(R.string.action_board_info)).performClick()

        assertEquals(boardId, opened)
    }

    private companion object {
        val REACHABLE_WINDOW_CLOSED = DeviceInfo(
            device = "matrixfaces",
            firmwareVersion = "dev",
            pairedClients = 0,
            pairingOpen = false,
            pairingExpiresInSeconds = 0,
            clockSynced = true,
        )
    }
}
