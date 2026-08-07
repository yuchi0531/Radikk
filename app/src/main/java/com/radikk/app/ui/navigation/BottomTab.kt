package com.radikk.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * ボトムナビゲーションの4タブ。
 */
enum class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    LIVE("live", "ライブ", Icons.Filled.Radio),
    PROGRAM_GUIDE("program_guide", "番組表", Icons.Filled.CalendarMonth),
    TIMEFREE("timefree", "タイムフリー", Icons.Filled.History),
    SETTINGS("settings", "設定", Icons.Filled.Settings),
}
