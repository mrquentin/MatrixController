package com.mrquentinet.matrixcontroller.ui.info

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrquentinet.matrixcontroller.R
import com.mrquentinet.matrixcontroller.domain.BoardMetrics
import com.mrquentinet.matrixcontroller.domain.BoardStatus
import com.mrquentinet.matrixcontroller.ui.common.message
import java.text.DateFormat
import java.util.Date

@Composable
fun BoardInfoScreen(
    viewModel: BoardInfoViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val errorMessage = state.error?.message()
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage)
            viewModel.errorShown()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.info_title)) },
                subtitle = { Text(state.board?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusCard(
                    status = state.status,
                    firmwareVersion = state.info?.firmwareVersion,
                )
                if (state.metricsUnavailable) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), Alignment.Center) {
                        Text(
                            text = stringResource(R.string.info_metrics_disabled),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    state.metrics?.let { metrics ->
                        CpuCard(metrics)
                        RamCard(metrics)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: BoardStatus?, firmwareVersion: String?) {
    val context = LocalContext.current
    InfoCard(stringResource(R.string.info_card_status)) {
        InfoRow(
            stringResource(R.string.info_firmware),
            firmwareVersion ?: stringResource(R.string.info_unknown),
        )
        InfoRow(
            stringResource(R.string.info_uptime),
            status?.let { formatUptime(context, it.uptimeSeconds) }
                ?: stringResource(R.string.info_unknown),
        )
        InfoRow(
            stringResource(R.string.info_signal),
            status?.let { formatSignal(context, it.rssi) }
                ?: stringResource(R.string.info_unknown),
        )
        InfoRow(
            stringResource(R.string.info_paired_clients),
            status?.let { stringResource(R.string.paired_clients_value, it.pairedClients) }
                ?: stringResource(R.string.info_unknown),
        )
        InfoRow(
            stringResource(R.string.info_board_time),
            when {
                status == null -> stringResource(R.string.info_unknown)
                status.boardTimeEpochSeconds == 0L -> stringResource(R.string.info_not_synced)
                else -> DateFormat.getDateTimeInstance()
                    .format(Date(status.boardTimeEpochSeconds * 1000))
            },
        )
    }
}

@Composable
private fun CpuCard(metrics: BoardMetrics) {
    val cpu = metrics.cpu
    InfoCard(stringResource(R.string.info_card_cpu)) {
        InfoRow(
            stringResource(R.string.cpu_loop_rate),
            stringResource(R.string.cpu_loop_rate_value, cpu.loopHz),
        )
        InfoRow(
            stringResource(R.string.cpu_busy),
            stringResource(R.string.cpu_busy_value, cpu.busyPerMille / 10.0f),
        )
        LinearWavyProgressIndicator(
            progress = { (cpu.busyPerMille / 1000f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        InfoRow(stringResource(R.string.cpu_requests), cpu.requests.toString())
        InfoRow(
            stringResource(R.string.cpu_request_time),
            stringResource(R.string.micros_pair, cpu.requestAvgMicros, cpu.requestMaxMicros),
        )
        InfoRow(
            stringResource(R.string.cpu_auth_time),
            stringResource(R.string.micros_pair, cpu.authAvgMicros, cpu.authMaxMicros),
        )
    }
}

@Composable
private fun RamCard(metrics: BoardMetrics) {
    val ram = metrics.ram
    InfoCard(stringResource(R.string.info_card_ram)) {
        LinearWavyProgressIndicator(
            progress = {
                if (ram.total <= 0) 0f else (ram.heapUsed.toFloat() / ram.total).coerceIn(0f, 1f)
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        InfoRow(stringResource(R.string.ram_heap_used), kib(ram.heapUsed))
        InfoRow(stringResource(R.string.ram_static), kib(ram.staticBytes))
        InfoRow(stringResource(R.string.ram_stack_peak), kib(ram.stackPeak))
        InfoRow(stringResource(R.string.ram_free_now), kib(ram.freeNow))
        InfoRow(stringResource(R.string.ram_min_free_ever), kib(ram.minFreeEver))
    }
}

@Composable
private fun kib(bytes: Long): String = stringResource(R.string.kib_value, bytes / 1024.0f)

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatUptime(context: Context, seconds: Long): String {
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return when {
        days > 0 -> context.getString(R.string.uptime_days, days, hours, minutes)
        hours > 0 -> context.getString(R.string.uptime_hours, hours, minutes)
        else -> context.getString(R.string.uptime_minutes, minutes, seconds % 60)
    }
}

private fun formatSignal(context: Context, rssi: Int): String {
    val quality = when {
        rssi >= -55 -> R.string.signal_excellent
        rssi >= -67 -> R.string.signal_good
        rssi >= -80 -> R.string.signal_fair
        else -> R.string.signal_weak
    }
    return context.getString(R.string.signal_value, rssi, context.getString(quality))
}
