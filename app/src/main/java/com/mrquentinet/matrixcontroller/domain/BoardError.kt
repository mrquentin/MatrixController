package com.mrquentinet.matrixcontroller.domain

/** The only error currency crossing the data/UI boundary. */
sealed interface BoardError {
    /** IOException, connect or read timeout. */
    data object Unreachable : BoardError

    /** 401 `pairing_closed` — the board's 60 s UP-button window is shut. */
    data object PairingClosed : BoardError

    /** 409 `too_many_clients` — the board already holds its maximum of 4 clients. */
    data object TooManyClients : BoardError

    /** 401 `unknown_client` — the board revoked this pairing. `bad_signature` is deliberately
     *  *not* folded in here: that code means the client's own signing is wrong (a dev-facing
     *  bug), and auto-clearing valid credentials over it would mask the real bug. It falls
     *  through `mapError` to [Server] instead. */
    data object CredentialsRejected : BoardError

    /** 401 `stale_timestamp` — the phone clock is more than 60 s off the board's. */
    data object ClockSkew : BoardError

    /** 401 `replay_detected` — nonce reuse or a timestamp below the board's high-water mark. */
    data object ReplayRejected : BoardError

    /** 503 `clock_unavailable` — the board has no NTP time yet. */
    data object BoardClockUnavailable : BoardError

    /** 404 `unknown_endpoint`. */
    data object EndpointMissing : BoardError

    /** 400 `invalid_setting_value` — `POST /api/apps/<index>/settings` rejected a value. */
    data object InvalidSettingValue : BoardError

    /** 400 `no_recognized_settings` — every key in a settings update was unrecognized; the
     *  locally-held schema is stale. */
    data object NoRecognizedSettings : BoardError

    /** 404 `unknown_app_index` — the app index no longer exists on the board. */
    data object UnknownAppIndex : BoardError

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
