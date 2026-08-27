package com.mrquentinet.matrixcontroller.domain

/**
 * The board's settings schemas are fully dynamic — new apps and new setting types can appear at
 * any firmware version — so this is a sealed type with an explicit [Unknown] fallback rather than
 * an enum. An enum would force a compile-time list of every type the board could ever report; a
 * new wire value would either crash `valueOf` or silently coerce into the wrong bucket. [Unknown]
 * lets the UI render the setting read-only instead.
 */
sealed interface AppSettingType {
    data object Bool : AppSettingType
    data object IntType : AppSettingType
    data object StringType : AppSettingType

    /** Wire-identical to [IntType] (packed 24-bit `0xRRGGBB`, no alpha) — separate only so the UI
     *  picks a colour widget instead of a plain number widget. */
    data object ColorType : AppSettingType

    data class Unknown(val raw: String) : AppSettingType
}

/**
 * One entry from an app's `settings` array in `GET /api/apps`. [min]/[max] are only meaningful for
 * [AppSettingType.IntType]/[AppSettingType.ColorType]; [maxLen] only for [AppSettingType.StringType].
 */
data class AppSettingSchema(
    val key: String,
    val label: String,
    val type: AppSettingType,
    val min: Int? = null,
    val max: Int? = null,
    val maxLen: Int? = null,
)

/**
 * A setting's current value, keyed by [AppSettingSchema.key]. Board booleans/ints/strings map
 * 1:1; [RawValue] is the fallback for an [AppSettingType.Unknown] entry or a value whose JSON
 * shape didn't match its declared type — kept verbatim (its JSON text) so the UI can show it
 * read-only instead of crashing. The UI never produces edits for [RawValue] entries.
 */
sealed interface SettingValue {
    data class BoolValue(val value: Boolean) : SettingValue
    data class IntValue(val value: Int) : SettingValue
    data class StringValue(val value: String) : SettingValue
    data class RawValue(val json: String) : SettingValue
}
