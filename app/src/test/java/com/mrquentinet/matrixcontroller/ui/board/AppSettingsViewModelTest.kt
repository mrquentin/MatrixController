package com.mrquentinet.matrixcontroller.ui.board

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mrquentinet.matrixcontroller.data.store.DataStoreBoardRepository
import com.mrquentinet.matrixcontroller.data.store.PlaintextSecretCipher
import com.mrquentinet.matrixcontroller.domain.AppSettingSchema
import com.mrquentinet.matrixcontroller.domain.AppSettingType
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
import com.mrquentinet.matrixcontroller.domain.SettingValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val TEST_CREDENTIALS = BoardCredentials("0123456789abcdef", "ab".repeat(32))

private val SCHEMA = listOf(
    AppSettingSchema("size", "Text scale", AppSettingType.IntType, min = 1, max = 2),
    AppSettingSchema("label", "Label", AppSettingType.StringType, maxLen = 31),
)

private class FakeMatrixApi : MatrixApi {
    var apps: () -> BoardApps = { BoardApps(listOf(BoardApp(0, "Clock", SCHEMA)), activeIndex = 0) }
    var appSettings: (Int) -> Map<String, SettingValue> = {
        mapOf("size" to SettingValue.IntValue(1), "label" to SettingValue.StringValue("hi"))
    }
    var updateAppSettings: (Int, Map<String, SettingValue>) -> Map<String, SettingValue> =
        { _, changes -> changes }
    val calls = mutableListOf<String>()

    override suspend fun deviceInfo(board: Board): DeviceInfo =
        throw AssertionError("unexpected deviceInfo call")

    override suspend fun pair(board: Board): BoardCredentials =
        throw AssertionError("unexpected pair call")

    override suspend fun status(board: Board, credentials: BoardCredentials): BoardStatus =
        throw AssertionError("unexpected status call")

    override suspend fun apps(board: Board, credentials: BoardCredentials): BoardApps {
        calls += "apps"
        return apps.invoke()
    }

    override suspend fun activeApp(board: Board, credentials: BoardCredentials): BoardApp =
        throw AssertionError("unexpected activeApp call")

    override suspend fun setActiveApp(
        board: Board,
        credentials: BoardCredentials,
        index: Int,
    ): BoardApp = throw AssertionError("unexpected setActiveApp call")

    override suspend fun appSettings(
        board: Board,
        credentials: BoardCredentials,
        index: Int,
        schema: List<AppSettingSchema>,
    ): Map<String, SettingValue> {
        calls += "appSettings $index"
        return appSettings.invoke(index)
    }

    override suspend fun updateAppSettings(
        board: Board,
        credentials: BoardCredentials,
        index: Int,
        schema: List<AppSettingSchema>,
        changes: Map<String, SettingValue>,
    ): Map<String, SettingValue> {
        calls += "updateAppSettings $index ${changes.keys.sorted()}"
        return updateAppSettings.invoke(index, changes)
    }

