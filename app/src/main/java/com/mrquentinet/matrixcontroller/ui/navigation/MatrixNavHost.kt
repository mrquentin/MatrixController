package com.mrquentinet.matrixcontroller.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mrquentinet.matrixcontroller.AppContainer
import com.mrquentinet.matrixcontroller.ui.board.AppSettingsScreen
import com.mrquentinet.matrixcontroller.ui.board.AppSettingsViewModel
import com.mrquentinet.matrixcontroller.ui.board.BoardScreen
import com.mrquentinet.matrixcontroller.ui.board.BoardViewModel
import com.mrquentinet.matrixcontroller.ui.boards.BoardsScreen
import com.mrquentinet.matrixcontroller.ui.boards.BoardsViewModel
import com.mrquentinet.matrixcontroller.ui.info.BoardInfoScreen
import com.mrquentinet.matrixcontroller.ui.info.BoardInfoViewModel

@Composable
fun MatrixNavHost(container: AppContainer, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = RouteBoards,
        modifier = modifier,
    ) {
        composable<RouteBoards> {
            BoardsScreen(
                viewModel = viewModel(factory = BoardsViewModel.factory(container)),
                onOpenBoard = { boardId -> navController.navigate(RouteBoard(boardId)) },
            )
        }
        composable<RouteBoard> {
            BoardScreen(
                viewModel = viewModel(factory = BoardViewModel.factory(container)),
                onBack = { navController.popBackStack() },
                onOpenInfo = { boardId -> navController.navigate(RouteBoardInfo(boardId)) },
                onOpenAppSettings = { boardId, appIndex ->
                    navController.navigate(RouteAppSettings(boardId, appIndex))
                },
            )
        }
        composable<RouteAppSettings> {
            AppSettingsScreen(
                viewModel = viewModel(factory = AppSettingsViewModel.factory(container)),
                onBack = { navController.popBackStack() },
            )
        }
        composable<RouteBoardInfo> {
            BoardInfoScreen(
                viewModel = viewModel(factory = BoardInfoViewModel.factory(container)),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
