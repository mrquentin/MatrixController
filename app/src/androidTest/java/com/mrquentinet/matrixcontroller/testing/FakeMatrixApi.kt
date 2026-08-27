package com.mrquentinet.matrixcontroller.testing

import com.mrquentinet.matrixcontroller.domain.AppSettingSchema
import com.mrquentinet.matrixcontroller.domain.Board
import com.mrquentinet.matrixcontroller.domain.BoardApp
import com.mrquentinet.matrixcontroller.domain.BoardApps
import com.mrquentinet.matrixcontroller.domain.BoardCredentials
import com.mrquentinet.matrixcontroller.domain.BoardMetrics
import com.mrquentinet.matrixcontroller.domain.BoardStatus
import com.mrquentinet.matrixcontroller.domain.CpuMetrics
import com.mrquentinet.matrixcontroller.domain.DeviceInfo
import com.mrquentinet.matrixcontroller.domain.MatrixApi
import com.mrquentinet.matrixcontroller.domain.RamMetrics
import com.mrquentinet.matrixcontroller.domain.SettingValue

val TEST_CREDENTIALS = BoardCredentials("0123456789abcdef", "ab".repeat(32))

val REACHABLE_BOARD = DeviceInfo(
    device = "matrixfaces",
    firmwareVersion = "dev",
    pairedClients = 0,
    pairingOpen = true,
    pairingExpiresInSeconds = 42,
    clockSynced = true,
)

/** Scriptable [MatrixApi]; every method records its call so tests can assert on traffic. */
class FakeMatrixApi : MatrixApi {

    var deviceInfo: () -> DeviceInfo = { REACHABLE_BOARD }
    var pair: () -> BoardCredentials = { TEST_CREDENTIALS }
    var apps: () -> BoardApps = { BoardApps(DEFAULT_APPS, activeIndex = 0) }
    var setActiveApp: (Int) -> BoardApp = { index -> DEFAULT_APPS[index] }
    var status: () -> BoardStatus = { DEFAULT_STATUS }
    var metrics: () -> BoardMetrics? = { DEFAULT_METRICS }
    var appSettings: (Int) -> Map<String, SettingValue> = { emptyMap() }
    var updateAppSettings: (Int, Map<String, SettingValue>) -> Map<String, SettingValue> =
        { _, changes -> changes }

    val calls = mutableListOf<String>()

    override suspend fun deviceInfo(board: Board): DeviceInfo {
        calls += "deviceInfo ${board.host}:${board.port}"
        return deviceInfo.invoke()
    }

    override suspend fun pair(board: Board): BoardCredentials {
        calls += "pair ${board.host}:${board.port}"
        return pair.invoke()
    }

    override suspend fun status(board: Board, credentials: BoardCredentials): BoardStatus {
        calls += "status"
        return status.invoke()
    }

    override suspend fun apps(board: Board, credentials: BoardCredentials): BoardApps {
        calls += "apps"
        return apps.invoke()
    }

    override suspend fun activeApp(board: Board, credentials: BoardCredentials): BoardApp {
        calls += "activeApp"
        val current = apps.invoke()
        return current.apps.first { it.index == current.activeIndex }
    }

    override suspend fun setActiveApp(
        board: Board,
        credentials: BoardCredentials,
        index: Int,
    ): BoardApp {
        calls += "setActiveApp $index"
        return setActiveApp.invoke(index)
    }

    override suspend fun metrics(board: Board, credentials: BoardCredentials): BoardMetrics? {
        calls += "metrics"
        return metrics.invoke()
    }

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
        calls += "updateAppSettings $index"
        return updateAppSettings.invoke(index, changes)
    }

    companion object {
        val DEFAULT_APPS = listOf(BoardApp(0, "Clock"), BoardApp(1, "Weather"))

        val DEFAULT_STATUS = BoardStatus(
            uptimeSeconds = 3_723,
            ledOn = false,
            rssi = -57,
            pairedClients = 1,
            boardTimeEpochSeconds = 1_700_000_000,
        )

        val DEFAULT_METRICS = BoardMetrics(
            cpu = CpuMetrics(
                loopHz = 812,
                busyPerMille = 143,
                requests = 7,
                requestAvgMicros = 4_210,
                requestMaxMicros = 9_930,
                authAvgMicros = 1_180,
                authMaxMicros = 2_470,
            ),
            ram = RamMetrics(
                total = 196_608,
                staticBytes = 51_200,
                heapUsed = 38_912,
                stackPeak = 6_144,
                freeNow = 106_496,
                minFreeEver = 98_304,
            ),
        )
    }
}
