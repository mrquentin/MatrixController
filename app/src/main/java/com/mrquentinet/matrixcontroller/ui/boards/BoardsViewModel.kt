package com.mrquentinet.matrixcontroller.ui.boards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mrquentinet.matrixcontroller.AppContainer
import com.mrquentinet.matrixcontroller.R
import com.mrquentinet.matrixcontroller.core.BoardAddress
import com.mrquentinet.matrixcontroller.core.parseBoardAddress
import com.mrquentinet.matrixcontroller.domain.Board
import com.mrquentinet.matrixcontroller.domain.BoardError
import com.mrquentinet.matrixcontroller.domain.BoardException
import com.mrquentinet.matrixcontroller.domain.BoardRepository
import com.mrquentinet.matrixcontroller.domain.DeviceInfo
import com.mrquentinet.matrixcontroller.domain.MatrixApi
import com.mrquentinet.matrixcontroller.ui.common.FieldError
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

/** The single add-board dialog is a two-phase flow; see [BoardsViewModel.checkBoard]. */
sealed interface AddBoardStep {
    /** Entering a name and an address. Nothing has touched the network yet. */
    data object Form : AddBoardStep

    /** The board answered `GET /`, so it exists. It must now be paired before it is persisted. */
    data class Pairing(
        val info: DeviceInfo,
        val pairing: Boolean = false,
        val error: BoardError? = null,
    ) : AddBoardStep
}

data class AddBoardUiState(
    val name: String = "",
    val address: String = "",
    val nameError: Int? = null,
    val addressError: FieldError? = null,
    /** A `GET /` probe is in flight. */
    val checking: Boolean = false,
    val step: AddBoardStep = AddBoardStep.Form,
)

data class BoardsUiState(
    val boards: List<BoardRowState> = emptyList(),
    val refreshing: Boolean = false,
    /** null when the add-board dialog is closed. */
    val addBoard: AddBoardUiState? = null,
)

/** Snackbar payload for the "board forgotten" undo affordance. */
class BoardRemoved(val boardName: String, val undo: () -> Unit)

class BoardsViewModel(
    private val repository: BoardRepository,
    private val api: MatrixApi,
) : ViewModel() {

    private val probes = MutableStateFlow<Map<String, ProbeState>>(emptyMap())
    private val pairedIds = MutableStateFlow<Set<String>>(emptySet())
    private val refreshing = MutableStateFlow(false)
    private val addBoard = MutableStateFlow<AddBoardUiState?>(null)

    private val removals = MutableSharedFlow<BoardRemoved>(extraBufferCapacity = 4)
    val removedEvents: Flow<BoardRemoved> = removals.asSharedFlow()

    val uiState: StateFlow<BoardsUiState> =
        combine(
            repository.boards,
            probes,
            pairedIds,
            refreshing,
            addBoard,
        ) { boards, probeStates, paired, isRefreshing, add ->
            BoardsUiState(
                boards = boards.map { board ->
                    BoardRowState(
                        board = board,
                        paired = board.id in paired,
                        probe = probeStates[board.id] ?: ProbeState.Probing,
                    )
                },
                refreshing = isRefreshing,
                addBoard = add,
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
        addBoard.value = AddBoardUiState()
    }

    fun dismissAddDialog() {
        addBoard.value = null
    }

    fun onAddNameChange(value: String) {
        addBoard.update { it?.copy(name = value, nameError = null) }
    }

    fun onAddAddressChange(value: String) {
        // Editing the address invalidates any completed reachability check.
        addBoard.update {
            it?.copy(address = value, addressError = null, step = AddBoardStep.Form)
        }
    }

    /**
     * Phase one of adding a board: prove it exists. Validates the form, rejects an address that
     * is already registered without a network call, then probes `GET /`. Nothing is persisted
     * here — on success the dialog advances to [AddBoardStep.Pairing].
     */
    fun checkBoard() {
        val current = addBoard.value ?: return
        if (current.checking) return
        val name = current.name.trim()
        val nameError = if (name.isEmpty()) R.string.error_name_required else null
        val parsed = parseBoardAddress(current.address)
        val addressError =
            if (parsed == null) FieldError.Res(R.string.error_invalid_address) else null
        if (nameError != null || parsed == null) {
            addBoard.value = current.copy(nameError = nameError, addressError = addressError)
            return
        }
        // Claim the in-flight flag before suspending, so a second tap is rejected by the
        // `checking` guard above rather than starting a second probe.
        addBoard.value = current.copy(nameError = null, addressError = null, checking = true)
        viewModelScope.launch {
            val duplicate = repository.boards.first().any {
                it.host.equals(parsed.host, ignoreCase = true) && it.port == parsed.port
            }
            if (duplicate) {
                addBoard.update {
                    it?.copy(
                        checking = false,
                        addressError = FieldError.Res(R.string.error_duplicate_address),
                    )
                }
                return@launch
            }
            try {
                val info = api.deviceInfo(target(name, parsed))
                addBoard.update { it?.copy(checking = false, step = AddBoardStep.Pairing(info)) }
            } catch (e: BoardException) {
                addBoard.update {
                    it?.copy(checking = false, addressError = FieldError.Board(e.error))
                }
            }
        }
    }

    /** Re-reads `GET /` so the pairing-window countdown reflects a just-pressed UP button. */
    fun recheckPairingWindow() {
        val current = addBoard.value ?: return
        val step = current.step as? AddBoardStep.Pairing ?: return
        if (current.checking || step.pairing) return
        val parsed = parseBoardAddress(current.address) ?: return
        addBoard.value = current.copy(checking = true)
        viewModelScope.launch {
            try {
                val info = api.deviceInfo(target(current.name.trim(), parsed))
                addBoard.update { it?.copy(checking = false, step = AddBoardStep.Pairing(info)) }
            } catch (e: BoardException) {
                addBoard.update { it?.copy(checking = false, step = step.copy(error = e.error)) }
            }
        }
    }

    /**
     * Phase two: pair first, persist only on success. A board the user cannot pair with never
     * reaches the list, so the list never contains an entry that still needs setting up.
     */
    fun pairAndAdd() {
        val current = addBoard.value ?: return
        val step = current.step as? AddBoardStep.Pairing ?: return
        if (step.pairing || current.checking) return
        val parsed = parseBoardAddress(current.address) ?: return
        val name = current.name.trim()
        // Claim the in-flight flag before suspending. A double tap must never pair twice: each
        // pair consumes one of the board's four client slots and cannot be undone from here.
        addBoard.value = current.copy(step = step.copy(pairing = true, error = null))
        viewModelScope.launch {
            val credentials = try {
                api.pair(target(name, parsed))
            } catch (e: BoardException) {
                addBoard.update { it?.copy(step = step.copy(pairing = false, error = e.error)) }
                return@launch
            }
            val created = repository.add(name, parsed.host, parsed.port)
            if (created == null) {
                // Lost a race against another add of the same address.
                addBoard.update {
                    it?.copy(
                        step = AddBoardStep.Form,
                        addressError = FieldError.Res(R.string.error_duplicate_address),
                    )
                }
                return@launch
            }
            repository.saveCredentials(created.id, credentials)
            addBoard.value = null
            pairedIds.update { it + created.id }
            probe(created)
        }
    }

    /**
     * Probing and pairing happen before the board is persisted, so they run against a transient
     * [Board]; [MatrixApi] only ever reads [Board.baseUrl] from it.
     */
    private fun target(name: String, address: BoardAddress) =
        Board(id = "", name = name, host = address.host, port = address.port)

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
