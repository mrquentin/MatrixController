package com.mrquentinet.matrixcontroller.data.api.dto

import com.mrquentinet.matrixcontroller.domain.BoardStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StatusDto(
    @SerialName("uptime_s") val uptimeSeconds: Long,
    val led: Boolean,
    val rssi: Int,
    @SerialName("paired_clients") val pairedClients: Int,
    val time: Long,
)

fun StatusDto.toDomain() = BoardStatus(
    uptimeSeconds = uptimeSeconds,
    ledOn = led,
    rssi = rssi,
    pairedClients = pairedClients,
    boardTimeEpochSeconds = time,
)
