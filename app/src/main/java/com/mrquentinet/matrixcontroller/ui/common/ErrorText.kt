package com.mrquentinet.matrixcontroller.ui.common

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.mrquentinet.matrixcontroller.R
import com.mrquentinet.matrixcontroller.domain.BoardError

/**
 * Exhaustive `when` on purpose: a new [BoardError] variant cannot compile without a string.
 * Takes a resolver rather than reading a composition local directly so it can also be used from
 * non-composable call sites.
 */
fun BoardError.message(resolve: (Int, Array<out Any>) -> String): String = when (this) {
    BoardError.Unreachable -> resolve(R.string.error_unreachable, emptyArray())
    BoardError.PairingClosed -> resolve(R.string.error_pairing_closed, emptyArray())
    BoardError.TooManyClients -> resolve(R.string.error_too_many_clients, emptyArray())
    BoardError.CredentialsRejected -> resolve(R.string.error_credentials_rejected, emptyArray())
    BoardError.ClockSkew -> resolve(R.string.error_clock_skew, emptyArray())
    BoardError.ReplayRejected -> resolve(R.string.error_replay_rejected, emptyArray())
    BoardError.BoardClockUnavailable ->
        resolve(R.string.error_board_clock_unavailable, emptyArray())
    BoardError.EndpointMissing -> resolve(R.string.error_endpoint_missing, emptyArray())
    BoardError.NotPaired -> resolve(R.string.error_not_paired, emptyArray())
    BoardError.InvalidSettingValue -> resolve(R.string.error_invalid_setting_value, emptyArray())
    BoardError.NoRecognizedSettings ->
        resolve(R.string.error_no_recognized_settings, emptyArray())
    BoardError.UnknownAppIndex -> resolve(R.string.error_unknown_app_index, emptyArray())
    is BoardError.Server -> resolve(R.string.error_server, arrayOf<Any>(httpCode, code ?: ""))
    is BoardError.Malformed -> resolve(R.string.error_malformed, emptyArray())
}

fun BoardError.message(resources: Resources): String =
    message { id, args -> resources.getString(id, *args) }

@Composable
fun BoardError.message(): String {
    // LocalResources, unlike LocalContext.getString, recomposes on configuration changes.
    val resources = LocalResources.current
    return message(resources)
}
