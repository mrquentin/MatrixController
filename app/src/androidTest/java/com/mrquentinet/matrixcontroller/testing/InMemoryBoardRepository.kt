package com.mrquentinet.matrixcontroller.testing

import com.mrquentinet.matrixcontroller.domain.Board
import com.mrquentinet.matrixcontroller.domain.BoardCredentials
import com.mrquentinet.matrixcontroller.domain.BoardRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test double replacing DataStore + the Android Keystore, so UI tests observe the same
 * behaviour synchronously and without touching the filesystem.
 */
class InMemoryBoardRepository : BoardRepository {

    private val state = MutableStateFlow<List<Board>>(emptyList())
    private val secrets = mutableMapOf<String, BoardCredentials>()
    private var nextId = 0

    override val boards: Flow<List<Board>> = state.asStateFlow()

    /** Synchronous view for assertions. */
    val current: List<Board> get() = state.value

    fun credentialsFor(boardId: String): BoardCredentials? = secrets[boardId]

    override suspend fun credentials(boardId: String): BoardCredentials? = secrets[boardId]

    override suspend fun add(name: String, host: String, port: Int): Board? {
        if (state.value.any { it.host.equals(host, ignoreCase = true) && it.port == port }) {
            return null
        }
        val board = Board(id = "board-${nextId++}", name = name, host = host, port = port)
        state.value = state.value + board
        return board
    }

    override suspend fun rename(boardId: String, name: String) {
        state.value = state.value.map { if (it.id == boardId) it.copy(name = name) else it }
    }

    override suspend fun saveCredentials(boardId: String, credentials: BoardCredentials) {
        secrets[boardId] = credentials
    }

    override suspend fun clearCredentials(boardId: String) {
        secrets.remove(boardId)
    }

    override suspend fun remove(boardId: String) {
        state.value = state.value.filterNot { it.id == boardId }
    }

    override suspend fun restore(board: Board, credentials: BoardCredentials?) {
        if (state.value.none { it.id == board.id }) state.value = state.value + board
        if (credentials != null) secrets[board.id] = credentials
    }
}
