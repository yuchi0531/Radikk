package com.radikk.app.ui.screen

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.radikk.app.data.model.Program
import com.radikk.app.data.model.Station
import com.radikk.app.ui.AppViewModel
import com.radikk.app.ui.component.StationCard
import com.radikk.app.util.RadikoTimeUtil

/**
 * タイムフリー再生画面。
 * 局選択 → 過去7日分の番組リスト → タップで再生 (シーク可能)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimefreeScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val stationState by viewModel.stationState.collectAsState()
    var selectedStation by remember { mutableStateOf<Station?>(null) }
    var selectedDayOffset by remember { mutableStateOf(0) }
    var programs by remember { mutableStateOf<List<Program>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    // 過去7日分 (0=今日, 1=昨日, ...)
    val days = remember { (0..7).toList() }

    LaunchedEffect(selectedStation?.id, selectedDayOffset) {
        val station = selectedStation ?: return@LaunchedEffect
        loading = true
        try {
            // offset は「何日前」を表す (0=今日, 1=昨日, ...)
            programs = viewModel.getPrograms(station.id, -selectedDayOffset)
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
                        // 局選択
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.stations, key = { it.id }) { station ->
                                StationCard(
                                    station = station,
                                    onClick = { selectedStation = station },
                                )
                            }
                        }
                    } else {
                        // 番組リスト
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
                                                onClick = { viewModel.playTimefree(station, program) },
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
 * タイムフリー再生可能な番組の行。
 */
@Composable
private fun TimefreeProgramRow(
    program: Program,
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
        if (program.performer != null) {
            Text(
                text = program.performer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
