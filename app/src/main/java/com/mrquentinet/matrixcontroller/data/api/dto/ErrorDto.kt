package com.mrquentinet.matrixcontroller.data.api.dto

import kotlinx.serialization.Serializable

/** Every firmware error response is exactly `{"error":"<code>"}`. */
@Serializable
data class ErrorDto(val error: String)
