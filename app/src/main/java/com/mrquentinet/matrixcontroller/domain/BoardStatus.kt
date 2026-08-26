package com.mrquentinet.matrixcontroller.domain

/** Authenticated `GET /api/status` payload. */
data class BoardStatus(
    val uptimeSeconds: Long,
    val ledOn: Boolean,
    val rssi: Int,
    val pairedClients: Int,
    /** Board wall clock; `0` means the board has no valid NTP time yet. */
    val boardTimeEpochSeconds: Long,
)
