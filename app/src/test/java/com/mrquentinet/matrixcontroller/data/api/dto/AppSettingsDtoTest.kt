package com.mrquentinet.matrixcontroller.data.api.dto

import com.mrquentinet.matrixcontroller.domain.AppSettingSchema
import com.mrquentinet.matrixcontroller.domain.AppSettingType
import com.mrquentinet.matrixcontroller.domain.SettingValue
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

private val JSON = Json { ignoreUnknownKeys = true }

class AppSettingsDtoTest {

    @Test
    fun `parses bool, int, string and color values by the schema's declared type`() {
        val schema = listOf(
            AppSettingSchema("on", "On", AppSettingType.Bool),
            AppSettingSchema("size", "Size", AppSettingType.IntType, min = 1, max = 2),
            AppSettingSchema("label", "Label", AppSettingType.StringType, maxLen = 31),
            AppSettingSchema("color", "Color", AppSettingType.ColorType, min = 0, max = 16_777_215),
        )
        val payload = """{"on":true,"size":2,"label":"hi","color":46335}"""

        val values = parseSettingValues(JSON, schema, payload)

        assertEquals(SettingValue.BoolValue(true), values["on"])
        assertEquals(SettingValue.IntValue(2), values["size"])
        assertEquals(SettingValue.StringValue("hi"), values["label"])
        assertEquals(SettingValue.IntValue(46_335), values["color"])
    }

    @Test
    fun `an unrecognized setting type falls back to a raw value instead of crashing`() {
        val schema = listOf(AppSettingSchema("mode", "Mode", AppSettingType.Unknown("enum")))
        val payload = """{"mode":"rainbow"}"""

        val values = parseSettingValues(JSON, schema, payload)

        assertEquals(SettingValue.RawValue("\"rainbow\""), values["mode"])
    }

    @Test
    fun `a key missing from the schema falls back to a raw value instead of throwing`() {
        val payload = """{"ghost":123}"""

        val values = parseSettingValues(JSON, emptyList(), payload)

        assertEquals(SettingValue.RawValue("123"), values["ghost"])
    }

    @Test
    fun `a value whose JSON shape does not match its declared bool type is a raw value`() {
        val schema = listOf(AppSettingSchema("on", "On", AppSettingType.Bool))
        // Not "true"/"false" — booleanOrNull rejects it, so this can't silently become a bool.
        val payload = """{"on":"yes"}"""

        val values = parseSettingValues(JSON, schema, payload)

        assertEquals(SettingValue.RawValue("\"yes\""), values["on"])
    }

    @Test
    fun `encodes only bool, int and string edits and skips raw values`() {
        val changes = mapOf(
            "on" to SettingValue.BoolValue(true),
            "size" to SettingValue.IntValue(2),
            "label" to SettingValue.StringValue("hi"),
            "ghost" to SettingValue.RawValue("123"),
        )

        val body = encodeSettingChanges(JSON, changes)
        val roundTripped = JSON.parseToJsonElement(body)

        assertEquals(
            JSON.parseToJsonElement("""{"on":true,"size":2,"label":"hi"}"""),
            roundTripped,
        )
    }

    @Test
    fun `encoding an empty change set produces an empty JSON object`() {
        assertEquals("{}", encodeSettingChanges(JSON, emptyMap()))
    }
}
