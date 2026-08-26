package com.mrquentinet.matrixcontroller.data.api.dto

import com.mrquentinet.matrixcontroller.domain.BoardApp
import com.mrquentinet.matrixcontroller.domain.BoardApps
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/app` and `POST /api/app` both answer with the short keys `index` + `name`. */
@Serializable
data class AppDto(val index: Int, val name: String)

@Serializable
data class AppsDto(
    val apps: List<AppDto>,
    @SerialName("active_index") val activeIndex: Int,
    @SerialName("active_name") val activeName: String,
)

fun AppDto.toDomain() = BoardApp(index = index, name = name)

fun AppsDto.toDomain() = BoardApps(apps = apps.map(AppDto::toDomain), activeIndex = activeIndex)
