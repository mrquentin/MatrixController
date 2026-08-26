package com.mrquentinet.matrixcontroller.data.api.dto

import com.mrquentinet.matrixcontroller.domain.DeviceInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfoDto(
    val device: String,
    @SerialName("firmware_version") val firmwareVersion: String,
    @SerialName("paired_clients") val pairedClients: Int,
    @SerialName("pairing_open") val pairingOpen: Boolean,
    @SerialName("pairing_expires_in") val pairingExpiresIn: Long,
    @SerialName("clock_synced") val clockSynced: Boolean,
)

fun DeviceInfoDto.toDomain() = DeviceInfo(
    device = device,
    firmwareVersion = firmwareVersion,
    pairedClients = pairedClients,
    pairingOpen = pairingOpen,
    pairingExpiresInSeconds = pairingExpiresIn,
    clockSynced = clockSynced,
)
