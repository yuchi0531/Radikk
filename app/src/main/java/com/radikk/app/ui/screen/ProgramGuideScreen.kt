package com.radikk.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radikk.app.data.model.Program
import com.radikk.app.data.model.Station
import com.radikk.app.ui.AppViewModel
import com.radikk.app.util.RadikoTimeUtil
import java.time.Instant

/**
 * 番組表画面 (EPG グリッド)。
 *
 * 横軸: 局 (エリア内の局、横スクロール)
 * 縦軸: 時間 (JST 5:00〜29:00、縦スクロール)
 * 左上: 日付、ヘッダー行: 局名、左端: 時刻ラベル。
 *
 * - 日付チップ (今日〜7日分)
 * - 現在時刻ライン (今日のみ)
 * - 番組セルは放送時間に応じた高さ
 * - 局名タップ = ライブ再生、番組タップ = 放送中ならライブ / 過去ならタイムフリー
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramGuideScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val stationState by viewModel.stationState.collectAsState()
    var selectedDayOffset by remember { mutableStateOf(0) }

    // 局 → 番組リスト (EPG グリッド用に一括取得)
    var programsByStation by remember { mutableStateOf<Map<String, List<Program>>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }

    // 選択中のエリアの局一覧 (stationState から)
    val stations = (stationState as? AppViewModel.StationUiState.Success)?.stations ?: emptyList()

    // 日付チップ (今日〜7日分)
    val days = remember { (0..7).toList() }

    // 日付・局一覧が変わったら全局の番組表を並列取得
    LaunchedEffect(stations.map { it.id }.joinToString(","), selectedDayOffset) {
        if (stations.isEmpty()) return@LaunchedEffect
        loading = true
        try {
            programsByStation = viewModel.getProgramsForStations(stations, selectedDayOffset)
        } catch (e: Exception) {
            programsByStation = emptyMap()
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("番組表") })
        },
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 日付チップ
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(days) { offset ->
                    val dayStart = RadikoTimeUtil.todayDayStart().plusSeconds(offset * 24 * 3600L)
                    FilterChip(
                        selected = selectedDayOffset == offset,
                        onClick = { selectedDayOffset = offset },
                        label = {
                            Text(
                                when (offset) {
                                    0 -> "今日"
                                    1 -> "明日"
                                    else -> RadikoTimeUtil.formatDate(dayStart)
                                }
                            )
                        },
                    )
                }
            }

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
                    if (stations.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("聴ける局がありません")
                        }
                    } else if (loading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        EpgGrid(
                            stations = stations,
                            programsByStation = programsByStation,
                            dayOffset = selectedDayOffset,
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }
}

/** 1時間あたりのピクセル数 (縦軸) */
private val HOUR_HEIGHT_DP = 56.dp

/** 1局あたりの幅 (横軸) */
private val STATION_WIDTH_DP = 150.dp

/** 時刻ラベル列の幅 (左固定) */
private val TIME_COLUMN_WIDTH_DP = 44.dp

/** 日付・時刻の表示に使う時間帯の開始 (JST 5:00) */
private const val GRID_START_HOUR = 5

/** 番組表の時間帯 (5:00 から翌 5:00 まで) */
private const val GRID_HOURS = 24

/**
 * EPG グリッド本体。
 * 横軸 = 局、縦軸 = 時間。時刻ラベルと局名ヘッダーを固定し、
 * 本体部分を横スクロール + 縦スクロールする。
 */
