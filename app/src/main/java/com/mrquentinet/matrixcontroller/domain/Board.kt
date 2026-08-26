package com.mrquentinet.matrixcontroller.domain

data class Board(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
) {
    val displayAddress: String get() = if (port == 80) host else "$host:$port"
    val baseUrl: String get() = "http://$host:$port"
}

/** Credentials handed out by `POST /pair`. [secretHex] is 64 lowercase hex chars = 32 raw bytes. */
data class BoardCredentials(val clientId: String, val secretHex: String)
