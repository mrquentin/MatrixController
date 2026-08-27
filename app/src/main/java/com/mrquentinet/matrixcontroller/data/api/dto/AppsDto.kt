package com.mrquentinet.matrixcontroller.data.api.dto

import com.mrquentinet.matrixcontroller.domain.AppSettingSchema
import com.mrquentinet.matrixcontroller.domain.AppSettingType
import com.mrquentinet.matrixcontroller.domain.BoardApp
import com.mrquentinet.matrixcontroller.domain.BoardApps
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SettingSchemaDto(
    val key: String,
    val label: String,
    val type: String,
    val min: Int? = null,
    val max: Int? = null,
    @SerialName("max_len") val maxLen: Int? = null,
)

/** `GET /api/app` and `POST /api/app` both answer with the short keys `index` + `name` (no
 *  `settings` — that only ever comes from `GET /api/apps`). [settings] defaults to empty so the
 *  same DTO/mapping serves both shapes. */
@Serializable
data class AppDto(val index: Int, val name: String, val settings: List<SettingSchemaDto> = emptyList())

@Serializable
data class AppsDto(
    val apps: List<AppDto>,
    @SerialName("active_index") val activeIndex: Int,
    @SerialName("active_name") val activeName: String,
)

/** Unrecognized `type` values render read-only rather than failing to parse — the board can add
 *  new setting types at any firmware version. */
private fun SettingSchemaDto.toDomainType(): AppSettingType = when (type) {
    "bool" -> AppSettingType.Bool
    "int" -> AppSettingType.IntType
    "string" -> AppSettingType.StringType
    "color" -> AppSettingType.ColorType
    else -> AppSettingType.Unknown(type)
}

fun SettingSchemaDto.toDomain() = AppSettingSchema(
    key = key,
    label = label,
    type = toDomainType(),
    min = min,
    max = max,
    maxLen = maxLen,
)

fun AppDto.toDomain() = BoardApp(index = index, name = name, settings = settings.map { it.toDomain() })

fun AppsDto.toDomain() = BoardApps(apps = apps.map(AppDto::toDomain), activeIndex = activeIndex)
