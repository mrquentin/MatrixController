package com.mrquentinet.matrixcontroller.domain

/** The only error currency crossing the data/UI boundary. */
sealed interface BoardError {
    /** IOException, connect or read timeout. */
    data object Unreachable : BoardError

    /** 401 `pairing_closed` — the board's 60 s UP-button window is shut. */
    data object PairingClosed : BoardError

    /** 409 `too_many_clients` — the board already holds its maximum of 4 clients. */
    data object TooManyClients : BoardError

    /** 401 `unknown_client` or `bad_signature` — stored credentials no longer work. */
    data object CredentialsRejected : BoardError

    /** 401 `stale_timestamp` — the phone clock is more than 60 s off the board's. */
    data object ClockSkew : BoardError

    /** 401 `replay_detected` — nonce reuse or a timestamp below the board's high-water mark. */
    data object ReplayRejected : BoardError

    /** 503 `clock_unavailable` — the board has no NTP time yet. */
    data object BoardClockUnavailable : BoardError

    /** 404 `unknown_endpoint`. */
    data object EndpointMissing : BoardError

    /** No credentials stored for this board. */
    data object NotPaired : BoardError

    data class Server(val httpCode: Int, val code: String?) : BoardError

    data class Malformed(val detail: String) : BoardError
}

/**
 * @param cause the underlying transport exception, when there is one (e.g. the
 *   [ConnectException][java.net.ConnectException] or
 *   [SocketTimeoutException][java.net.SocketTimeoutException] behind [BoardError.Unreachable]).
 *   Preserved so it survives into logs even though [error] is the only thing the UI reads.
 */
class BoardException(val error: BoardError, cause: Throwable? = null) :
    Exception(error.toString(), cause)
