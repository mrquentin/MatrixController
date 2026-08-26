package com.mrquentinet.matrixcontroller.ui.boards

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mrquentinet.matrixcontroller.R
import com.mrquentinet.matrixcontroller.data.store.DataStoreBoardRepository
import com.mrquentinet.matrixcontroller.data.store.PlaintextSecretCipher
import com.mrquentinet.matrixcontroller.domain.Board
import com.mrquentinet.matrixcontroller.domain.BoardApp
import com.mrquentinet.matrixcontroller.domain.BoardApps
import com.mrquentinet.matrixcontroller.domain.BoardCredentials
import com.mrquentinet.matrixcontroller.domain.BoardError
import com.mrquentinet.matrixcontroller.domain.BoardException
import com.mrquentinet.matrixcontroller.domain.BoardMetrics
import com.mrquentinet.matrixcontroller.domain.BoardStatus
import com.mrquentinet.matrixcontroller.domain.DeviceInfo
import com.mrquentinet.matrixcontroller.domain.MatrixApi
import com.mrquentinet.matrixcontroller.ui.common.FieldError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val TEST_CREDENTIALS = BoardCredentials("0123456789abcdef", "ab".repeat(32))

private val REACHABLE = DeviceInfo(
    device = "matrixfaces",
    firmwareVersion = "dev",
    pairedClients = 0,
    pairingOpen = true,
    pairingExpiresInSeconds = 42,
    clockSynced = true,
)

private class FakeMatrixApi : MatrixApi {
    var deviceInfo: () -> DeviceInfo = { REACHABLE }
    var pair: () -> BoardCredentials = { TEST_CREDENTIALS }
    val calls = mutableListOf<String>()

    override suspend fun deviceInfo(board: Board): DeviceInfo {
        calls += "deviceInfo ${board.host}:${board.port}"
        return deviceInfo.invoke()
    }

    override suspend fun pair(board: Board): BoardCredentials {
        calls += "pair ${board.host}:${board.port}"
        return pair.invoke()
    }

    override suspend fun activeApp(board: Board, credentials: BoardCredentials): BoardApp {
        calls += "activeApp ${board.host}:${board.port}"
        return BoardApp(0, "Clock")
    }

    override suspend fun status(board: Board, credentials: BoardCredentials): BoardStatus =
        throw AssertionError("unexpected status call")

    override suspend fun apps(board: Board, credentials: BoardCredentials): BoardApps =
        throw AssertionError("unexpected apps call")

    override suspend fun setActiveApp(
        board: Board,
        credentials: BoardCredentials,
        index: Int,
    ): BoardApp = throw AssertionError("unexpected setActiveApp call")

    override suspend fun metrics(board: Board, credentials: BoardCredentials): BoardMetrics? =
        throw AssertionError("unexpected metrics call")
}

