package com.mrquentinet.matrixcontroller.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.mrquentinet.matrixcontroller.AppContainer
import com.mrquentinet.matrixcontroller.domain.Board
import com.mrquentinet.matrixcontroller.domain.BoardApp
import com.mrquentinet.matrixcontroller.domain.BoardError
import com.mrquentinet.matrixcontroller.domain.BoardException
import com.mrquentinet.matrixcontroller.domain.BoardRepository
import com.mrquentinet.matrixcontroller.domain.MatrixApi
import com.mrquentinet.matrixcontroller.ui.navigation.RouteBoard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface BoardUiState {
    data object Loading : BoardUiState

    data class NeedsPairing(
        val board: Board,
        val pairingOpen: Boolean,
        val pairingExpiresIn: Long,
        val pairing: Boolean,
        val error: BoardError?,
    ) : BoardUiState

    data class Apps(
        val board: Board,
        val apps: List<BoardApp>,
        val activeIndex: Int,
        val switchingToIndex: Int?,
        val refreshing: Boolean,
        val error: BoardError?,
    ) : BoardUiState

    data class Failed(val board: Board?, val error: BoardError) : BoardUiState
}

class BoardViewModel(
    private val boardId: String,
    private val repository: BoardRepository,
    private val api: MatrixApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BoardUiState>(BoardUiState.Loading)
    val uiState: StateFlow<BoardUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = BoardUiState.Loading
            val board = board()
            if (board == null) {
                _uiState.value = BoardUiState.Failed(null, BoardError.NotPaired)
                return@launch
            }
            val credentials = repository.credentials(boardId)
            if (credentials == null) {
                _uiState.value = needsPairing(board)
                return@launch
            }
            try {
                val apps = api.apps(board, credentials)
                _uiState.value = BoardUiState.Apps(
                    board = board,
                    apps = apps.apps,
                    activeIndex = apps.activeIndex,
                    switchingToIndex = null,
                    refreshing = false,
                    error = null,
                )
            } catch (e: BoardException) {
                if (e.error == BoardError.CredentialsRejected) {
                    // Revoked on the board: drop the dead secret and offer pairing again.
                    repository.clearCredentials(boardId)
                    _uiState.value = needsPairing(board)
                } else {
                    _uiState.value = BoardUiState.Failed(board, e.error)
                }
            }
        }
    }

    fun refresh() {
        val current = _uiState.value
        if (current !is BoardUiState.Apps || current.refreshing) return
        viewModelScope.launch {
            _uiState.value = current.copy(refreshing = true, error = null)
            val credentials = repository.credentials(boardId)
            if (credentials == null) {
                _uiState.value = needsPairing(current.board)
                return@launch
            }
            try {
                val apps = api.apps(current.board, credentials)
                _uiState.value = current.copy(
                    apps = apps.apps,
                    activeIndex = apps.activeIndex,
                    refreshing = false,
                    error = null,
                )
            } catch (e: BoardException) {
                _uiState.value = current.copy(refreshing = false, error = e.error)
            }
        }
    }

    fun pair() {
        val current = _uiState.value
        if (current !is BoardUiState.NeedsPairing || current.pairing) return
        viewModelScope.launch {
            _uiState.value = current.copy(pairing = true, error = null)
            try {
                val credentials = api.pair(current.board)
                repository.saveCredentials(boardId, credentials)
                load()
            } catch (e: BoardException) {
                _uiState.value = current.copy(pairing = false, error = e.error)
            }
        }
    }

    fun selectApp(index: Int) {
        val current = _uiState.value
        if (current !is BoardUiState.Apps) return
        if (index == current.activeIndex || current.switchingToIndex != null) return
        viewModelScope.launch {
            _uiState.value = current.copy(switchingToIndex = index, error = null)
            val credentials = repository.credentials(boardId)
            if (credentials == null) {
                _uiState.value = needsPairing(current.board)
                return@launch
            }
            try {
                val applied = api.setActiveApp(current.board, credentials, index)
                // The board's response is the source of truth, not the requested index.
                _uiState.value = current.copy(
                    activeIndex = applied.index,
                    switchingToIndex = null,
                    error = null,
                )
            } catch (e: BoardException) {
                _uiState.value = current.copy(switchingToIndex = null, error = e.error)
            }
        }
    }

    fun errorShown() {
        when (val current = _uiState.value) {
            is BoardUiState.Apps -> _uiState.value = current.copy(error = null)
            is BoardUiState.NeedsPairing -> _uiState.value = current.copy(error = null)
            else -> Unit
        }
    }

    private suspend fun board(): Board? =
        repository.boards.first().firstOrNull { it.id == boardId }

    private suspend fun needsPairing(board: Board): BoardUiState = try {
        val info = api.deviceInfo(board)
        BoardUiState.NeedsPairing(
            board = board,
            pairingOpen = info.pairingOpen,
            pairingExpiresIn = info.pairingExpiresInSeconds,
            pairing = false,
            error = null,
        )
    } catch (e: BoardException) {
        BoardUiState.NeedsPairing(
            board = board,
            pairingOpen = false,
            pairingExpiresIn = 0,
            pairing = false,
            error = e.error,
        )
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val boardId = createSavedStateHandle().toRoute<RouteBoard>().boardId
                BoardViewModel(boardId, container.boardRepository, container.matrixApi)
            }
        }
    }
}
