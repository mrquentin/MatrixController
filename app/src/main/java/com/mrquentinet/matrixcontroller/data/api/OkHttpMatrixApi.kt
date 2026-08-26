package com.mrquentinet.matrixcontroller.data.api

import com.mrquentinet.matrixcontroller.data.api.dto.AppDto
import com.mrquentinet.matrixcontroller.data.api.dto.AppsDto
import com.mrquentinet.matrixcontroller.data.api.dto.DeviceInfoDto
import com.mrquentinet.matrixcontroller.data.api.dto.ErrorDto
import com.mrquentinet.matrixcontroller.data.api.dto.MetricsDto
import com.mrquentinet.matrixcontroller.data.api.dto.PairDto
import com.mrquentinet.matrixcontroller.data.api.dto.StatusDto
import com.mrquentinet.matrixcontroller.data.api.dto.toDomain
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
import java.io.IOException
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

        val (code, payload) = try {
            client.newCall(builder.build()).execute().use { response ->
                response.code to response.body.string()
            }
        } catch (_: IOException) {
            throw BoardException(BoardError.Unreachable)
        }

        if (code !in 200..299) throw BoardException(mapError(code, errorCode(payload)))
        payload
    }

    private fun <T> decode(serializer: DeserializationStrategy<T>, payload: String): T = try {
        json.decodeFromString(serializer, payload)
    } catch (e: SerializationException) {
        throw BoardException(BoardError.Malformed(e.message ?: "unparsable response"))
    }

    /** An error body that does not parse is tolerated: the HTTP code alone still classifies it. */
    private fun errorCode(payload: String): String? = try {
        json.decodeFromString(ErrorDto.serializer(), payload).error
    } catch (_: SerializationException) {
        null
    }
}

internal fun mapError(httpCode: Int, code: String?): BoardError = when (code) {
    "pairing_closed" -> BoardError.PairingClosed
    "too_many_clients" -> BoardError.TooManyClients
    "unknown_client", "bad_signature" -> BoardError.CredentialsRejected
    "stale_timestamp" -> BoardError.ClockSkew
    "replay_detected" -> BoardError.ReplayRejected
    "clock_unavailable" -> BoardError.BoardClockUnavailable
    "unknown_endpoint" -> BoardError.EndpointMissing
    else -> BoardError.Server(httpCode, code)
}
