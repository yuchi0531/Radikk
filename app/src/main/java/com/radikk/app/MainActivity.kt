package com.radikk.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.radikk.app.data.reminder.ReminderReceiver
import com.radikk.app.ui.AppViewModel
import com.radikk.app.ui.component.FullPlayerScreen
import com.radikk.app.ui.component.MiniPlayer
import com.radikk.app.ui.navigation.BottomTab
import com.radikk.app.ui.screen.HomeScreen
import com.radikk.app.ui.screen.ProgramGuideScreen
import com.radikk.app.ui.screen.SettingsScreen
import com.radikk.app.ui.screen.TimefreeScreen
import com.radikk.app.ui.theme.RadikkTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 通知タップで再生する番組の情報。
 */
data class ReminderPlaybackRequest(
    val stationId: String,
    val stationName: String,
    val programTitle: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
) {
    companion object {
        fun fromIntent(intent: Intent): ReminderPlaybackRequest? {
            val stationId = intent.getStringExtra(ReminderReceiver.EXTRA_STATION_ID) ?: return null
            val stationName = intent.getStringExtra(ReminderReceiver.EXTRA_STATION_NAME) ?: ""
            val programTitle = intent.getStringExtra(ReminderReceiver.EXTRA_PROGRAM_TITLE) ?: ""
            return ReminderPlaybackRequest(
                stationId = stationId,
                stationName = stationName,
                programTitle = programTitle,
                startEpochMillis = intent.getLongExtra(ReminderReceiver.EXTRA_START_EPOCH, 0L),
                endEpochMillis = intent.getLongExtra(ReminderReceiver.EXTRA_END_EPOCH, 0L),
            )
        }
    }
}

/**
 * 通知タップからの再生イベントを Compose に伝えるイベントバス。
 * MainActivity (onCreate / onNewIntent) が emit し、AppScaffold が購読する。
 */
object ReminderPlaybackEvents {
    val requests = MutableStateFlow<ReminderPlaybackRequest?>(null)

    fun emit(request: ReminderPlaybackRequest) {
        requests.value = request
    }
}

/**
 * Radikk のメインアクティビティ。
 * ボトムナビ 4 タブ (ホーム / 番組表 / タイムフリー / 設定) をホストする。
 * 通知タップ (ACTION_PLAY_FROM_REMINDER) でその番組を再生する。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 通知タップからの再生リクエスト (プロセス再生成時: onCreate で処理)
        if (intent?.action == ACTION_PLAY_FROM_REMINDER) {
            ReminderPlaybackRequest.fromIntent(intent)?.let {
                ReminderPlaybackEvents.emit(it)
            }
        }

        setContent {
            val app = application as RadikkApplication
            val viewModel: AppViewModel = viewModel()

            RadikkTheme(
                themeMode = viewModel.settingsFlow.collectAsState().value.themeMode,
            ) {
                AppScaffold(viewModel)
            }
        }
    }

    /** 通知タップ時 (アプリが既に起動している場合)。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_PLAY_FROM_REMINDER) {
            ReminderPlaybackRequest.fromIntent(intent)?.let {
                ReminderPlaybackEvents.emit(it)
            }
        }
    }

    companion object {
        const val ACTION_PLAY_FROM_REMINDER = "com.radikk.app.action.PLAY_FROM_REMINDER"
    }
}

@Composable
private fun AppScaffold(viewModel: AppViewModel) {
    var selectedTab by remember { mutableStateOf(BottomTab.HOME) }
    // ホームの「すべて見る」→ タイムフリーのダウンロードタブを開くためのフラグ
    var timefreeOpenDownloads by remember { mutableStateOf(false) }
    var showFullPlayer by remember { mutableStateOf(false) }
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // ホームタブ以外で戻るボタンを押したらホームタブへ戻る。
    // ホームタブではバックを無効にして、システム標準の動作（アプリ終了）に委ねる。
    // 各画面内のサブ状態（局選択中など）は各画面側の BackHandler が先に消費する。
    BackHandler(enabled = selectedTab != BottomTab.HOME) {
        selectedTab = BottomTab.HOME
    }
    // 全画面プレイヤー表示中はバックで閉じる。
    // 最後に登録した BackHandler が優先されるため、こちらを後に配置する。
    BackHandler(enabled = showFullPlayer) {
        showFullPlayer = false
    }

    // エラーメッセージを Snackbar で表示
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumePlayerError()
        }
    }

    // 通知タップからの再生 (onCreate / onNewIntent の両方を処理)
    val reminderRequest by ReminderPlaybackEvents.requests.collectAsState()
    LaunchedEffect(reminderRequest) {
        reminderRequest?.let { request ->
            ReminderPlaybackEvents.requests.value = null // 消費済みにする
            viewModel.playFromReminder(
                stationId = request.stationId,
                stationName = request.stationName,
                programTitle = request.programTitle,
                startEpochMillis = request.startEpochMillis,
                endEpochMillis = request.endEpochMillis,
            )
        }
    }

    // 全体を Box でラップし、全画面プレイヤーは Scaffold (タブ+ミニプレイヤー) の最前面に重ねる
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column {
                    // ミニプレイヤー (再生中かつ全画面プレイヤー非表示のみ)
                    if (!showFullPlayer) {
                        MiniPlayer(
                            viewModel = viewModel,
                            onClick = { showFullPlayer = true },
                        )
                    }
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
                    BottomTab.HOME -> HomeScreen(
                        viewModel = viewModel,
                        onShowAllDownloads = {
                            // タイムフリーのダウンロードタブを開く
                            timefreeOpenDownloads = true
                            selectedTab = BottomTab.TIMEFREE
                        },
                    )
                    BottomTab.PROGRAM_GUIDE -> ProgramGuideScreen(viewModel)
                    BottomTab.TIMEFREE -> TimefreeScreen(
                        viewModel = viewModel,
                        openDownloads = timefreeOpenDownloads,
                        onDownloadsOpened = { timefreeOpenDownloads = false },
                    )
                    BottomTab.SETTINGS -> SettingsScreen(viewModel)
                }
            }
        }

        // 全画面プレイヤー (再生中のみ、Scaffold 全体を覆う最前面オーバーレイ)
        if (showFullPlayer) {
            FullPlayerScreen(
                viewModel = viewModel,
                onClose = { showFullPlayer = false },
            )
        }

        // Snackbar は最前面に配置する (全画面プレイヤー表示中もエラーが見えるように)
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
