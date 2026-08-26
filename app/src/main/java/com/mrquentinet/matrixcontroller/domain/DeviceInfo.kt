package com.mrquentinet.matrixcontroller.domain

/** Unauthenticated `GET /` payload. */
data class DeviceInfo(
    val device: String,
    val firmwareVersion: String,
    val pairedClients: Int,
    val pairingOpen: Boolean,
    val pairingExpiresInSeconds: Long,
    val clockSynced: Boolean,
)
