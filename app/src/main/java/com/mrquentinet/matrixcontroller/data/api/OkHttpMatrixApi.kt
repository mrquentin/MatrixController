package com.mrquentinet.matrixcontroller.data.api

import android.util.Log
import com.mrquentinet.matrixcontroller.data.api.dto.AppDto
import com.mrquentinet.matrixcontroller.data.api.dto.AppsDto
import com.mrquentinet.matrixcontroller.data.api.dto.DeviceInfoDto
import com.mrquentinet.matrixcontroller.data.api.dto.ErrorDto
import com.mrquentinet.matrixcontroller.data.api.dto.MetricsDto
import com.mrquentinet.matrixcontroller.data.api.dto.PairDto
import com.mrquentinet.matrixcontroller.data.api.dto.StatusDto
import com.mrquentinet.matrixcontroller.data.api.dto.encodeSettingChanges
import com.mrquentinet.matrixcontroller.data.api.dto.parseSettingValues
import com.mrquentinet.matrixcontroller.data.api.dto.toDomain
import com.mrquentinet.matrixcontroller.domain.AppSettingSchema
import com.mrquentinet.matrixcontroller.domain.Board
import com.mrquentinet.matrixcontroller.domain.BoardApp
import com.mrquentinet.matrixcontroller.domain.BoardApps
import com.mrquentinet.matrixcontroller.domain.BoardCredentials
import com.mrquentinet.matrixcontroller.domain.BoardError
import com.mrquentinet.matrixcontroller.domain.BoardException
import com.mrquentinet.matrixcontroller.domain.BoardMetrics
import com.mrquentinet.matrixcontroller.domain.BoardStatus
import com.mrquentinet.matrixcontroller.domain.DeviceInfo
import com.mrquentinet.matrixcontroller.domain.MatrixApi
import com.mrquentinet.matrixcontroller.domain.SettingValue
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private val EMPTY_BODY = ByteArray(0)

class OkHttpMatrixApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val signer: RequestSigner,
) : MatrixApi {

    override suspend fun deviceInfo(board: Board): DeviceInfo =
        call(board, "/", "GET", DeviceInfoDto.serializer()).toDomain()

    override suspend fun pair(board: Board): BoardCredentials =
        // The firmware ignores the /pair body entirely; an empty POST body is what m4client.py sends.
        call(board, "/pair", "POST", PairDto.serializer(), bodyJson = "").toDomain()

    override suspend fun status(board: Board, credentials: BoardCredentials): BoardStatus =
        call(board, "/api/status", "GET", StatusDto.serializer(), credentials).toDomain()

    override suspend fun apps(board: Board, credentials: BoardCredentials): BoardApps =
        call(board, "/api/apps", "GET", AppsDto.serializer(), credentials).toDomain()

    override suspend fun activeApp(board: Board, credentials: BoardCredentials): BoardApp =
        call(board, "/api/app", "GET", AppDto.serializer(), credentials).toDomain()

    override suspend fun setActiveApp(
        board: Board,
        credentials: BoardCredentials,
        index: Int,
    ): BoardApp = call(
        board = board,
        target = "/api/app",
        method = "POST",
        serializer = AppDto.serializer(),
        credentials = credentials,
        // Well under the firmware's 256-byte body cap.
        bodyJson = """{"index":$index}""",
    ).toDomain()

    override suspend fun metrics(board: Board, credentials: BoardCredentials): BoardMetrics? =
        callOrNull(
            board = board,
            target = "/api/metrics",
            method = "GET",
            serializer = MetricsDto.serializer(),
            credentials = credentials,
        )?.toDomain()

    // Heterogeneous bool/int/string JSON objects, not a fixed shape — bypass the `call`/`decode`
    // helpers and parse/encode via the schema directly.
    override suspend fun appSettings(
        board: Board,
        credentials: BoardCredentials,
        index: Int,
        schema: List<AppSettingSchema>,
    ): Map<String, SettingValue> {
        val payload = execute(board, "/api/apps/$index/settings", "GET", credentials, null)
        return parseSettingValues(json, schema, payload)
    }

    override suspend fun updateAppSettings(
        board: Board,
        credentials: BoardCredentials,
        index: Int,
        schema: List<AppSettingSchema>,
        changes: Map<String, SettingValue>,
    ): Map<String, SettingValue> {
        val body = encodeSettingChanges(json, changes)
        val payload = execute(board, "/api/apps/$index/settings", "POST", credentials, body)
        // Atomic on the board and mirrors the GET shape on success — the caller's new baseline.
        return parseSettingValues(json, schema, payload)
    }

    private suspend fun <T> call(
        board: Board,
        target: String,
        method: String,
        serializer: DeserializationStrategy<T>,
        credentials: BoardCredentials? = null,
        bodyJson: String? = null,
    ): T = decode(serializer, execute(board, target, method, credentials, bodyJson))

    /** Returns null when the endpoint is absent — used only by `/api/metrics`. */
    private suspend fun <T> callOrNull(
        board: Board,
        target: String,
        method: String,
        serializer: DeserializationStrategy<T>,
        credentials: BoardCredentials?,
    ): T? {
        val payload = try {
            execute(board, target, method, credentials, null)
        } catch (e: BoardException) {
            if (e.error == BoardError.EndpointMissing) return null else throw e
        }
        return decode(serializer, payload)
    }

    /**
     * Performs one request and returns its body. The signed `target` and the request URL are built
     * from the same string, so the wire target and the signed target cannot diverge.
     */
    private suspend fun execute(
        board: Board,
        target: String,
        method: String,
        credentials: BoardCredentials?,
        bodyJson: String?,
        // The board's per-client timestamp high-water mark and 24-entry nonce cache mean a
        // `stale_timestamp`/`replay_detected` can be a genuine one-off (clock drift settling,
        // a nonce collision) rather than a real client bug — retry exactly once with a fresh
        // ts/nonce (built fresh below on every call) before surfacing it.
        allowAuthRetry: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        val bodyBytes = bodyJson?.toByteArray(Charsets.UTF_8) ?: EMPTY_BODY
        val builder = Request.Builder().url(board.baseUrl + target)
        if (bodyJson == null) {
            builder.method(method, null)
        } else {
            // The firmware ignores the request Content-Type; OkHttp needs a MediaType regardless.
            builder.method(method, bodyBytes.toRequestBody(JSON_MEDIA_TYPE))
        }
        if (credentials != null) {
            builder.header(
                "Authorization",
                signer.authorizationHeader(credentials, method, target, bodyBytes),
            )
        }

        val startedAt = System.currentTimeMillis()
        Log.d(
            BOARD_HTTP_LOG_TAG,
            "$method $target -> ${board.baseUrl}$target " +
                "(authenticated=${credentials != null}, bodyBytes=${bodyBytes.size})",
        )

        val (code, payload) = try {
            client.newCall(builder.build()).execute().use { response ->
                response.code to response.body.string()
            }
        } catch (e: IOException) {
            val elapsedMs = System.currentTimeMillis() - startedAt
            Log.e(
                BOARD_HTTP_LOG_TAG,
                "$method $target FAILED after ${elapsedMs}ms: ${describeFailure(e)}",
                e,
            )
            throw BoardException(BoardError.Unreachable, e)
        }
        val elapsedMs = System.currentTimeMillis() - startedAt

        if (code !in 200..299) {
            val error = mapError(code, errorCode(payload))
            if (allowAuthRetry && credentials != null &&
                (error == BoardError.ClockSkew || error == BoardError.ReplayRejected)
            ) {
                Log.w(
                    BOARD_HTTP_LOG_TAG,
                    "$method $target -> $error after ${elapsedMs}ms, retrying once with a " +
                        "fresh timestamp/nonce",
                )
                return@withContext execute(
                    board, target, method, credentials, bodyJson, allowAuthRetry = false,
                )
            }
            // The error envelope is always just {"error":"<code>"} — never a secret — safe to log
            // in full. 2xx bodies are deliberately NOT logged in full: /pair's response carries
            // the 64-hex board secret.
            Log.w(
                BOARD_HTTP_LOG_TAG,
                "$method $target -> HTTP $code after ${elapsedMs}ms, body=$payload -> $error",
            )
            throw BoardException(error)
        }

        Log.d(
            BOARD_HTTP_LOG_TAG,
            "$method $target -> HTTP $code after ${elapsedMs}ms (${payload.length} chars)",
        )
        payload
    }

    /** Classifies the transport failure so the log shows *why*, not just that it failed. */
    private fun describeFailure(e: IOException): String = when (e) {
        is UnknownHostException -> "DNS lookup failed (UnknownHostException): ${e.message}"
        is ConnectException -> "connection refused/unreachable (ConnectException): ${e.message}"
        is SocketTimeoutException -> "timed out (SocketTimeoutException): ${e.message}"
        is SSLException -> "TLS failure (SSLException) — unexpected, the board is plain HTTP: ${e.message}"
        else -> "${e::class.java.name}: ${e.message}"
    }

    private fun <T> decode(serializer: DeserializationStrategy<T>, payload: String): T = try {
        json.decodeFromString(serializer, payload)
    } catch (e: SerializationException) {
        Log.e(
            BOARD_HTTP_LOG_TAG,
            "response did not match the expected shape: ${e.message}. " +
                "payload=${payload.take(300)}",
            e,
        )
        throw BoardException(BoardError.Malformed(e.message ?: "unparsable response"), e)
    }

    /** An error body that does not parse is tolerated: the HTTP code alone still classifies it. */
    private fun errorCode(payload: String): String? = try {
        json.decodeFromString(ErrorDto.serializer(), payload).error
    } catch (_: SerializationException) {
        null
    }
}

/**
 * `bad_signature` deliberately falls through to [BoardError.Server] rather than joining
 * `unknown_client` under [BoardError.CredentialsRejected]: it means the client's own signing is
 * wrong (a dev-facing bug), not that the board revoked a real pairing, and callers auto-clear
 * stored credentials on [BoardError.CredentialsRejected] — doing that for a signing bug would
 * destroy valid credentials while masking the actual defect.
 */
internal fun mapError(httpCode: Int, code: String?): BoardError = when (code) {
    "pairing_closed" -> BoardError.PairingClosed
    "too_many_clients" -> BoardError.TooManyClients
    "unknown_client" -> BoardError.CredentialsRejected
    "stale_timestamp" -> BoardError.ClockSkew
    "replay_detected" -> BoardError.ReplayRejected
    "clock_unavailable" -> BoardError.BoardClockUnavailable
    "unknown_endpoint" -> BoardError.EndpointMissing
    "invalid_setting_value" -> BoardError.InvalidSettingValue
    "no_recognized_settings" -> BoardError.NoRecognizedSettings
    "unknown_app_index" -> BoardError.UnknownAppIndex
    else -> BoardError.Server(httpCode, code)
}
