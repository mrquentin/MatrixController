package com.mrquentinet.matrixcontroller.domain

/**
 * The board's HTTP API. Every authenticated call takes explicit [BoardCredentials] because the
 * secret is per-board; a shared auth interceptor would have nowhere to look it up.
 *
 * Every method throws [BoardException] and nothing else.
 */
interface MatrixApi {
    suspend fun deviceInfo(board: Board): DeviceInfo

    suspend fun pair(board: Board): BoardCredentials

    suspend fun status(board: Board, credentials: BoardCredentials): BoardStatus

    suspend fun apps(board: Board, credentials: BoardCredentials): BoardApps

    suspend fun activeApp(board: Board, credentials: BoardCredentials): BoardApp

    suspend fun setActiveApp(board: Board, credentials: BoardCredentials, index: Int): BoardApp

    /** null when the firmware was built with `-DMETRICS_ENABLED=0` (404 `unknown_endpoint`). */
    suspend fun metrics(board: Board, credentials: BoardCredentials): BoardMetrics?
}
