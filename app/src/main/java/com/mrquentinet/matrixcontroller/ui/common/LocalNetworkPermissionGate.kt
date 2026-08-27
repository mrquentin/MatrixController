package com.mrquentinet.matrixcontroller.ui.common

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mrquentinet.matrixcontroller.R

/**
 * Android 17 (API 37) blocks all outbound LAN traffic for apps targeting API 37+ unless
 * `ACCESS_LOCAL_NETWORK` is granted, and every board call in this app is LAN traffic. A blocked
 * call does not fail distinctly — Android's own docs say it "typically results in a timeout
 * error" — which would be indistinguishable from a genuine network failure at the HTTP layer.
 * Gating the whole app behind this state instead means the network layer is never even invoked
 * until it is true, so `BoardError.Unreachable` keeps its original, accurate meaning.
 */
private sealed interface LocalNetworkPermissionState {
    data object Granted : LocalNetworkPermissionState

    /** Never asked yet, or denied once — the system dialog can still be shown. */
    data class NotGranted(val request: () -> Unit) : LocalNetworkPermissionState

    /** Denied with "don't ask again" (or by policy) — the system dialog is now a silent no-op. */
    data class PermanentlyDenied(val openSettings: () -> Unit) : LocalNetworkPermissionState
}

@Composable
private fun rememberLocalNetworkPermissionState(): LocalNetworkPermissionState {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    fun currentlyGranted() = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_LOCAL_NETWORK,
    ) == PackageManager.PERMISSION_GRANTED

    var granted by rememberSaveable { mutableStateOf(currentlyGranted()) }
    // shouldShowRequestPermissionRationale() alone cannot tell "never asked" apart from
    // "permanently denied" — both return false. Tracking whether we have asked resolves that.
    var hasRequested by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        hasRequested = true
        granted = isGranted
    }

    // A user can grant or revoke this from system Settings without the process restarting; catch
    // that on every return to the app, the same way BoardsScreen re-probes boards on ON_START.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        granted = currentlyGranted()
    }

    return when {
        granted -> LocalNetworkPermissionState.Granted
        hasRequested && activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK) ->
            LocalNetworkPermissionState.PermanentlyDenied(
                openSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null))
                    )
                },
            )

        else -> LocalNetworkPermissionState.NotGranted(
            request = { launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) },
        )
    }
}

/** Shows [content] once local network access is granted; otherwise asks for it first. */
@Composable
fun LocalNetworkPermissionGate(content: @Composable () -> Unit) {
    when (val state = rememberLocalNetworkPermissionState()) {
        LocalNetworkPermissionState.Granted -> content()

        is LocalNetworkPermissionState.NotGranted -> LocalNetworkPermissionScreen(
            body = stringResource(R.string.local_network_permission_body),
            actionLabel = stringResource(R.string.action_grant_access),
            onAction = state.request,
        )

        is LocalNetworkPermissionState.PermanentlyDenied -> LocalNetworkPermissionScreen(
            body = stringResource(R.string.local_network_permission_denied_body),
            actionLabel = stringResource(R.string.action_open_settings),
            onAction = state.openSettings,
        )
    }
}

/** Pure rendering, kept separate from the permission plumbing so it is trivial to test. */
@Composable
fun LocalNetworkPermissionScreen(
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    Icons.Rounded.Wifi,
                    contentDescription = null,
                    modifier = Modifier.padding(24.dp).size(48.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = stringResource(R.string.local_network_permission_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
            )
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}
