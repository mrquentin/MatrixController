package com.mrquentinet.matrixcontroller.data.api

import com.mrquentinet.matrixcontroller.domain.BoardCredentials
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known-answer vectors lifted verbatim from the firmware's own suite,
 * `E:/cpp/m4-webserver/test/test_apiauth/test_main.cpp`. The test secret is the 32 bytes
 * 0x00..0x1f. These passing is the proof that the Kotlin signer is byte-compatible with the
 * board; a failure here means the app can never authenticate, whatever the UI does.
 */
class RequestSignerTest {

    private val secretHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val signer = RequestSigner()

    @Test
    fun `canonical request has the firmware's exact layout and length`() {
        val canonical = signer.canonicalRequest(
            method = "GET",
            target = "/api/status",
            ts = "1700000000",
            nonce = "0011223344556677",
            body = ByteArray(0),
        )

        assertEquals(
            "GET\n/api/status\n1700000000\n0011223344556677\n" +
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            canonical,
        )
        assertEquals(108, canonical.length)
    }

    @Test
    fun `signs a GET with the empty-body hash`() {
        assertEquals(
            "f7b2089f804512fe2d2f9793e35f28423118ef337c73f73f08fcaa0cefb3a6ce",
            signer.sign(
                secretHex = secretHex,
                method = "GET",
                target = "/api/status",
                ts = "1700000000",
                nonce = "0011223344556677",
                body = ByteArray(0),
            ),
        )
    }

    @Test
    fun `signs a POST over its exact body bytes`() {
        assertEquals(
            "a6ea34944a1ff180c29f80f54f15dcc13d815b2a3c96881cf7e0722debf1e226",
            signer.sign(
                secretHex = secretHex,
                method = "POST",
                target = "/api/led",
                ts = "1700000123",
                nonce = "89abcdef01234567",
                body = """{"on":true}""".toByteArray(),
            ),
        )
    }

    @Test
    fun `authorization header carries exactly the four accepted parameters`() {
        val fixed = RequestSigner(
            epochSeconds = { 1_700_000_000L },
            random = object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) {
                    bytes.fill(0x2a)
                }
            },
        )

        val header = fixed.authorizationHeader(
            credentials = BoardCredentials("0123456789abcdef", secretHex),
            method = "GET",
            target = "/api/status",
            body = ByteArray(0),
        )

        // `matches`, not a partial find: the firmware parser rejects unknown parameters and any
        // stray spacing.
        assertTrue(
            header,
            Regex("""HMAC id=[0-9a-f]{16},ts=\d{1,10},nonce=[0-9a-f]{16},sig=[0-9a-f]{64}""")
                .matches(header),
        )
        assertEquals(
            "HMAC id=0123456789abcdef,ts=1700000000,nonce=2a2a2a2a2a2a2a2a," +
                signer.sign(secretHex, "GET", "/api/status", "1700000000", "2a2a2a2a2a2a2a2a", ByteArray(0))
                    .let { "sig=$it" },
            header,
        )
    }
}
