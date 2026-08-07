package com.radikk.app.ui.screen

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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radikk.app.data.favorite.FavoriteEntry
import com.radikk.app.ui.AppViewModel
import com.radikk.app.ui.component.AreaSelector
import com.radikk.app.ui.component.StationCard
import com.radikk.app.util.RadikoTimeUtil
import java.time.Instant

/**
 * ホーム画面。
 * エリア選択・放送局一覧（現在放送中番組名付き）・お気に入りを縦スクロールで表示する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val stationState by viewModel.stationState.collectAsState()
    val selectedAreaId by viewModel.selectedAreaId.collectAsState()
    val favorites by viewModel.favorites.collectAsState()

    val stations = (stationState as? AppViewModel.StationUiState.Success)?.stations ?: emptyList()

    // エリア内全局の今日分番組表から on-air の番組名だけを抽出する
    // (放送局一覧の「放送中: 〇〇」表示に使う)
    var onAirTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(stations.map { it.id }.joinToString(",")) {
        if (stations.isEmpty()) return@LaunchedEffect
        val map = viewModel.getProgramsForStations(stations, 0)
        onAirTitles = map.entries.flatMap { (sid, progs) ->
            progs.filter { it.isOnAir() }.map { sid to it.title }
        }.toMap()
    }

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

            // お気に入り
            FavoritesSection(
                favorites = favorites,
                onEntryClick = { viewModel.playFavorite(it) },
                onRemove = { viewModel.removeFavorite(it.stationId, it.ftEpochMillis) },
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
                                nowPlayingTitle = onAirTitles[station.id],
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * お気に入りセクション。
 * お気に入り登録したタイムフリー番組の一覧。タップで再生、ハートアイコンで解除。
 */
@Composable
private fun FavoritesSection(
    favorites: List<FavoriteEntry>,
    onEntryClick: (FavoriteEntry) -> Unit,
    onRemove: (FavoriteEntry) -> Unit,
) {
    Text(
        text = "お気に入り",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp),
    )

    if (favorites.isEmpty()) {
        Text(
            text = "お気に入りはまだありません",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            favorites.forEach { entry ->
                FavoriteRow(
                    entry = entry,
                    onClick = { onEntryClick(entry) },
                    onRemove = { onRemove(entry) },
                )
            }
        }
    }
}

/**
 * お気に入りの1行。番組タイトル・局名・放送日時を表示し、タップで再生。
 * 右端のハートアイコンでお気に入り解除。
 */
@Composable
private fun FavoriteRow(
    entry: FavoriteEntry,
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
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "お気に入り解除",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
    HorizontalDivider()
}
