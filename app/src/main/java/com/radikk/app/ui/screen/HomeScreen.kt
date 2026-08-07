package com.radikk.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radikk.app.data.history.HistoryEntry
import com.radikk.app.data.model.Station
import com.radikk.app.data.timefree.TimefreeCacheRepository
import com.radikk.app.ui.AppViewModel
import com.radikk.app.ui.component.AreaSelector
import com.radikk.app.ui.component.StationCard
import java.time.Instant

/**
 * ホーム画面。
 * エリア選択・現在再生中・放送局・聞いた履歴を縦スクロールで表示する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val stationState by viewModel.stationState.collectAsState()
    val selectedAreaId by viewModel.selectedAreaId.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val playerState by viewModel.playerUiState.collectAsState()
    val history by viewModel.history.collectAsState()

    val stations = (stationState as? AppViewModel.StationUiState.Success)?.stations ?: emptyList()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("ホーム") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // エリアセレクタ
            AreaSelector(
                selectedAreaId = selectedAreaId,
                onAreaSelected = { viewModel.changeArea(it) },
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // 現在再生中カード (再生中のみ)
            nowPlaying?.let { np ->
                NowPlayingCard(
                    nowPlaying = np,
                    isPlaying = playerState.isPlaying,
                    onPlayPause = { if (playerState.isPlaying) viewModel.pause() else viewModel.play() },
                    onStop = { viewModel.stop() },
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

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
                    // 2列グリッド (縦スクロール Column 内のため LazyVerticalGrid は使わず
                    // chunked で行に分割する)
                    val chunked = stations.chunked(2)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        chunked.forEach { rowStations ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowStations.forEach { station ->
                                    StationCard(
                                        station = station,
                                        onClick = { viewModel.playLive(station) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                // 奇数個のとき右側を空ける
                                if (rowStations.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // 聞いた履歴 (最下部)
            HistorySection(
                history = history,
                onClear = { viewModel.clearHistory() },
                onEntryClick = { viewModel.playHistoryEntry(it) },
            )
        }
    }
}

/**
 * 現在再生中の番組カード。再生/一時停止・停止ボタン付き。
 */
@Composable
private fun NowPlayingCard(
    nowPlaying: AppViewModel.NowPlaying,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nowPlaying.stationName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = nowPlaying.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "一時停止" else "再生",
                )
            }
            IconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "停止",
                )
            }
        }
    }
}

/**
 * 聞いた履歴セクション。
 * タイムフリー期間 (7日) を過ぎた行はグレー表示でタップ不可。
 */
@Composable
private fun HistorySection(
    history: List<HistoryEntry>,
    onClear: () -> Unit,
    onEntryClick: (HistoryEntry) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "聞いた履歴",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (history.isNotEmpty()) {
            TextButton(onClick = onClear) {
                Text("クリア")
            }
        }
    }

    if (history.isEmpty()) {
        Text(
            text = "再生した番組がここに表示されます",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            history.forEach { entry ->
                HistoryRow(
                    entry = entry,
                    expired = Instant.now().toEpochMilli() - entry.listenedAtEpochMillis >
                        TimefreeCacheRepository.MAX_AGE_MILLIS,
                    onClick = { onEntryClick(entry) },
                )
            }
        }
    }
}

/**
 * 履歴の1行。期限切れ (7日超過) はグレー表示でタップ不可。
 */
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    expired: Boolean,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val dim = onSurfaceVariant.copy(alpha = 0.5f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (expired) it else it.clickable(onClick = onClick) }
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = entry.stationName,
                style = MaterialTheme.typography.labelMedium,
                color = if (expired) dim else primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = relativeTime(entry.listenedAtEpochMillis),
                style = MaterialTheme.typography.labelSmall,
                color = if (expired) dim else onSurfaceVariant,
            )
        }
        Text(
            text = entry.programTitle,
            style = MaterialTheme.typography.bodyLarge,
            color = if (expired) dim else onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * 履歴の経過時間を「◯分前 / ◯時間前 / ◯日前」で返す。
 */
private fun relativeTime(listenedAtEpochMillis: Long): String {
    val diff = Instant.now().toEpochMilli() - listenedAtEpochMillis
    val minutes = diff / 60_000
    return when {
        diff < 60_000 -> "たった今"
        minutes < 60 -> "${minutes}分前"
        minutes < 24 * 60 -> "${minutes / 60}時間前"
        else -> "${minutes / (24 * 60)}日前"
    }
}
