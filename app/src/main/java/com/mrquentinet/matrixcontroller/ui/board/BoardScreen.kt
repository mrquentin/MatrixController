package com.mrquentinet.matrixcontroller.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrquentinet.matrixcontroller.R
import com.mrquentinet.matrixcontroller.domain.BoardError
import com.mrquentinet.matrixcontroller.ui.common.message

@Composable
fun BoardScreen(
    viewModel: BoardViewModel,
    onBack: () -> Unit,
    onOpenInfo: (String) -> Unit,
    onOpenAppSettings: (boardId: String, appIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val error: BoardError? = when (state) {
        is BoardUiState.Apps -> (state as BoardUiState.Apps).error
        is BoardUiState.NeedsPairing -> (state as BoardUiState.NeedsPairing).error
        else -> null
    }
    val errorMessage = error?.message()
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage)
            viewModel.errorShown()
        }
    }

    val board = when (val current = state) {
        is BoardUiState.Apps -> current.board
        is BoardUiState.NeedsPairing -> current.board
        is BoardUiState.Failed -> current.board
        BoardUiState.Loading -> null
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(board?.name ?: stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (board != null) {
                        IconButton(onClick = { onOpenInfo(board.id) }) {
                            Icon(
                                Icons.Rounded.Info,
                                contentDescription = stringResource(R.string.action_board_info),
                            )
                        }
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val contentModifier = Modifier.fillMaxSize().padding(innerPadding)
        when (val current = state) {
            BoardUiState.Loading -> Box(contentModifier, Alignment.Center) {
                CircularWavyProgressIndicator()
            }

            is BoardUiState.NeedsPairing -> PairPanel(
                state = current,
                onPair = viewModel::pair,
                onCheckAgain = viewModel::load,
                modifier = contentModifier,
            )

            is BoardUiState.Apps -> PullToRefreshBox(
                isRefreshing = current.refreshing,
                onRefresh = viewModel::refresh,
                modifier = contentModifier,
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(current.apps, key = { it.index }) { app ->
                        val active = app.index == current.activeIndex
                        ListItem(
                            // `selected` is what gives the active app the expressive selected
                            // list-item treatment; do not hand-roll a background colour.
                            selected = active,
                            // Short press opens the schema-driven settings form; long press is
                            // the "make this the active app" action (was previously the click).
                            onClick = { onOpenAppSettings(current.board.id, app.index) },
                            onLongClick = { viewModel.selectApp(app.index) },
                            onLongClickLabel = stringResource(R.string.action_set_active_app),
                            enabled = current.switchingToIndex == null,
                            supportingContent = if (active) {
                                { Text(stringResource(R.string.app_active)) }
                            } else {
                                null
                            },
                            leadingContent = {
                                Icon(Icons.Rounded.Apps, contentDescription = null)
                            },
                            trailingContent = when {
                                current.switchingToIndex == app.index -> {
                                    { CircularWavyProgressIndicator(Modifier.size(24.dp)) }
                                }
                                active -> {
                                    { Icon(Icons.Rounded.CheckCircle, contentDescription = null) }
                                }
                                else -> {
                                    { Icon(Icons.Rounded.ChevronRight, contentDescription = null) }
                                }
                            },
                            content = { Text(app.name) },
                        )
                    }
                }
            }

            is BoardUiState.Failed -> Box(contentModifier, Alignment.Center) {
                Text(
                    text = current.error.message(),
                    modifier = Modifier.padding(32.dp),
                )
            }
        }
    }
}
