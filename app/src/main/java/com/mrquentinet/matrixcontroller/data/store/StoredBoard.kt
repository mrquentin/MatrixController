package com.mrquentinet.matrixcontroller.data.store

import com.mrquentinet.matrixcontroller.core.DEFAULT_BOARD_PORT
import com.mrquentinet.matrixcontroller.domain.Board
import kotlinx.serialization.Serializable

/** Persistence record. [sealedSecret] is Base64 of `iv(12 bytes) || ciphertext||tag`. */
@Serializable
data class StoredBoard(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = DEFAULT_BOARD_PORT,
    val clientId: String? = null,
    val sealedSecret: String? = null,
)

fun StoredBoard.toDomain() = Board(id = id, name = name, host = host, port = port)
