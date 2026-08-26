package com.mrquentinet.matrixcontroller.core

private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase hex, one allocation for the char buffer plus the resulting String. */
fun ByteArray.toHexLower(): String {
    val chars = CharArray(size * 2)
    var out = 0
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        chars[out++] = HEX_DIGITS[value ushr 4]
        chars[out++] = HEX_DIGITS[value and 0x0F]
    }
    return String(chars)
}

/**
 * Decodes a hex string to its raw bytes. Deliberately *not* named `hexToByteArray`, which is an
 * experimental `kotlin.text` extension that would shadow-clash with this one.
 *
 * @throws IllegalArgumentException on an odd length or a non-hex character.
 */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string length must be even, was $length" }
    val out = ByteArray(length / 2)
    var index = 0
    while (index < length) {
        out[index / 2] = ((hexDigit(this[index]) shl 4) or hexDigit(this[index + 1])).toByte()
        index += 2
    }
    return out
}

private fun hexDigit(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> throw IllegalArgumentException("not a hex digit: '$char'")
}
