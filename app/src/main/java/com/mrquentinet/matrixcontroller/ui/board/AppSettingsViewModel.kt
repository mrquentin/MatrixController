package com.mrquentinet.matrixcontroller.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.mrquentinet.matrixcontroller.AppContainer
import com.mrquentinet.matrixcontroller.domain.AppSettingSchema
import com.mrquentinet.matrixcontroller.domain.Board
import com.mrquentinet.matrixcontroller.domain.BoardCredentials
import com.mrquentinet.matrixcontroller.domain.BoardError
import com.mrquentinet.matrixcontroller.domain.BoardException
import com.mrquentinet.matrixcontroller.domain.BoardRepository
import com.mrquentinet.matrixcontroller.domain.MatrixApi
import com.mrquentinet.matrixcontroller.domain.SettingValue
import com.mrquentinet.matrixcontroller.ui.navigation.RouteAppSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface AppSettingsUiState {
    data object Loading : AppSettingsUiState

    data class Loaded(
        val appName: String,
        val schema: List<AppSettingSchema>,
        /** Last known-good values from the board. */
        val values: Map<String, SettingValue>,
        /** Local, unsaved changes — layered on top of [values] for display. */
        val edits: Map<String, SettingValue>,
        val saving: Boolean,
        val error: BoardError?,
    ) : AppSettingsUiState {
        val dirty: Boolean get() = edits.isNotEmpty()

        fun valueFor(key: String): SettingValue? = edits[key] ?: values[key]
    }

    data class Failed(val error: BoardError) : AppSettingsUiState
}

class AppSettingsViewModel(
    private val boardId: String,
    private val appIndex: Int,
    private val repository: BoardRepository,
    private val api: MatrixApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AppSettingsUiState>(AppSettingsUiState.Loading)
    val uiState: StateFlow<AppSettingsUiState> = _uiState.asStateFlow()

    /**
     * One-shot: the board revoked this pairing — pop back to where re-pairing lives.
     * `extraBufferCapacity = 1` (not `replay`) so `emit` never drops this because the screen's
     * collector hasn't attached yet, while a subscriber that attaches *after* the emit still
     * doesn't get a stale replay.
     */
    private val _navigateBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateBack: SharedFlow<Unit> = _navigateBack.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = AppSettingsUiState.Loading
            val board = board()
            val credentials = board?.let { repository.credentials(boardId) }
            if (board == null || credentials == null) {
                _uiState.value = AppSettingsUiState.Failed(BoardError.NotPaired)
                return@launch
            }
            try {
                val (name, schema) = appAndSchema(board, credentials)
                val values = api.appSettings(board, credentials, appIndex, schema)
                _uiState.value = AppSettingsUiState.Loaded(
                    appName = name,
                    schema = schema,
                    values = values,
                    edits = emptyMap(),
                    saving = false,
                    error = null,
                )
            } catch (e: BoardException) {
                if (e.error == BoardError.CredentialsRejected) {
                    repository.clearCredentials(boardId)
                    _navigateBack.emit(Unit)
                    return@launch
                }
                _uiState.value = AppSettingsUiState.Failed(e.error)
            }
        }
    }

    fun setEdit(key: String, value: SettingValue) {
        val current = _uiState.value
        if (current !is AppSettingsUiState.Loaded) return
        _uiState.value = current.copy(edits = current.edits + (key to value), error = null)
    }

    fun errorShown() {
        val current = _uiState.value
        if (current is AppSettingsUiState.Loaded) _uiState.value = current.copy(error = null)
    }

    fun save() {
        val current = _uiState.value
        if (current !is AppSettingsUiState.Loaded || !current.dirty || current.saving) return
        viewModelScope.launch {
            _uiState.value = current.copy(saving = true, error = null)
            val board = board()
            val credentials = board?.let { repository.credentials(boardId) }
            if (board == null || credentials == null) {
                repository.clearCredentials(boardId)
                _navigateBack.emit(Unit)
                return@launch
            }
            try {
                val applied =
                    api.updateAppSettings(board, credentials, appIndex, current.schema, current.edits)
                _uiState.value = current.copy(
                    values = applied,
                    edits = emptyMap(),
                    saving = false,
                    error = null,
                )
            } catch (e: BoardException) {
                handleSaveFailure(current, board, credentials, e.error)
            }
        }
    }

    /**
     * Every failure keeps [AppSettingsUiState.Loaded.edits] so the user doesn't lose typing —
     * only the codes the spec calls out as "schema/values went stale" trigger a re-fetch of the
     * baseline underneath those edits.
     */
    private suspend fun handleSaveFailure(
        current: AppSettingsUiState.Loaded,
        board: Board,
        credentials: BoardCredentials,
        error: BoardError,
    ) {
        when (error) {
            BoardError.CredentialsRejected -> {
                repository.clearCredentials(boardId)
                _navigateBack.emit(Unit)
            }

            BoardError.InvalidSettingValue -> refreshValues(current, board, credentials, error)

            BoardError.NoRecognizedSettings, BoardError.UnknownAppIndex ->
                refreshSchemaAndValues(current, board, credentials, error)

            else -> _uiState.value = current.copy(saving = false, error = error)
        }
    }

    private suspend fun refreshValues(
        current: AppSettingsUiState.Loaded,
        board: Board,
        credentials: BoardCredentials,
        error: BoardError,
    ) {
        try {
            val values = api.appSettings(board, credentials, appIndex, current.schema)
            _uiState.value = current.copy(values = values, saving = false, error = error)
        } catch (e: BoardException) {
            _uiState.value = current.copy(saving = false, error = e.error)
        }
    }

    private suspend fun refreshSchemaAndValues(
        current: AppSettingsUiState.Loaded,
        board: Board,
        credentials: BoardCredentials,
        error: BoardError,
    ) {
        try {
            val (name, schema) = appAndSchema(board, credentials)
            val values = api.appSettings(board, credentials, appIndex, schema)
            _uiState.value = current.copy(
                appName = name,
                schema = schema,
                values = values,
                saving = false,
                error = error,
            )
        } catch (e: BoardException) {
            _uiState.value = current.copy(saving = false, error = e.error)
        }
    }

    private suspend fun appAndSchema(
        board: Board,
        credentials: BoardCredentials,
    ): Pair<String, List<AppSettingSchema>> {
        val app = api.apps(board, credentials).apps.firstOrNull { it.index == appIndex }
            ?: throw BoardException(BoardError.UnknownAppIndex)
        return app.name to app.settings
    }

    private suspend fun board(): Board? =
        repository.boards.first().firstOrNull { it.id == boardId }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val route = createSavedStateHandle().toRoute<RouteAppSettings>()
                AppSettingsViewModel(
                    boardId = route.boardId,
                    appIndex = route.appIndex,
                    repository = container.boardRepository,
                    api = container.matrixApi,
                )
            }
        }
    }
}