    override suspend fun metrics(board: Board, credentials: BoardCredentials): BoardMetrics? =
        throw AssertionError("unexpected metrics call")
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    // Unconfined, not Standard: `viewModelScope.launch` and a test's `backgroundScope.launch`
    // (e.g. collecting `navigateBack`) must interleave without hand-pumping order becoming
    // observable — a suspended SharedFlow collector's resumption is a separately-scheduled task
    // under StandardTestDispatcher, and getting `advanceUntilIdle()` calls in exactly the right
    // places relative to `emit()` is needlessly fragile. Unconfined runs everything eagerly.
    private val dispatcher = UnconfinedTestDispatcher()
    private val api = FakeMatrixApi()
    private lateinit var repository: DataStoreBoardRepository
    private lateinit var boardId: String

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        // Not `dispatcher`: this scope's actor coroutine needs to actually run while this
        // `@Before` is inside a plain `runBlocking`, not a `TestScope` pumping the shared
        // `StandardTestDispatcher` — see DataStoreBoardRepositoryTest for the same reasoning.
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()),
            produceFile = { folder.newFile("boards.preferences_pb").also { it.delete() } },
        )
        repository = DataStoreBoardRepository(dataStore, PlaintextSecretCipher(), Json)
        boardId = repository.add("Matrix", "192.168.1.50", 80)!!.id
        repository.saveCredentials(boardId, TEST_CREDENTIALS)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(appIndex: Int = 0) = AppSettingsViewModel(boardId, appIndex, repository, api)

    @Test
    fun `load fetches the schema and current values for the given index`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value as AppSettingsUiState.Loaded
        assertEquals("Clock", state.appName)
        assertEquals(SCHEMA, state.schema)
        assertEquals(SettingValue.IntValue(1), state.values["size"])
        assertEquals(SettingValue.StringValue("hi"), state.values["label"])
        assertEquals(emptyMap<String, SettingValue>(), state.edits)
        assertEquals(listOf("apps", "appSettings 0"), api.calls)
    }

    @Test
    fun `load fails with UnknownAppIndex when the index is no longer in the apps list`() =
        runTest(dispatcher) {
            val vm = viewModel(appIndex = 7)
            advanceUntilIdle()

            assertEquals(
                BoardError.UnknownAppIndex,
                (vm.uiState.value as AppSettingsUiState.Failed).error,
            )
        }

    @Test
    fun `load fails with NotPaired when no credentials are stored`() = runTest(dispatcher) {
        repository.clearCredentials(boardId)
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(BoardError.NotPaired, (vm.uiState.value as AppSettingsUiState.Failed).error)
    }

    @Test
    fun `setEdit layers on top of the baseline without mutating it`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setEdit("size", SettingValue.IntValue(2))

        val state = vm.uiState.value as AppSettingsUiState.Loaded
        assertEquals(SettingValue.IntValue(1), state.values["size"])
        assertEquals(SettingValue.IntValue(2), state.edits["size"])
        assertEquals(SettingValue.IntValue(2), state.valueFor("size"))
        assertTrue(state.dirty)
    }

    @Test
    fun `save sends only the edited keys and replaces the form with the response`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setEdit("size", SettingValue.IntValue(2))
            api.updateAppSettings = { _, _ ->
                mapOf("size" to SettingValue.IntValue(2), "label" to SettingValue.StringValue("hi"))
            }

            vm.save()
            advanceUntilIdle()

            val state = vm.uiState.value as AppSettingsUiState.Loaded
            assertEquals(SettingValue.IntValue(2), state.values["size"])
            assertEquals(emptyMap<String, SettingValue>(), state.edits)
            assertTrue(!state.dirty)
            assertNull(state.error)
            assertEquals(listOf("apps", "appSettings 0", "updateAppSettings 0 [size]"), api.calls)
        }

    @Test
    fun `an invalid_setting_value failure refreshes the values but keeps the user's edits`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setEdit("size", SettingValue.IntValue(9))
            api.updateAppSettings = { _, _ -> throw BoardException(BoardError.InvalidSettingValue) }
            api.appSettings = {
                mapOf("size" to SettingValue.IntValue(1), "label" to SettingValue.StringValue("server-side"))
            }

            vm.save()
            advanceUntilIdle()

            val state = vm.uiState.value as AppSettingsUiState.Loaded
            assertEquals(BoardError.InvalidSettingValue, state.error)
            // Refreshed baseline...
            assertEquals(SettingValue.StringValue("server-side"), state.values["label"])
            // ...but the user's unsaved edit survives so they can fix and retry.
            assertEquals(SettingValue.IntValue(9), state.edits["size"])
        }

    @Test
    fun `an unknown_app_index failure refreshes the schema but keeps the user's edits`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setEdit("size", SettingValue.IntValue(2))
            api.updateAppSettings = { _, _ -> throw BoardException(BoardError.UnknownAppIndex) }
            val newSchema = SCHEMA + AppSettingSchema("extra", "Extra", AppSettingType.Bool)
            api.apps = { BoardApps(listOf(BoardApp(0, "Clock", newSchema)), activeIndex = 0) }
            api.appSettings = {
                mapOf(
                    "size" to SettingValue.IntValue(1),
                    "label" to SettingValue.StringValue("hi"),
                    "extra" to SettingValue.BoolValue(false),
                )
            }

            vm.save()
            advanceUntilIdle()

            val state = vm.uiState.value as AppSettingsUiState.Loaded
            assertEquals(BoardError.UnknownAppIndex, state.error)
            assertEquals(newSchema, state.schema)
            assertEquals(SettingValue.IntValue(2), state.edits["size"])
        }

    @Test
    fun `a CredentialsRejected save failure clears credentials and emits navigateBack`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setEdit("size", SettingValue.IntValue(2))
            api.updateAppSettings = { _, _ -> throw BoardException(BoardError.CredentialsRejected) }
            var navigatedBack = false
            backgroundScope.launch { vm.navigateBack.collect { navigatedBack = true } }
            advanceUntilIdle()

            vm.save()
            advanceUntilIdle()

            assertTrue(navigatedBack)
            assertNull(repository.credentials(boardId))
        }
}
