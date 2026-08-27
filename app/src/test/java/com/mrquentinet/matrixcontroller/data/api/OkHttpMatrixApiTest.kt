package com.mrquentinet.matrixcontroller.data.api

import com.mrquentinet.matrixcontroller.core.hexToBytes
import com.mrquentinet.matrixcontroller.core.toHexLower
import com.mrquentinet.matrixcontroller.domain.Board
import com.mrquentinet.matrixcontroller.domain.BoardCredentials
import com.mrquentinet.matrixcontroller.domain.BoardError
import com.mrquentinet.matrixcontroller.domain.BoardException
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
        server.enqueue(MockResponse(code = 401, body = """{"error":"stale_timestamp"}"""))
        assertEquals(BoardError.ClockSkew, errorFrom { api.status(board, credentials) })

        server.enqueue(MockResponse(code = 401, body = """{"error":"unknown_client"}"""))
        assertEquals(BoardError.CredentialsRejected, errorFrom { api.status(board, credentials) })

        server.enqueue(MockResponse(code = 401, body = """{"error":"bad_signature"}"""))
        assertEquals(BoardError.CredentialsRejected, errorFrom { api.apps(board, credentials) })

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