@Composable
private fun EpgGrid(
    stations: List<Station>,
    programsByStation: Map<String, List<Program>>,
    dayOffset: Int,
    viewModel: AppViewModel,
) {
    // 番組表の開始時刻 = 選択日の 5:00 JST
    val gridStart = RadikoTimeUtil.todayDayStart()
        .plusSeconds(dayOffset * 24 * 3600L)

    val columnScroll = rememberScrollState() // 縦スクロール
    val rowScroll = rememberScrollState()    // 横スクロール

    Column(Modifier.fillMaxSize()) {
        // --- ヘッダー行 (局名) ---
        // 左端の時刻コーナー + 横スクロールする局名行
        Row(Modifier.fillMaxWidth()) {
            // 左上コーナー: 日付
            Box(
                modifier = Modifier
                    .width(TIME_COLUMN_WIDTH_DP)
                    .height(HOUR_HEIGHT_DP)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = RadikoTimeUtil.formatDate(gridStart),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
            // 局名ヘッダー (横スクロール)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScrollState(rowScroll),
            ) {
                stations.forEach { station ->
                    StationHeader(
                        station = station,
                        onClick = { viewModel.playLive(station) },
                    )
                }
            }
        }
        HorizontalDivider()

        // --- 本体: 時刻列 + 番組グリッド (横・縦スクロール) ---
        Row(Modifier.fillMaxSize()) {
            // 時刻ラベル列 (左固定、縦スクロール)
            Column(
                modifier = Modifier
                    .width(TIME_COLUMN_WIDTH_DP)
                    .fillMaxHeight()
                    .verticalScrollState(columnScroll),
            ) {
                for (h in GRID_START_HOUR until GRID_START_HOUR + GRID_HOURS) {
                    Box(
                        modifier = Modifier
                            .height(HOUR_HEIGHT_DP)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Text(
                            text = "${h % 24}:00",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            // 番組グリッド (横・縦スクロール)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .verticalScrollState(columnScroll)
                    .horizontalScrollState(rowScroll),
            ) {
                stations.forEach { station ->
                    StationColumn(
                        station = station,
                        programs = programsByStation[station.id].orEmpty(),
                        gridStart = gridStart,
                        onProgramClick = { program ->
                            if (program.isOnAir()) {
                                viewModel.playLive(station)
                            } else {
                                viewModel.playTimefree(station, program)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 局名ヘッダーセル。タップでライブ再生。 */
@Composable
private fun StationHeader(
    station: Station,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(STATION_WIDTH_DP)
            .height(HOUR_HEIGHT_DP)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = station.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = station.id,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 1局分の縦列。番組を放送時間に応じた高さで配置する。
 * 縦スクロールは親 (EpgGrid) が担当するため、自身ではスクロールしない。
 * 空き時間 (番組のない区間) は透明のセルにする。
 */
@Composable
private fun StationColumn(
    station: Station,
    programs: List<Program>,
    gridStart: Instant,
    onProgramClick: (Program) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(STATION_WIDTH_DP)
    ) {
        // 列の高さは親の縦スクロールと同期するため、実コンテンツを配置する。
        // 時間帯の開始から最初の番組までの空きを埋める。
        var cursor = gridStart
        for (program in programs) {
            // 番組開始がグリッド開始より前の場合 (5:00より前から放送) は先頭を切り詰める
            val cellStart = if (program.ft.isBefore(gridStart)) gridStart else program.ft
            val cellEnd = if (program.to.isAfter(gridStart.plusSeconds(24 * 3600L))) {
                gridStart.plusSeconds(24 * 3600L)
            } else {
                program.to
            }
            if (cellStart.isBefore(cellEnd)) {
                // 前の番組との間の空き
                val gapHours = (cellStart.toEpochMilli() - cursor.toEpochMilli()) / 3600_000.0
                if (gapHours > 0) {
                    Box(Modifier.height((gapHours * HOUR_HEIGHT_DP.value).dp))
                }
                val durationHours = (cellEnd.toEpochMilli() - cellStart.toEpochMilli()) / 3600_000.0
                ProgramCell(
                    program = program,
                    durationHours = durationHours,
                    onClick = { onProgramClick(program) },
                )
                cursor = cellEnd
            }
        }
        // 末尾の空きを埋める
        val tailHours = (gridStart.plusSeconds(24 * 3600L).toEpochMilli() - cursor.toEpochMilli()) / 3600_000.0
        if (tailHours > 0) {
            Box(Modifier.height((tailHours * HOUR_HEIGHT_DP.value).dp))
        }
    }
}

/**
 * 番組 1 セル。放送時間に応じた高さで表示し、放送中をハイライトする。
 * 現在時刻ラインは「今日」かつ放送中の場合に上部に表示する。
 */
@Composable
private fun ProgramCell(
    program: Program,
    durationHours: Double,
    onClick: () -> Unit,
) {
    val isOnAir = program.isOnAir()

    Box(
        modifier = Modifier
            .height((durationHours * HOUR_HEIGHT_DP.value).dp)
            .fillMaxWidth()
            .background(
                color = if (isOnAir) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Column {
            Text(
                text = "${RadikoTimeUtil.formatTime(program.ft)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = program.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Modifier 拡張: 横スクロール状態を共有する。 */
private fun Modifier.horizontalScrollState(state: androidx.compose.foundation.ScrollState) =
    this.then(
        Modifier.horizontalScroll(state)
    )

/** Modifier 拡張: 縦スクロール状態を共有する。 */
private fun Modifier.verticalScrollState(state: androidx.compose.foundation.ScrollState) =
    this.then(
        Modifier.verticalScroll(state)
    )
