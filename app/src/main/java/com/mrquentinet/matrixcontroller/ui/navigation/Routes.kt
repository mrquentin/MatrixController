package com.mrquentinet.matrixcontroller.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object RouteBoards

@Serializable
data class RouteBoard(val boardId: String)

@Serializable
data class RouteAppSettings(val boardId: String, val appIndex: Int)

@Serializable
data class RouteBoardInfo(val boardId: String)
