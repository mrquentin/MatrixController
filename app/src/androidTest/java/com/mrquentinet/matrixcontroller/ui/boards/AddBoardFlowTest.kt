package com.mrquentinet.matrixcontroller.ui.boards

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mrquentinet.matrixcontroller.R
import com.mrquentinet.matrixcontroller.domain.BoardError
import com.mrquentinet.matrixcontroller.domain.BoardException
import com.mrquentinet.matrixcontroller.testing.FakeMatrixApi
import com.mrquentinet.matrixcontroller.testing.InMemoryBoardRepository
import com.mrquentinet.matrixcontroller.testing.TEST_CREDENTIALS
import com.mrquentinet.matrixcontroller.ui.theme.MatrixControllerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real [BoardsScreen] + [BoardsViewModel] against test doubles, replacing what used to
 * be a manual adb tap-and-dump session.
 *
 * The contract under test: adding a board is a single dialog, and nothing is persisted until the
 * board has answered `GET /` *and* been paired.
 */
@RunWith(AndroidJUnit4::class)
class AddBoardFlowTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = InMemoryBoardRepository()
    private val api = FakeMatrixApi()

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun start() {
        rule.setContent {
            val viewModel = remember { BoardsViewModel(repository, api) }
            MatrixControllerTheme {
                BoardsScreen(viewModel = viewModel, onOpenBoard = {})
            }
        }
    }

    private fun openFormWith(name: String, address: String) {
        rule.onNodeWithContentDescription(string(R.string.action_add_board)).performClick()
        rule.onNodeWithTag(ADD_BOARD_NAME_TAG).performTextInput(name)
        rule.onNodeWithTag(ADD_BOARD_ADDRESS_TAG).performTextInput(address)
    }

    private fun tapContinue() =
        rule.onNodeWithText(string(R.string.action_continue)).performClick()

    private fun tapPair() = rule.onNodeWithText(string(R.string.action_pair)).performClick()

    @Test
    fun emptyStateOffersTheAddAction() {
        start()

        rule.onNodeWithText(string(R.string.boards_empty_title)).assertIsDisplayed()
        rule.onNodeWithContentDescription(string(R.string.action_add_board)).assertIsDisplayed()
    }

    @Test
    fun aBoardThatDoesNotAnswerIsNeverAdded() {
        api.deviceInfo = { throw BoardException(BoardError.Unreachable) }
        start()

        openFormWith("Ghost", "127.0.0.1:9999")
        tapContinue()

        // The failure is reported inline on the address field and the dialog stays open.
        rule.onNodeWithText(string(R.string.error_unreachable)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_pair)).assertDoesNotExist()
        assertEquals(emptyList<String>(), repository.current.map { it.name })
    }

    @Test
    fun aReachableBoardIsConfirmedButNotYetPersisted() {
        start()

        openFormWith("Matrix", "192.168.1.50")
        tapContinue()

        rule.onNodeWithText(string(R.string.add_board_found, "matrixfaces", "dev"))
            .assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_pair)).assertIsDisplayed()
        assertEquals(emptyList<String>(), repository.current.map { it.name })
    }

    @Test
    fun pairingInTheSameDialogAddsAReadyToUseBoard() {
        start()

        openFormWith("Matrix", "192.168.1.50")
        tapContinue()
        tapPair()

        // Dialog closed, and the row is immediately paired and online: no second step in the list.
        rule.onNodeWithText(string(R.string.add_board_title)).assertDoesNotExist()
        rule.onNodeWithText("Matrix").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.status_online_app, "Clock")).assertIsDisplayed()

        val board = repository.current.single()
        assertEquals("Matrix", board.name)
        assertEquals("192.168.1.50", board.host)
        assertEquals(80, board.port)
        assertEquals(TEST_CREDENTIALS, repository.credentialsFor(board.id))
    }

    @Test
    fun aFailedPairingPersistsNothingAndKeepsTheDialogOpen() {
        api.pair = { throw BoardException(BoardError.PairingClosed) }
        start()

        openFormWith("Matrix", "192.168.1.50")
        tapContinue()
        tapPair()

        rule.onNodeWithText(string(R.string.error_pairing_closed)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_pair)).assertIsDisplayed()
        assertEquals(emptyList<String>(), repository.current.map { it.name })
    }

    @Test
    fun cancellingAfterASuccessfulProbePersistsNothing() {
        start()

        openFormWith("Matrix", "192.168.1.50")
        tapContinue()
        rule.onNodeWithText(string(R.string.action_cancel)).performClick()

        rule.onNodeWithText(string(R.string.add_board_title)).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.boards_empty_title)).assertIsDisplayed()
        assertEquals(emptyList<String>(), repository.current.map { it.name })
    }

    @Test
    fun theFormIsValidatedBeforeAnyNetworkCall() {
        start()

        rule.onNodeWithContentDescription(string(R.string.action_add_board)).performClick()
        rule.onNodeWithTag(ADD_BOARD_ADDRESS_TAG).performTextInput("not a host")
        tapContinue()

        rule.onNodeWithText(string(R.string.error_name_required)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.error_invalid_address)).assertIsDisplayed()
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun anAlreadyRegisteredAddressIsRejectedWithoutProbing() {
        start()

        openFormWith("Matrix", "192.168.1.50")
        tapContinue()
        tapPair()
        api.calls.clear()

        openFormWith("Second", "http://192.168.1.50/")
        tapContinue()

        rule.onNodeWithText(string(R.string.error_duplicate_address)).assertIsDisplayed()
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(1, repository.current.size)
    }

    @Test
    fun editingTheAddressSendsAConfirmedBoardBackToTheForm() {
        start()

        openFormWith("Matrix", "192.168.1.50")
        tapContinue()
        rule.onNodeWithText(string(R.string.add_board_found, "matrixfaces", "dev"))
            .assertIsDisplayed()

        // Going back to the form is the only way the address field can be edited again.
        rule.onNodeWithText(string(R.string.action_cancel)).performClick()
        openFormWith("Matrix", "192.168.1.51")

        rule.onNodeWithText(string(R.string.add_board_found, "matrixfaces", "dev"))
            .assertDoesNotExist()
        rule.onNodeWithText(string(R.string.action_continue)).assertIsDisplayed()
        assertNull(repository.current.firstOrNull())
    }
}
