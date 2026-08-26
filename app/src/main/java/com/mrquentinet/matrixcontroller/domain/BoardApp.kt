package com.mrquentinet.matrixcontroller.domain

data class BoardApp(val index: Int, val name: String)

/**
 * The board reports the active app as a top-level index, not as a per-item flag
 * (`GET /api/apps` → `{"apps":[…],"active_index":0,"active_name":"Clock"}`).
 */
data class BoardApps(val apps: List<BoardApp>, val activeIndex: Int)
