package com.mrquentinet.matrixcontroller.data.api

import com.mrquentinet.matrixcontroller.core.hexToBytes
import com.mrquentinet.matrixcontroller.core.toHexLower
import com.mrquentinet.matrixcontroller.domain.AppSettingSchema
import com.mrquentinet.matrixcontroller.domain.AppSettingType
import com.mrquentinet.matrixcontroller.domain.Board
import com.mrquentinet.matrixcontroller.domain.BoardCredentials
import com.mrquentinet.matrixcontroller.domain.BoardError
import com.mrquentinet.matrixcontroller.domain.BoardException
import com.mrquentinet.matrixcontroller.domain.SettingValue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private const val SECRET_HEX =
    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
private const val EMPTY_SHA256 =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

class OkHttpMatrixApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: OkHttpMatrixApi
    private lateinit var board: Board

    private val credentials = BoardCredentials("0123456789abcdef", SECRET_HEX)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        board = Board(id = "b1", name = "Matrix", host = server.hostName, port = server.port)
        api = OkHttpMatrixApi(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            signer = RequestSigner(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `apps reports the top-level active index and preserves order`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"apps":[{"index":0,"name":"Clock"},{"index":1,"name":"Weather"}],""" +
                    """"active_index":1,"active_name":"Weather"}""",
            )
        )

        val apps = api.apps(board, credentials)

        assertEquals(1, apps.activeIndex)
        assertEquals(listOf(0, 1), apps.apps.map { it.index })
        assertEquals(listOf("Clock", "Weather"), apps.apps.map { it.name })
    }

    @Test
    fun `setActiveApp posts the index body and trusts the response`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"index":1,"name":"Weather"}"""))

        val applied = api.setActiveApp(board, credentials, 1)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/app", request.target)
        assertEquals("""{"index":1}""", request.body?.utf8())
        assertEquals(1, applied.index)
        assertEquals("Weather", applied.name)
    }

    @Test
    fun `setActiveApp returns the board's index even when it differs from the request`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"index":0,"name":"Clock"}"""))

        assertEquals(0, api.setActiveApp(board, credentials, 1).index)
    }

    @Test
    fun `metrics is null when the firmware compiled the endpoint out`() = runTest {
        server.enqueue(MockResponse(code = 404, body = """{"error":"unknown_endpoint"}"""))

        assertNull(api.metrics(board, credentials))
    }

    @Test
    fun `unknown_endpoint on any other path is an error`() = runTest {
        server.enqueue(MockResponse(code = 404, body = """{"error":"unknown_endpoint"}"""))

        assertEquals(BoardError.EndpointMissing, errorFrom { api.status(board, credentials) })
    }

    @Test
    fun `maps the firmware's authentication and clock error codes`() = runTest {
        // stale_timestamp/replay_detected retry once before surfacing (fresh ts/nonce) — enqueue
        // two identical failures so the retry doesn't consume a later assertion's response.
        server.enqueue(MockResponse(code = 401, body = """{"error":"stale_timestamp"}"""))
        server.enqueue(MockResponse(code = 401, body = """{"error":"stale_timestamp"}"""))
        assertEquals(BoardError.ClockSkew, errorFrom { api.status(board, credentials) })

        server.enqueue(MockResponse(code = 401, body = """{"error":"unknown_client"}"""))
        assertEquals(BoardError.CredentialsRejected, errorFrom { api.status(board, credentials) })

        // bad_signature means the client's own signing is wrong, not that the board revoked a
        // real pairing — it must NOT be folded into CredentialsRejected, or a signing bug would
        // silently wipe valid credentials and mask the real defect.
        server.enqueue(MockResponse(code = 401, body = """{"error":"bad_signature"}"""))
        assertEquals(
            BoardError.Server(401, "bad_signature"),
            errorFrom { api.apps(board, credentials) },
        )

        server.enqueue(MockResponse(code = 401, body = """{"error":"replay_detected"}"""))
        server.enqueue(MockResponse(code = 401, body = """{"error":"replay_detected"}"""))
        assertEquals(BoardError.ReplayRejected, errorFrom { api.apps(board, credentials) })

        server.enqueue(MockResponse(code = 503, body = """{"error":"clock_unavailable"}"""))
        assertEquals(
            BoardError.BoardClockUnavailable,
            errorFrom { api.status(board, credentials) },
        )
    }

    @Test
    fun `maps the pairing error codes`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":"pairing_closed"}"""))
        assertEquals(BoardError.PairingClosed, errorFrom { api.pair(board) })

        server.enqueue(MockResponse(code = 409, body = """{"error":"too_many_clients"}"""))
        assertEquals(BoardError.TooManyClients, errorFrom { api.pair(board) })
    }

    @Test
    fun `an unparsable error body still classifies by http code`() = runTest {
        server.enqueue(MockResponse(code = 500, body = "not json"))

        assertEquals(BoardError.Server(500, null), errorFrom { api.apps(board, credentials) })
    }

    @Test
    fun `an unreachable board preserves the real exception as the cause`() = runTest {
        // Nothing is listening on this port once the server is closed, so the connection is
        // refused immediately: a real ConnectException, not a manufactured one.
        server.close()

        val thrown = try {
            api.deviceInfo(board)
            null
        } catch (e: BoardException) {
            e
        }

        assertEquals(BoardError.Unreachable, thrown?.error)
        assertTrue(
            "expected the underlying IOException to be preserved as the cause, was ${thrown?.cause}",
            thrown?.cause is java.io.IOException,
        )
    }

    @Test
    fun `a response that does not parse preserves the serialization exception as the cause`() =
        runTest {
            server.enqueue(MockResponse(code = 200, body = "not json"))

            val thrown = try {
                api.deviceInfo(board)
                null
            } catch (e: BoardException) {
                e
            }

            assertTrue(thrown?.error is BoardError.Malformed)
            assertTrue(
                "expected the SerializationException to be preserved as the cause, was ${thrown?.cause}",
                thrown?.cause is kotlinx.serialization.SerializationException,
            )
        }

    @Test
    fun `signs the exact target and the empty-body hash`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"uptime_s":12,"led":false,"rssi":-57,"paired_clients":1,"time":170}""",
            )
        )

        api.status(board, credentials)

        val header = server.takeRequest().headers["Authorization"]!!
        val params = header.removePrefix("HMAC ").split(',')
            .associate { it.substringBefore('=') to it.substringAfter('=') }

        // Independent recomputation: proves the client signs GET, the literal target and SHA256("").
        val canonical = "GET\n/api/status\n${params["ts"]}\n${params["nonce"]}\n$EMPTY_SHA256"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(SECRET_HEX.hexToBytes(), "HmacSHA256"))
        }
        assertEquals(
            mac.doFinal(canonical.toByteArray(Charsets.US_ASCII)).toHexLower(),
            params["sig"],
        )
        assertEquals(credentials.clientId, params["id"])
        assertEquals(setOf("id", "ts", "nonce", "sig"), params.keys)
    }

    @Test
    fun `pair parses the credentials handed out by the board`() = runTest {
        val secret = "a".repeat(64)
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"client_id":"00112233445566aa","secret":"$secret",""" +
                    """"algorithm":"HMAC-SHA256"}""",
            )
        )

        val paired = api.pair(board)

        assertEquals("/pair", server.takeRequest().target)
        assertEquals("00112233445566aa", paired.clientId)
        assertEquals(64, paired.secretHex.length)
        assertEquals(secret, paired.secretHex)
    }

    @Test
    fun `pair is sent without an Authorization header`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"client_id":"00112233445566aa","secret":"${"b".repeat(64)}",""" +
                    """"algorithm":"HMAC-SHA256"}""",
            )
        )

        api.pair(board)

        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `deviceInfo needs no credentials`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"device":"matrixfaces","firmware_version":"dev","paired_clients":0,""" +
                    """"pairing_open":true,"pairing_expires_in":42,"clock_synced":true}""",
            )
        )

        val info = api.deviceInfo(board)

        assertTrue(info.pairingOpen)
        assertEquals(42L, info.pairingExpiresInSeconds)
        assertEquals("dev", info.firmwareVersion)
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `appSettings decodes each value using the schema's declared type`() = runTest {
        val schema = listOf(
            AppSettingSchema("color", "Text color", AppSettingType.ColorType, min = 0, max = 16_777_215),
            AppSettingSchema("size", "Text scale", AppSettingType.IntType, min = 1, max = 2),
        )
        server.enqueue(MockResponse(code = 200, body = """{"color":46335,"size":1}"""))

        val values = api.appSettings(board, credentials, 0, schema)

        assertEquals(SettingValue.IntValue(46_335), values["color"])
        assertEquals(SettingValue.IntValue(1), values["size"])
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/apps/0/settings", request.target)
    }

    @Test
    fun `appSettings falls back to a raw value when the JSON shape does not match the schema`() =
        runTest {
            // An array where a string is declared — never a JsonPrimitive, so it can never be
            // coerced into a bool/int/string and must render read-only instead of crashing.
            val schema = listOf(AppSettingSchema("text", "Display text", AppSettingType.StringType))
            server.enqueue(MockResponse(code = 200, body = """{"text":[1,2,3]}"""))

            val values = api.appSettings(board, credentials, 1, schema)

            assertEquals(SettingValue.RawValue("[1,2,3]"), values["text"])
        }

    @Test
    fun `updateAppSettings posts only the given keys and returns the applied map`() = runTest {
        val schema = listOf(
            AppSettingSchema("color", "Text color", AppSettingType.ColorType, min = 0, max = 16_777_215),
            AppSettingSchema("size", "Text scale", AppSettingType.IntType, min = 1, max = 2),
        )
        server.enqueue(MockResponse(code = 200, body = """{"color":16711680,"size":2}"""))

        val applied = api.updateAppSettings(
            board, credentials, 1, schema, mapOf("size" to SettingValue.IntValue(2)),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/apps/1/settings", request.target)
        assertEquals("""{"size":2}""", request.body?.utf8())
        assertEquals(SettingValue.IntValue(16_711_680), applied["color"])
        assertEquals(SettingValue.IntValue(2), applied["size"])
    }

    @Test
    fun `retries once with a fresh nonce and timestamp on stale_timestamp, then succeeds`() =
        runTest {
            server.enqueue(MockResponse(code = 401, body = """{"error":"stale_timestamp"}"""))
            server.enqueue(
                MockResponse(
                    code = 200,
                    body = """{"uptime_s":1,"led":false,"rssi":-50,"paired_clients":1,"time":1}""",
                )
            )

            val status = api.status(board, credentials)

            assertEquals(1L, status.uptimeSeconds)
            val first = server.takeRequest()
            val second = server.takeRequest()
            val firstNonce = first.headers["Authorization"]!!.substringAfter("nonce=").substringBefore(',')
            val secondNonce = second.headers["Authorization"]!!.substringAfter("nonce=").substringBefore(',')
            assertTrue("retry must sign with a fresh nonce", firstNonce != secondNonce)
        }

    @Test
    fun `gives up after exactly one retry`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":"replay_detected"}"""))
        server.enqueue(MockResponse(code = 401, body = """{"error":"replay_detected"}"""))

        assertEquals(BoardError.ReplayRejected, errorFrom { api.status(board, credentials) })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `maps the settings-specific error codes`() = runTest {
        val schema = listOf(AppSettingSchema("size", "Text scale", AppSettingType.IntType, min = 1, max = 2))

        server.enqueue(MockResponse(code = 400, body = """{"error":"invalid_setting_value"}"""))
        assertEquals(
            BoardError.InvalidSettingValue,
            errorFrom {
                api.updateAppSettings(board, credentials, 0, schema, mapOf("size" to SettingValue.IntValue(9)))
            },
        )

        server.enqueue(MockResponse(code = 400, body = """{"error":"no_recognized_settings"}"""))
        assertEquals(
            BoardError.NoRecognizedSettings,
            errorFrom {
                api.updateAppSettings(board, credentials, 0, schema, mapOf("ghost" to SettingValue.IntValue(1)))
            },
        )

        server.enqueue(MockResponse(code = 404, body = """{"error":"unknown_app_index"}"""))
        assertEquals(
            BoardError.UnknownAppIndex,
            errorFrom { api.appSettings(board, credentials, 99, schema) },
        )
    }

    private suspend fun errorFrom(call: suspend () -> Any?): BoardError {
        try {
            call()
        } catch (e: BoardException) {
            return e.error
        }
        fail("expected a BoardException")
        error("unreachable")
    }
}
