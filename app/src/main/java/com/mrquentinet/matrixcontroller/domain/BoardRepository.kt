package com.mrquentinet.matrixcontroller.domain

import kotlinx.coroutines.flow.Flow

interface BoardRepository {
    val boards: Flow<List<Board>>

    suspend fun credentials(boardId: String): BoardCredentials?

    /** @return the created board, or null when a board with the same host+port already exists. */
    suspend fun add(name: String, host: String, port: Int): Board?

    suspend fun rename(boardId: String, name: String)

    suspend fun saveCredentials(boardId: String, credentials: BoardCredentials)

    suspend fun clearCredentials(boardId: String)

    suspend fun remove(boardId: String)

    /** Re-inserts a removed board and its credentials — backs the snackbar Undo action. */
    suspend fun restore(board: Board, credentials: BoardCredentials?)
}
