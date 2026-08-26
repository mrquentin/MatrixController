package com.mrquentinet.matrixcontroller.ui.boards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mrquentinet.matrixcontroller.R

@Composable
fun BoardRow(
    row: BoardRowState,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        selected = false,
        onClick = { onOpen(row.board.id) },
        modifier = modifier,
        overlineContent = { Text(row.board.displayAddress) },
        supportingContent = { Text(statusLine(row)) },
        leadingContent = { StatusDot(row.probe) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        content = { Text(row.board.name) },
    )
}

@Composable
private fun StatusDot(probe: ProbeState) {
    val color = when (probe) {
        ProbeState.Probing -> MaterialTheme.colorScheme.outline
        ProbeState.Offline -> MaterialTheme.colorScheme.error
        is ProbeState.Online -> MaterialTheme.colorScheme.primary
    }
    Box(Modifier.size(12.dp).background(color, CircleShape))
}

@Composable
private fun statusLine(row: BoardRowState): String = when (val probe = row.probe) {
    ProbeState.Probing -> stringResource(R.string.status_probing)
    ProbeState.Offline -> stringResource(R.string.status_offline)
    is ProbeState.Online -> when {
        probe.credentialsRejected -> stringResource(R.string.status_revoked)
        !row.paired && probe.info.pairingOpen -> stringResource(R.string.status_ready_to_pair)
        !row.paired -> stringResource(R.string.status_not_paired)
        probe.activeAppName != null ->
            stringResource(R.string.status_online_app, probe.activeAppName)
        !probe.info.clockSynced -> stringResource(R.string.status_waiting_clock)
        else -> stringResource(R.string.status_online)
    }
}
