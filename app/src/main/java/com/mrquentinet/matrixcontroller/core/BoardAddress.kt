package com.mrquentinet.matrixcontroller.core

data class BoardAddress(val host: String, val port: Int)

const val DEFAULT_BOARD_PORT = 80

/**
 * Parses a user-entered LAN address into host + port, or returns null when it cannot be a valid
 * board address. Accepts `host`, `host:port` and a `http://` prefix; rejects `https://` outright
 * because the firmware has no TLS.
 */
fun parseBoardAddress(raw: String): BoardAddress? {
    var text = raw.trim()
    if (text.isEmpty()) return null
    if (text.startsWith("https://", ignoreCase = true)) return null
    if (text.startsWith("http://", ignoreCase = true)) text = text.substring("http://".length)
    text = text.removeSuffix("/")

    var port = DEFAULT_BOARD_PORT
    val colon = text.lastIndexOf(':')
    if (colon >= 0) {
        port = text.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        text = text.substring(0, colon)
    }

    if (text.isBlank()) return null
    if (text.any { it.isWhitespace() || it == '/' }) return null
    return BoardAddress(text, port)
}