/**
 * The add-board flow is a single dialog that persists nothing until the board has both answered
 * `GET /` and been paired. These tests pin that down: an entry can never reach the list unless the
 * board really exists and is usable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardsViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val api = FakeMatrixApi()
    private lateinit var repository: DataStoreBoardRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            produceFile = { folder.newFile("boards.preferences_pb").also { it.delete() } },
        )
        repository = DataStoreBoardRepository(dataStore, PlaintextSecretCipher(), Json)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** uiState is `WhileSubscribed`, so it needs a live collector to leave its initial value. */
    private fun TestScope.viewModel(): BoardsViewModel {
        val viewModel = BoardsViewModel(repository, api)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        return viewModel
    }

    private fun BoardsViewModel.fillForm(name: String, address: String) {
        showAddDialog()
        onAddNameChange(name)
        onAddAddressChange(address)
    }

    @Test
    fun `an unreachable board is never persisted and pairing is not attempted`() =
        runTest(dispatcher) {
            api.deviceInfo = { throw BoardException(BoardError.Unreachable) }
            val viewModel = viewModel()

            viewModel.fillForm("Matrix", "192.168.1.50")
            viewModel.checkBoard()
            advanceUntilIdle()

            assertEquals(emptyList<Board>(), repository.boards.first())
            val add = viewModel.uiState.value.addBoard!!
            assertEquals(AddBoardStep.Form, add.step)
            assertEquals(FieldError.Board(BoardError.Unreachable), add.addressError)
            assertFalse(add.checking)
            assertTrue(api.calls.none { it.startsWith("pair") })
        }

    @Test
    fun `a reachable board advances to pairing without being persisted yet`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.fillForm("Matrix", "192.168.1.50:8080")
            viewModel.checkBoard()
            advanceUntilIdle()

            assertEquals(emptyList<Board>(), repository.boards.first())
            assertEquals(AddBoardStep.Pairing(REACHABLE), viewModel.uiState.value.addBoard!!.step)
            assertTrue(api.calls.contains("deviceInfo 192.168.1.50:8080"))
        }

    @Test
    fun `the board and its credentials are persisted only once pairing succeeds`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.fillForm("  Matrix  ", "192.168.1.50:8080")
            viewModel.checkBoard()
            advanceUntilIdle()
            viewModel.pairAndAdd()
            advanceUntilIdle()

            val board = repository.boards.first().single()
            assertEquals("Matrix", board.name)
            assertEquals("192.168.1.50", board.host)
            assertEquals(8080, board.port)
            assertEquals(TEST_CREDENTIALS, repository.credentials(board.id))
            assertNull("the dialog closes on success", viewModel.uiState.value.addBoard)
            assertTrue(viewModel.uiState.value.boards.single().paired)
        }

    @Test
    fun `a failed pairing persists nothing and keeps the dialog on the pairing step`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.fillForm("Matrix", "192.168.1.50")
            viewModel.checkBoard()
            advanceUntilIdle()

            api.pair = { throw BoardException(BoardError.PairingClosed) }
            viewModel.pairAndAdd()
            advanceUntilIdle()

            assertEquals(emptyList<Board>(), repository.boards.first())
            val step = viewModel.uiState.value.addBoard!!.step as AddBoardStep.Pairing
            assertEquals(BoardError.PairingClosed, step.error)
            assertFalse(step.pairing)
        }

    @Test
    fun `a duplicate address is rejected without touching the network`() = runTest(dispatcher) {
        repository.add("Existing", "192.168.1.50", 80)
        val viewModel = viewModel()
        api.calls.clear()

        viewModel.fillForm("Matrix", "http://192.168.1.50/")
        viewModel.checkBoard()
        advanceUntilIdle()

        val add = viewModel.uiState.value.addBoard!!
        assertEquals(FieldError.Res(R.string.error_duplicate_address), add.addressError)
        assertEquals(AddBoardStep.Form, add.step)
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(1, repository.boards.first().size)
    }

    @Test
    fun `form validation runs before any probe`() = runTest(dispatcher) {
        val viewModel = viewModel()
        api.calls.clear()

        viewModel.fillForm("   ", "not a host")
        viewModel.checkBoard()
        advanceUntilIdle()

        val add = viewModel.uiState.value.addBoard!!
        assertEquals(R.string.error_name_required, add.nameError)
        assertEquals(FieldError.Res(R.string.error_invalid_address), add.addressError)
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `editing the address sends a confirmed board back to the form`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.fillForm("Matrix", "192.168.1.50")
        viewModel.checkBoard()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.addBoard!!.step is AddBoardStep.Pairing)

        viewModel.onAddAddressChange("192.168.1.51")
        advanceUntilIdle()

        assertEquals(AddBoardStep.Form, viewModel.uiState.value.addBoard!!.step)
    }

    /**
     * Regression: the in-flight flag used to be claimed inside the launched coroutine, so two
     * taps landing in the same frame both got through and paired twice — silently burning two of
     * the board's four client slots.
     */
    @Test
    fun `tapping pair twice pairs exactly once`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.fillForm("Matrix", "192.168.1.50")
        viewModel.checkBoard()
        advanceUntilIdle()
        api.calls.clear()

        viewModel.pairAndAdd()
        viewModel.pairAndAdd()
        advanceUntilIdle()

        assertEquals(listOf("pair 192.168.1.50:80"), api.calls.filter { it.startsWith("pair") })
        assertEquals(1, repository.boards.first().size)
    }

    @Test
    fun `tapping continue twice probes exactly once`() = runTest(dispatcher) {
        val viewModel = viewModel()
        api.calls.clear()

        viewModel.fillForm("Matrix", "192.168.1.50")
        viewModel.checkBoard()
        viewModel.checkBoard()
        advanceUntilIdle()

        assertEquals(listOf("deviceInfo 192.168.1.50:80"), api.calls)
    }

    @Test
    fun `cancelling after a successful probe persists nothing`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.fillForm("Matrix", "192.168.1.50")
        viewModel.checkBoard()
        advanceUntilIdle()
        viewModel.dismissAddDialog()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.addBoard)
        assertEquals(emptyList<Board>(), repository.boards.first())
    }
}
