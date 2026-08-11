package com.radikk.app.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.radikk.app.data.download.DownloadedProgram
import com.radikk.app.data.model.Program
import com.radikk.app.data.model.Station
import com.radikk.app.ui.AppViewModel
import com.radikk.app.ui.component.AreaSelector
import com.radikk.app.ui.component.ConfirmDeleteDialog
import com.radikk.app.ui.component.ProgramDetailDialog
import com.radikk.app.ui.component.StationCard
import com.radikk.app.util.RadikoTimeUtil
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * ホーム画面。
 * エリア選択・放送局一覧（現在放送中番組名付き）・ダウンロード（最大5件）を縦スクロールで表示する。
 * ダウンロードが5件を超える場合は「すべて見る」からタイムフリーのダウンロードタブへ遷移できる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    onShowAllDownloads: (() -> Unit)? = null,
) {
    val stationState by viewModel.stationState.collectAsState()
    val selectedAreaId by viewModel.selectedAreaId.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val reminders by viewModel.reminders.collectAsState()

    val stations = (stationState as? AppViewModel.StationUiState.Success)?.stations ?: emptyList()

    val context = LocalContext.current

    // 通知設定ダイアログの対象番組
    var reminderTarget by remember { mutableStateOf<Pair<Station, Program>?>(null) }

    // 番組詳細ダイアログの対象番組
    var detailTarget by remember { mutableStateOf<Pair<Station, Program>?>(null) }

    // 通知権限リクエスト (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            val target = reminderTarget
            reminderTarget = null
            if (granted && target != null) {
                viewModel.setReminder(target.first, target.second)
            } else if (!granted) {
                viewModel.showError("通知権限が必要です。設定画面から許可してください")
            }
        },
    )

    /** 通知を設定する。未許可なら権限をリクエストする。 */
    fun requestReminder(station: Station, program: Program) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        viewModel.setReminder(station, program)
    }

    // 通知設定済みの識別子セット (局ID + 開始時刻)
    val reminderKeys = remember(reminders) {
        reminders.map { it.stationId + "|" + it.startEpochMillis }.toSet()
    }

    // エリア内全局の今日分番組表から on-air の番組を抽出する
    // (放送局一覧の「放送中: 〇〇」表示と詳細ダイアログに使う)
    var onAirPrograms by remember { mutableStateOf<Map<String, Program>>(emptyMap()) }
    val onAirStationIds = stations.map { it.id }.joinToString(",")
    // 画面が RESUMED (タブ表示中・アプリ再開中) の間だけ動くループで、
    // 番組切替に追従するため 30 秒ごとに再取得する (60秒から短縮)。
    // repeatOnLifecycle(RESUMED) により「ホームに戻ってきた直後」も即時再取得され、
    // 番組境界直後に戻ってきた場合の取りこぼし (最大 60 秒の古さ) を防ぐ。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(onAirStationIds, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (stations.isEmpty()) return@repeatOnLifecycle
            while (true) {
                // 一時的な取得失敗でループが死なないように握りつぶす (次回更新で補完)
                runCatching {
                    val map = viewModel.getProgramsForStations(stations, 0)
                    onAirPrograms = map.entries.flatMap { (sid, progs) ->
                        progs.filter { it.isOnAir() }.map { sid to it }
                    }.toMap()
                }
                // 番組切替に追従するため 30 秒ごとに再取得する (画面表示中のみ)
                delay(30_000)
            }
        }
    }

    // 削除確認ダイアログの対象 (null なら非表示)
    var pendingDelete by remember { mutableStateOf<DownloadedProgram?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("ホーム") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            // エリアセレクタ
            AreaSelector(
                selectedAreaId = selectedAreaId,
                onAreaSelected = { viewModel.changeArea(it) },
            )

            // 局一覧 (局一覧の状態に依存)
            when (val state = stationState) {
                is AppViewModel.StationUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is AppViewModel.StationUiState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                is AppViewModel.StationUiState.Success -> {
                    // 局一覧
                    Text(
                        text = "放送局",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    // 1列表示 (横一列 = 1カード)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        stations.forEach { station ->
                            StationCard(
                                station = station,
                                onClick = { viewModel.playLive(station) },
                                nowPlayingTitle = onAirPrograms[station.id]?.title,
                                onNowPlayingClick = onAirPrograms[station.id]?.let { program ->
                                    { detailTarget = station to program }
                                },
                            )
                        }
                    }
                }
            }

            // ダウンロード (最大5件。超過時は「すべて見る」でタイムフリーのダウンロードタブへ)
            DownloadsSection(
                downloads = downloads.take(5),
                totalCount = downloads.size,
                onEntryClick = { viewModel.playDownloaded(it) },
                onRemove = {
                    // 削除は確認ダイアログを経由する (7日経過後は再DL不可)
                    pendingDelete = it
                },
                onShowAll = onShowAllDownloads,
            )
        }
    }

    // ダウンロード削除の確認ダイアログ
    pendingDelete?.let { entry ->
        ConfirmDeleteDialog(
            programTitle = entry.programTitle,
            onConfirm = {
                viewModel.deleteDownload(entry.stationId, entry.ftEpochMillis)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // 番組詳細ダイアログ
    detailTarget?.let { (station, program) ->
        ProgramDetailDialog(
            station = station,
            program = program,
            isOnAir = program.isOnAir(),
            isPast = program.to.isBefore(Instant.now()),
            isReminderSet = reminderKeys.contains(station.id + "|" + program.ft.toEpochMilli()),
            onListen = {
                detailTarget = null
                when {
                    program.isOnAir() -> viewModel.playLive(station)
                    program.to.isBefore(Instant.now()) -> viewModel.playTimefree(station, program)
                    // 未来の番組は再生不可 (ボタンは無効化されているが、念のためガード)
                    else -> viewModel.showError("この番組はまだ放送されていません")
                }
            },
            onReminderClick = {
                // 通知設定/解除へ遷移 (既存の ReminderDialog を開く)
                val isSet = reminderKeys.contains(station.id + "|" + program.ft.toEpochMilli())
                if (isSet) {
                    // 解除する場合は詳細ダイアログを閉じる
                    detailTarget = null
                    val id = com.radikk.app.data.reminder.ReminderRepository.reminderId(
                        station.id, program.ft.toEpochMilli()
                    )
                    reminders.firstOrNull { it.id == id }?.let { viewModel.cancelReminder(it) }
                } else {
                    // 詳細ダイアログを閉じてから ReminderDialog を開く (重複表示防止)
                    detailTarget = null
                    reminderTarget = station to program
                }
            },
            onDismiss = { detailTarget = null },
        )
    }

    // 通知設定ダイアログ
    reminderTarget?.let { (station, program) ->
        ReminderDialog(
            station = station,
            program = program,
            isSet = reminderKeys.contains(station.id + "|" + program.ft.toEpochMilli()),
            onSet = {
                requestReminder(station, program)
                // 権限リクエストが発生する場合は reminderTarget を保持する
                // (onResult で参照するため)。権限が許可済みなら即時設定される。
                val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    reminderTarget = null
                }
            },
            onCancel = {
                val id = com.radikk.app.data.reminder.ReminderRepository.reminderId(
                    station.id, program.ft.toEpochMilli()
                )
                reminders.firstOrNull { it.id == id }?.let { viewModel.cancelReminder(it) }
                reminderTarget = null
            },
            onDismiss = { reminderTarget = null },
        )
    }
}

/**
 * ダウンロードセクション。
 * ダウンロード済みのタイムフリー番組の一覧。タップで再生、右端のボタンで削除。
 * downloads は呼び出し側で最大5件に制限済み。totalCount が5を超える場合は「すべて見る」を表示する。
 */
@Composable
private fun DownloadsSection(
    downloads: List<DownloadedProgram>,
    totalCount: Int,
    onEntryClick: (DownloadedProgram) -> Unit,
    onRemove: (DownloadedProgram) -> Unit,
    onShowAll: (() -> Unit)? = null,
) {
    Text(
        text = "ダウンロード",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp),
    )

    if (downloads.isEmpty()) {
        Text(
            text = "ダウンロード済みの番組はありません",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            downloads.forEach { entry ->
                DownloadRow(
                    entry = entry,
                    onClick = { onEntryClick(entry) },
                    onRemove = { onRemove(entry) },
                )
            }
        }
    }

    if (totalCount > 5 && onShowAll != null) {
        TextButton(onClick = onShowAll) {
            Text("すべて見る")
        }
    }
}

/**
 * ダウンロード済み番組の1行。番組タイトル・局名・放送日時を表示し、タップで再生。
 * 右端のボタンで削除。
 */
@Composable
private fun DownloadRow(
    entry: DownloadedProgram,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.programTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.stationName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${RadikoTimeUtil.formatDate(Instant.ofEpochMilli(entry.ftEpochMillis))} " +
                        "${RadikoTimeUtil.formatTime(Instant.ofEpochMilli(entry.ftEpochMillis))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.fileSizeBytes > 0L) {
                    Text(
                        text = RadikoTimeUtil.formatFileSize(entry.fileSizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "ダウンロード削除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}
