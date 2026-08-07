package com.radikk.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.radikk.app.ui.AppViewModel
import com.radikk.app.ui.component.MiniPlayer
import com.radikk.app.ui.navigation.BottomTab
import com.radikk.app.ui.screen.LiveScreen
import com.radikk.app.ui.screen.ProgramGuideScreen
import com.radikk.app.ui.screen.SettingsScreen
import com.radikk.app.ui.screen.TimefreeScreen
import com.radikk.app.ui.theme.RadikkTheme
import androidx.compose.runtime.collectAsState

/**
 * Radikk のメインアクティビティ。
 * ボトムナビ 4 タブ (ライブ / 番組表 / タイムフリー / 設定) をホストする。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as RadikkApplication
            val viewModel: AppViewModel = viewModel()

            RadikkTheme(
                themeMode = viewModel.settingsFlow.collectAsState().value.themeMode,
                dynamicColor = viewModel.settingsFlow.collectAsState().value.dynamicColor,
            ) {
                AppScaffold(viewModel)
            }
        }
    }
}

@Composable
private fun AppScaffold(viewModel: AppViewModel) {
    var selectedTab by remember { mutableStateOf(BottomTab.LIVE) }
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // エラーメッセージを Snackbar で表示
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumePlayerError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                // ミニプレイヤー (再生中のみ表示)
                MiniPlayer(
                    viewModel = viewModel,
                    onClick = { /* Phase 3 後半: フルプレイヤー表示 */ },
                )
                NavigationBar {
                    BottomTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                BottomTab.LIVE -> LiveScreen(viewModel)
                BottomTab.PROGRAM_GUIDE -> ProgramGuideScreen(viewModel)
                BottomTab.TIMEFREE -> TimefreeScreen(viewModel)
                BottomTab.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }
}
