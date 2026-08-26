package com.mrquentinet.matrixcontroller.ui.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.mrquentinet.matrixcontroller.AppContainer
import com.mrquentinet.matrixcontroller.domain.Board
import com.mrquentinet.matrixcontroller.domain.BoardError
import com.mrquentinet.matrixcontroller.domain.BoardException
import com.mrquentinet.matrixcontroller.domain.BoardMetrics
import com.mrquentinet.matrixcontroller.domain.BoardRepository
import com.mrquentinet.matrixcontroller.domain.BoardStatus
import com.mrquentinet.matrixcontroller.domain.DeviceInfo
import com.mrquentinet.matrixcontroller.domain.MatrixApi
import com.mrquentinet.matrixcontroller.ui.navigation.RouteBoardInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BoardInfoUiState(
    val board: Board? = null,
    val info: DeviceInfo? = null,
    val status: BoardStatus? = null,
    val metrics: BoardMetrics? = null,
    /** True when the firmware was built with `-DMETRICS_ENABLED=0`. Not an error. */
    val metricsUnavailable: Boolean = false,
    val refreshing: Boolean = false,
    val error: BoardError? = null,
)

class BoardInfoViewModel(
    private val boardId: String,
    private val repository: BoardRepository,
    private val api: MatrixApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BoardInfoUiState(refreshing = true))
    val uiState: StateFlow<BoardInfoUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true, error = null) }
            val board = repository.boards.first().firstOrNull { it.id == boardId }
            if (board == null) {
                _uiState.value = BoardInfoUiState(error = BoardError.NotPaired)
                return@launch
            }
            val credentials = repository.credentials(boardId)
            if (credentials == null) {
                _uiState.value = BoardInfoUiState(board = board, error = BoardError.NotPaired)
                return@launch
            }
            try {
                // Strictly sequential: the board serves one request at a time.
                val info = api.deviceInfo(board)
                val status = api.status(board, credentials)
                val metrics = api.metrics(board, credentials)
                _uiState.value = BoardInfoUiState(
                    board = board,
                    info = info,
                    status = status,
                    metrics = metrics,
                    metricsUnavailable = metrics == null,
                    refreshing = false,
                    error = null,
                )
            } catch (e: BoardException) {
                _uiState.update { it.copy(board = board, refreshing = false, error = e.error) }
            }
        }
    }

    fun errorShown() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val boardId = createSavedStateHandle().toRoute<RouteBoardInfo>().boardId
                BoardInfoViewModel(boardId, container.boardRepository, container.matrixApi)
            }
        }
    }
}
