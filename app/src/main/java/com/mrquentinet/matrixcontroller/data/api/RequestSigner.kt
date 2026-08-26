package com.mrquentinet.matrixcontroller.data.api

import com.mrquentinet.matrixcontroller.core.hexToBytes
import com.mrquentinet.matrixcontroller.core.toHexLower
import com.mrquentinet.matrixcontroller.domain.BoardCredentials
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Produces the board's `Authorization: HMAC …` header.
 *
 * The canonical string is exactly five fields joined by four literal LF bytes and carries no
 * trailing newline:
 *
 * ```
 * METHOD \n TARGET \n TS \n NONCE \n SHA256HEX(body)
 * ```
 *
 * The seams are `internal` so the firmware's own known-answer vectors can be asserted directly.
 */
class RequestSigner(
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: SecureRandom = SecureRandom(),
) {
    fun authorizationHeader(
        credentials: BoardCredentials,
        method: String,
        target: String,
        body: ByteArray,
    ): String {
        val ts = epochSeconds().toString()
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes).toHexLower()
        val sig = sign(credentials.secretHex, method, target, ts, nonce, body)
        // The firmware rejects unknown parameters and any stray spacing: exactly id, ts, nonce, sig.
        return "HMAC id=${credentials.clientId},ts=$ts,nonce=$nonce,sig=$sig"
    }

    internal fun canonicalRequest(
        method: String,
        target: String,
        ts: String,
        nonce: String,
        body: ByteArray,
    ): String = buildString {
        append(method.uppercase())
        append('\n')
        append(target)
        append('\n')
        append(ts)
        append('\n')
        append(nonce)
        append('\n')
        // Computed unconditionally, so GET/DELETE sign SHA256("").
        append(MessageDigest.getInstance("SHA-256").digest(body).toHexLower())
    }

    internal fun sign(
        secretHex: String,
        method: String,
        target: String,
        ts: String,
        nonce: String,
        body: ByteArray,
    ): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        // Load-bearing: the HMAC key is the 32 *decoded* bytes of the secret, never the 64 ASCII
        // characters of its hex representation.
        mac.init(SecretKeySpec(secretHex.hexToBytes(), HMAC_ALGORITHM))
        val canonical = canonicalRequest(method, target, ts, nonce, body)
        return mac.doFinal(canonical.toByteArray(Charsets.US_ASCII)).toHexLower()
    }

    private companion object {
        const val NONCE_BYTES = 8
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
