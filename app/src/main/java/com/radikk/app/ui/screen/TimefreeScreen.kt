package com.radikk.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radikk.app.data.favorite.FavoriteEntry
import com.radikk.app.data.model.Program
import com.radikk.app.data.model.Station
import com.radikk.app.data.timefree.CachedTimefreeProgram
import com.radikk.app.ui.AppViewModel
import com.radikk.app.ui.component.StationCard
import com.radikk.app.util.RadikoTimeUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * タイムフリー再生画面。
 *
 * - 検索バー: キャッシュ済み番組を番組名/パーソナリティ/局名で検索
 * - 局選択: 過去7日分の番組リスト (取得時にキャッシュへ保存)
 * - お気に入り: 登録した番組一覧 (タップで再生、ハートで解除)
 * - タップで再生 (シーク可能)
 *
 * キャッシュは DataStore に永続化され、タイムフリー期間外 (過去7日より前) の
 * 番組は自動的に破棄される。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimefreeScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    openFavorites: Boolean = false,
    onFavoritesOpened: () -> Unit = {},
) {
    val stationState by viewModel.stationState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    var selectedStation by remember { mutableStateOf<Station?>(null) }
    var selectedDayOffset by remember { mutableStateOf(0) }
    var programs by remember { mutableStateOf<List<Program>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    // 局選択中は戻るボタンで局一覧（検索モード）へ戻す。
    // 局一覧では MainActivity 側の BackHandler がホーム（ライブ）タブへ戻る。
    BackHandler(enabled = selectedStation != null) {
        selectedStation = null
    }

    // 検索 / 局から選ぶ / お気に入り モード
    var mode by remember { mutableStateOf(TimefreeMode.SEARCH) }

    // ホームの「すべて見る」→ お気に入りタブを開く (フラグ消費後にリセット)
    LaunchedEffect(openFavorites) {
        if (openFavorites) {
            mode = TimefreeMode.FAVORITES
            selectedStation = null
            onFavoritesOpened()
        }
    }

    // 検索状態
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<CachedTimefreeProgram>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

    // 選択中のエリアの局一覧
    val stations = (stationState as? AppViewModel.StationUiState.Success)?.stations ?: emptyList()

    // お気に入り登録済み番組のキー集合 (stationId, ftEpochMillis)。
    // favorites の変更で再計算されるため、各行の isFavorite が反応的に更新される。
    val favoriteKeys = favorites.map { it.stationId to it.ftEpochMillis }.toSet()

    // エリア変更後、選択中の局が現在のエリアに存在しない場合は一覧へ戻す
    LaunchedEffect(stations.map { it.id }.joinToString(",")) {
        val current = selectedStation
        if (current != null && stations.none { it.id == current.id }) {
            selectedStation = null
        }
    }

    // 過去7日分 (0=今日, 1=昨日, ...)
    val days = remember { (0..7).toList() }

    // 検索実行 (デバウンス付き: 300ms)
    // stations をキーに含める (エリア変更時に古いエリアの検索結果が残らないようにする)
    LaunchedEffect(searchQuery, selectedStation, stations.map { it.id }.joinToString(",")) {
        if (selectedStation != null) return@LaunchedEffect // 局選択モードでは検索しない
        searchJob?.cancel()
        searchJob = scope.launch {
            kotlinx.coroutines.delay(300)
            searchLoading = true
            searchResults = viewModel.searchTimefree(searchQuery, stations)
            searchLoading = false
        }
    }

    // 局選択時の番組取得 (キャッシュに保存)
    LaunchedEffect(selectedStation?.id, selectedDayOffset) {
        val station = selectedStation ?: return@LaunchedEffect
        loading = true
        try {
            // offset は「何日前」を表す (0=今日, 1=昨日, ...)
            programs = viewModel.loadAndCacheTimefree(station.id, station.name, -selectedDayOffset)
        } catch (e: Exception) {
            programs = emptyList()
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("タイムフリー") }) },
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = stationState) {
                is AppViewModel.StationUiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is AppViewModel.StationUiState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                }
                is AppViewModel.StationUiState.Success -> {
                    if (selectedStation == null) {
                        Column(Modifier.fillMaxSize()) {
                            // 検索 / 局から選ぶ / お気に入り モード切り替えタブ
                            PrimaryTabRow(selectedTabIndex = mode.ordinal) {
                                Tab(
                                    selected = mode == TimefreeMode.SEARCH,
                                    onClick = { mode = TimefreeMode.SEARCH },
                                    text = { Text("検索") },
                                )
                                Tab(
                                    selected = mode == TimefreeMode.STATIONS,
                                    onClick = { mode = TimefreeMode.STATIONS },
                                    text = { Text("局から選ぶ") },
                                )
                                Tab(
                                    selected = mode == TimefreeMode.FAVORITES,
                                    onClick = { mode = TimefreeMode.FAVORITES },
                                    text = { Text("お気に入り") },
                                )
                            }

                            when (mode) {
                                TimefreeMode.SEARCH -> {
                                    // 検索バー
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        placeholder = { Text("番組名・パーソナリティで検索") },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Search, contentDescription = null)
                                        },
                                        singleLine = true,
                                    )

                                    if (searchLoading) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(32.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator()
                                        }
                                    } else if (searchQuery.isNotBlank()) {
                                        // 検索結果一覧 (全局横断)
                                        if (searchResults.isEmpty()) {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(32.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    "該当する番組がありません",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        } else {
                                            LazyColumn(
                                                contentPadding = PaddingValues(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                items(searchResults, key = { it.stationId + "|" + it.ftEpochMillis }) { cached ->
                                                    val station = stations.firstOrNull { it.id == cached.stationId }
                                                    SearchResultRow(
                                                        cached = cached,
                                                        onClick = {
                                                            if (station != null) {
                                                                viewModel.playCachedTimefree(station, cached)
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        // 検索語が空: 検索を促すヒント
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(32.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                "番組名・パーソナリティで検索できます",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                TimefreeMode.STATIONS -> {
                                    // 局一覧 (タップでその局の番組リストへ)
                                    LazyColumn(
                                        contentPadding = PaddingValues(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        items(stations, key = { it.id }) { station ->
                                            StationCard(
                                                station = station,
                                                onClick = { selectedStation = station },
                                            )
                                        }
                                    }
                                }
                                TimefreeMode.FAVORITES -> {
                                    // お気に入り一覧
                                    if (favorites.isEmpty()) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(32.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                "お気に入りはまだありません",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            contentPadding = PaddingValues(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            items(favorites, key = { it.stationId + "|" + it.ftEpochMillis }) { entry ->
                                                FavoriteEntryRow(
                                                    entry = entry,
                                                    onClick = { viewModel.playFavorite(entry) },
                                                    onRemove = {
                                                        viewModel.removeFavorite(entry.stationId, entry.ftEpochMillis)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // 局選択 → 番組リスト
                        selectedStation?.let { station ->
                            Column {
                                // ヘッダー
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = station.name,
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Text(
                                        text = "戻る",
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable {
                                            selectedStation = null
                                        },
                                    )
                                }
                                // 日付チップ (過去7日分)
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    item {
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            items(days) { offset ->
                                                val dayStart = RadikoTimeUtil.todayDayStart()
                                                    .minusSeconds(offset * 24 * 3600L)
                                                FilterChip(
                                                    selected = selectedDayOffset == offset,
                                                    onClick = { selectedDayOffset = offset },
                                                    label = {
                                                        Text(
                                                            when (offset) {
                                                                0 -> "今日"
                                                                1 -> "昨日"
                                                                else -> RadikoTimeUtil.formatDate(dayStart)
                                                            }
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    if (loading) {
                                        item {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(32.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                CircularProgressIndicator()
                                            }
                                        }
                                    } else if (programs.isEmpty()) {
                                        item {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(32.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text("この日の番組はありません")
                                            }
                                        }
                                    } else {
                                        items(programs, key = { it.ft.toEpochMilli() }) { program ->
                                            TimefreeProgramRow(
                                                program = program,
                                                isFavorite = (station.id to program.ft.toEpochMilli()) in favoriteKeys,
                                                onClick = { viewModel.playTimefree(station, program) },
                                                onToggleFavorite = { viewModel.toggleFavorite(station, program) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 検索結果の行。
 */
@Composable
private fun SearchResultRow(
    cached: CachedTimefreeProgram,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = cached.stationName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${RadikoTimeUtil.formatDate(Instant.ofEpochMilli(cached.ftEpochMillis))} " +
                    "${RadikoTimeUtil.formatTime(Instant.ofEpochMilli(cached.ftEpochMillis))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = cached.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // "null" 文字列は API の null 相当なので表示しない
        if (!cached.performer.isNullOrBlank() && cached.performer != "null") {
            Text(
                text = cached.performer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * タイムフリー再生可能な番組の行。右端にハート (お気に入りトグル) 付き。
 */
@Composable
private fun TimefreeProgramRow(
    program: Program,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${RadikoTimeUtil.formatTime(program.ft)} - ${RadikoTimeUtil.formatTime(program.to)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (program.isOnAir()) {
                    Text(
                        text = "放送中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = program.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // "null" 文字列は API の null 相当なので表示しない
            if (!program.performer.isNullOrBlank() && program.performer != "null") {
                Text(
                    text = program.performer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (isFavorite) "お気に入り解除" else "お気に入り登録",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

/**
 * お気に入りエントリの行。タップで再生、ハートで解除。
 */
@Composable
private fun FavoriteEntryRow(
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.stationName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${RadikoTimeUtil.formatDate(Instant.ofEpochMilli(entry.ftEpochMillis))} " +
                        "${RadikoTimeUtil.formatTime(Instant.ofEpochMilli(entry.ftEpochMillis))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = entry.programTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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

/** タイムフリー画面のモード。 */
private enum class TimefreeMode { SEARCH, STATIONS, FAVORITES }
