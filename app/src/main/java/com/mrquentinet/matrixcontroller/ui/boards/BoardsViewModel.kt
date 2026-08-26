package com.mrquentinet.matrixcontroller.ui.boards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mrquentinet.matrixcontroller.AppContainer
import com.mrquentinet.matrixcontroller.R
import com.mrquentinet.matrixcontroller.core.parseBoardAddress
import com.mrquentinet.matrixcontroller.domain.Board
import com.mrquentinet.matrixcontroller.domain.BoardError
import com.mrquentinet.matrixcontroller.domain.BoardException
import com.mrquentinet.matrixcontroller.domain.BoardRepository
import com.mrquentinet.matrixcontroller.domain.DeviceInfo
import com.mrquentinet.matrixcontroller.domain.MatrixApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BoardRowState(val board: Board, val paired: Boolean, val probe: ProbeState)

sealed interface ProbeState {
    data object Probing : ProbeState
    data object Offline : ProbeState
    data class Online(
        val info: DeviceInfo,
        val activeAppName: String?,
        val credentialsRejected: Boolean,
    ) : ProbeState
}

data class BoardsUiState(
    val boards: List<BoardRowState> = emptyList(),
    val refreshing: Boolean = false,
    val addDialogVisible: Boolean = false,
    val addNameError: Int? = null,
    val addAddressError: Int? = null,
)

/** Snackbar payload for the "board forgotten" undo affordance. */
class BoardRemoved(val boardName: String, val undo: () -> Unit)

private data class AddDialogState(
    val visible: Boolean = false,
    val nameError: Int? = null,
    val addressError: Int? = null,
)

class BoardsViewModel(
    private val repository: BoardRepository,
    private val api: MatrixApi,
) : ViewModel() {

    private val probes = MutableStateFlow<Map<String, ProbeState>>(emptyMap())
    private val pairedIds = MutableStateFlow<Set<String>>(emptySet())
    private val refreshing = MutableStateFlow(false)
    private val addDialog = MutableStateFlow(AddDialogState())

    private val removals = MutableSharedFlow<BoardRemoved>(extraBufferCapacity = 4)
    val removedEvents: Flow<BoardRemoved> = removals.asSharedFlow()

    val uiState: StateFlow<BoardsUiState> =
        combine(
            repository.boards,
            probes,
            pairedIds,
            refreshing,
            addDialog,
        ) { boards, probeStates, paired, isRefreshing, dialog ->
            BoardsUiState(
                boards = boards.map { board ->
                    BoardRowState(
                        board = board,
                        paired = board.id in paired,
                        probe = probeStates[board.id] ?: ProbeState.Probing,
                    )
                },
                refreshing = isRefreshing,
                addDialogVisible = dialog.visible,
                addNameError = dialog.nameError,
                addAddressError = dialog.addressError,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoardsUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                val boards = repository.boards.first()
                val ids = boards.mapTo(mutableSetOf(), Board::id)
                probes.update { current -> current.filterKeys { it in ids } }
                syncPaired(boards)
                // One coroutine per board. Calls to a *single* board stay sequential inside
                // probe(); different boards are different hosts and may overlap.
                coroutineScope {
                    boards.forEach { board -> launch { probe(board) } }
                }
            } finally {
                refreshing.value = false
            }
        }
    }

    fun showAddDialog() {
        addDialog.value = AddDialogState(visible = true)
    }

    fun dismissAddDialog() {
        addDialog.value = AddDialogState()
    }

    fun addBoard(name: String, address: String) {
        val trimmedName = name.trim()
        val nameError = if (trimmedName.isEmpty()) R.string.error_name_required else null
        val parsed = parseBoardAddress(address)
        val addressError = if (parsed == null) R.string.error_invalid_address else null
        if (parsed == null || nameError != null) {
            addDialog.value =
                AddDialogState(visible = true, nameError = nameError, addressError = addressError)
            return
        }
        viewModelScope.launch {
            val created = repository.add(trimmedName, parsed.host, parsed.port)
            if (created == null) {
                addDialog.value = AddDialogState(
                    visible = true,
                    addressError = R.string.error_duplicate_address,
                )
            } else {
                addDialog.value = AddDialogState()
                probe(created)
            }
        }
    }

    fun forget(boardId: String) {
        viewModelScope.launch {
            val board = repository.boards.first().firstOrNull { it.id == boardId } ?: return@launch
            val credentials = repository.credentials(boardId)
            repository.remove(boardId)
            probes.update { it - boardId }
            pairedIds.update { it - boardId }
            removals.emit(
                BoardRemoved(board.name) {
                    viewModelScope.launch {
                        repository.restore(board, credentials)
                        if (credentials != null) pairedIds.update { it + boardId }
                        probe(board)
                    }
                }
            )
        }
    }

    private suspend fun syncPaired(boards: List<Board>) {
        val paired = mutableSetOf<String>()
        for (board in boards) {
            if (repository.credentials(board.id) != null) paired += board.id
        }
        pairedIds.value = paired
    }

    /**
     * `GET /` first; only if that succeeds and credentials exist does the active-app probe follow,
     * strictly after it — the board answers one request at a time.
     */
    private suspend fun probe(board: Board) {
        probes.update { it + (board.id to ProbeState.Probing) }
        val state = try {
            val info = api.deviceInfo(board)
            val credentials = repository.credentials(board.id)
            if (credentials == null) {
                pairedIds.update { it - board.id }
                ProbeState.Online(info, activeAppName = null, credentialsRejected = false)
            } else {
                pairedIds.update { it + board.id }
                try {
                    val active = api.activeApp(board, credentials)
                    ProbeState.Online(info, active.name, credentialsRejected = false)
                } catch (e: BoardException) {
                    ProbeState.Online(
                        info = info,
                        activeAppName = null,
                        credentialsRejected = e.error == BoardError.CredentialsRejected,
                    )
                }
            }
        } catch (_: BoardException) {
            ProbeState.Offline
        }
        probes.update { it + (board.id to state) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { BoardsViewModel(container.boardRepository, container.matrixApi) }
        }
    }
}
