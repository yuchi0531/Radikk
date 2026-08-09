package com.radikk.app.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.radikk.app.ui.AppViewModel
import com.radikk.app.util.RadikoTimeUtil

/**
 * ミニプレイヤー。画面下部に固定表示。
 * 上部 (局名・番組名 + 再生/停止ボタン) のタップでフルプレイヤー。
 * 下部のシークバーはドラッグで直接シークできる (フルプレイヤーと同じ
 * onValueChangeFinished で seekTo する方式。タップではフルプレイヤーを開かない)。
 */
@Composable
fun MiniPlayer(
    viewModel: AppViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowPlayingState by viewModel.nowPlaying.collectAsState()
    val playerState by viewModel.playerUiState.collectAsState()

    val nowPlaying = nowPlayingState ?: return
    val isPlaying = playerState.isPlaying

    // テーマ連動: 背景は elevation のあるサーフェストークンで、ダーク/ライト両モードで
    // アプリ背景と区別できる。テキスト・アイコンはテーマの既定色 (contentColor) を使い、
    // ハードコード色を排除する (Monet 常時適用と両モード対応のため)。
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            // 上部: 局名・番組名 + 再生/停止ボタン。タップでフルプレイヤーを開く。
            // (シークバー領域はドラッグ専用のため、クリックはこの Row のみ)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = nowPlaying.stationName,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = nowPlaying.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = {
                        if (isPlaying) viewModel.pause() else viewModel.play()
                    },
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "一時停止" else "再生",
                    )
                }
                IconButton(
                    onClick = { viewModel.stop() },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "停止",
                    )
                }
            }
            // 下部: 再生位置のシークバー。ドラッグで直接シークできる。
            // シーク可否は NowPlaying.isTimefree で判定する: ライブ (isTimefree = false) は
            // disabled トラックを表示し、タイムフリー/ダウンロード再生のみシーク可能。
            // (durationMs > 0 判定はライブ HLS が推定 duration を返すため使わない)
            // ドラッグ中のシーク位置。null ならポーリング値 (playerState.positionMs) を表示する。
            // ポーリング更新と競合しないよう onValueChangeFinished でのみ seekTo する。
            // 番組が変わったらリセットする (key = 局+番組開始時刻)。
            val seekable = nowPlaying.isTimefree
            var dragPositionMs by remember(nowPlaying.stationId, nowPlaying.title) {
                mutableStateOf<Long?>(null)
            }
            val displayPositionMs = dragPositionMs ?: playerState.positionMs
            val durationMs = playerState.durationMs
            val maxValue = if (durationMs > 0) durationMs.toFloat() else 1f
            Slider(
                value = displayPositionMs.toFloat().coerceIn(0f, maxValue),
                onValueChange = { value -> dragPositionMs = value.toLong() },
                onValueChangeFinished = {
                    dragPositionMs?.let { viewModel.seekTo(it) }
                    dragPositionMs = null
                },
                enabled = seekable,
                valueRange = 0f..maxValue,
                // Monet 対応: シークバーはテーマの primary 色で、ダークでは明るく、ライトでは
                // 彩度の高いアクセントとして両モードで視認性を確保する。
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    disabledThumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    disabledActiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    // 既定の高さを維持する (thumb が潰れない)。height(6.dp) 指定は Slider を
                    // 押しつぶして見た目・操作性を悪化させるため、タップ領域を確保する。
                    .height(20.dp),
            )
            // 経過 / 総時間 (タイムフリー・ダウンロード再生のみ表示)
            if (seekable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = RadikoTimeUtil.formatDuration(displayPositionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = RadikoTimeUtil.formatDuration(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
