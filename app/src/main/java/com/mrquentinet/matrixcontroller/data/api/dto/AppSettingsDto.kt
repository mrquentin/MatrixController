package com.mrquentinet.matrixcontroller.data.api.dto

import com.mrquentinet.matrixcontroller.domain.AppSettingSchema
import com.mrquentinet.matrixcontroller.domain.AppSettingType
import com.mrquentinet.matrixcontroller.domain.SettingValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * `GET`/`POST /api/apps/<index>/settings` bodies are a flat JSON object of heterogeneous
 * bool/int/string values — there is no single [kotlinx.serialization] shape to decode that
 * generically, so this parses the raw [JsonObject] and interprets each value using the matching
 * [AppSettingSchema.type] (falling back to [SettingValue.RawValue] rather than throwing when a
 * key is missing from the schema or its JSON shape doesn't match the declared type).
 */
fun parseSettingValues(
    json: Json,
    schema: List<AppSettingSchema>,
    payload: String,
): Map<String, SettingValue> {
    val typeByKey = schema.associate { it.key to it.type }
    val obj = json.parseToJsonElement(payload) as? JsonObject ?: return emptyMap()
    return obj.mapValues { (key, element) -> toSettingValue(typeByKey[key], element) }
}

private fun toSettingValue(type: AppSettingType?, element: JsonElement): SettingValue {
    val primitive = element as? JsonPrimitive
    val parsed = when (type) {
        AppSettingType.Bool -> primitive?.booleanOrNull?.let(SettingValue::BoolValue)
        AppSettingType.IntType, AppSettingType.ColorType ->
            primitive?.intOrNull?.let(SettingValue::IntValue)
        AppSettingType.StringType -> primitive?.contentOrNull?.let(SettingValue::StringValue)
        is AppSettingType.Unknown, null -> null
    }
    return parsed ?: SettingValue.RawValue(element.toString())
}

/**
 * Encodes only the touched keys for `POST /api/apps/<index>/settings`. [SettingValue.RawValue]
 * entries are silently dropped: they only ever come from read-only/unknown-type widgets, which
 * never produce edits, so one appearing here would be a UI bug rather than something to encode.
 */
fun encodeSettingChanges(json: Json, changes: Map<String, SettingValue>): String {
    val fields = changes.mapNotNull { (key, value) ->
        val primitive = when (value) {
            is SettingValue.BoolValue -> JsonPrimitive(value.value)
            is SettingValue.IntValue -> JsonPrimitive(value.value)
            is SettingValue.StringValue -> JsonPrimitive(value.value)
            is SettingValue.RawValue -> null
        }
        primitive?.let { key to it }
    }
    return json.encodeToString(JsonObject.serializer(), JsonObject(fields.toMap()))
}
