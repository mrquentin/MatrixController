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

    /** `GET /api/apps/<index>/settings` — current values, keyed by [AppSettingSchema.key]. [schema]
     *  (from the matching [BoardApp.settings]) drives how each raw JSON value is interpreted. */
    suspend fun appSettings(
        board: Board,
        credentials: BoardCredentials,
        index: Int,
        schema: List<AppSettingSchema>,
    ): Map<String, SettingValue>

    /**
     * `POST /api/apps/<index>/settings` — partial update; send only changed keys. Atomic on the
     * board: a single invalid/unrecognized key rejects the whole request. Returns every current
     * value on success, mirroring the GET shape — callers should replace their form state with it.
     */
    suspend fun updateAppSettings(
        board: Board,
        credentials: BoardCredentials,
        index: Int,
        schema: List<AppSettingSchema>,
        changes: Map<String, SettingValue>,
    ): Map<String, SettingValue>

    /** null when the firmware was built with `-DMETRICS_ENABLED=0` (404 `unknown_endpoint`). */
    suspend fun metrics(board: Board, credentials: BoardCredentials): BoardMetrics?
}
