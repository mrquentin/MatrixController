package com.mrquentinet.matrixcontroller.data.api.dto

import com.mrquentinet.matrixcontroller.domain.BoardCredentials
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PairDto(
    @SerialName("client_id") val clientId: String,
    val secret: String,
    val algorithm: String,
)

fun PairDto.toDomain() = BoardCredentials(clientId = clientId, secretHex = secret)
