package com.radikk.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.radikk.app.ui.AppViewModel
import com.radikk.app.util.RadikoTimeUtil
import com.radikk.app.util.htmlToPlainText

/**
 * 全画面プレイヤー。ミニプレイヤーのタップで表示される。
 *
 * - 番組ロゴ (programImgUrl) を大きく表示 (ない場合は局ロゴ)
 * - 局名・番組名
 * - 再生/一時停止ボタン
 * - シークバー (タイムフリーのみ有効) + 経過時間/総時間
 * - 閉じるボタン
 */
@Composable
fun FullPlayerScreen(
    viewModel: AppViewModel,
    onClose: () -> Unit,
) {
    val nowPlayingState by viewModel.nowPlaying.collectAsState()
    val playerState by viewModel.playerUiState.collectAsState()

    val nowPlaying = nowPlayingState

    // nowPlaying が null になった場合 (将来の停止操作など) は自動で閉じる。
    // コンポジション中の直接呼び出しではなく LaunchedEffect で副作用を分離する。
    LaunchedEffect(nowPlayingState) {
        if (nowPlayingState == null) {
            onClose()
        }
    }

    nowPlaying ?: return

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 閉じるボタン (左上)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "閉じる",
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 番組ロゴ → 局名 → 番組名 → ラベル → 出演者 → 説明文 の中央セクション。
            // 長い番組詳細で画面から溢れる場合はスクロール可能、短い場合は中央寄せ。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 番組ロゴ (大きく表示)
                    val imageUrl = nowPlaying.programImgUrl ?: nowPlaying.stationLogoUrl
                    if (imageUrl != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                model = imageUrl,
                                contentDescription = nowPlaying.title,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // 局名
                    Text(
                        text = nowPlaying.stationName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(4.dp))

                    // 番組名
                    Text(
                        text = nowPlaying.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(16.dp))

                    // ラベル: ライブ / タイムフリー
                    Text(
                        text = if (nowPlaying.isTimefree) "タイムフリー" else "ライブ放送",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    // 番組詳細 (パーソナリティ・説明)。ある場合のみ表示
                    if (!nowPlaying.performer.isNullOrBlank() && nowPlaying.performer != "null") {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "出演: ${nowPlaying.performer}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!nowPlaying.description.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = htmlToPlainText(nowPlaying.description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // シークバー (タイムフリーのみ表示)
            if (nowPlaying.isTimefree) {
                val durationMs = if (playerState.durationMs > 0) playerState.durationMs else 0L
                val positionMs = if (playerState.positionMs > 0) playerState.positionMs else 0L
                // ドラッグ中の値 (ポーリングと競合しないよう onValueChangeFinished でシーク)
                // 番組が変わったらリセットする (key = 局+番組開始時刻)
                var dragPositionMs by remember(nowPlaying.stationId, nowPlaying.title) {
                    mutableStateOf<Long?>(null)
                }
                val displayPositionMs = dragPositionMs ?: positionMs
                val maxValue = if (durationMs > 0) durationMs.toFloat() else 1f
                Slider(
                    enabled = durationMs > 0,
                    value = displayPositionMs.toFloat().coerceIn(0f, maxValue),
                    onValueChange = { value ->
                        dragPositionMs = value.toLong()
                    },
                    onValueChangeFinished = {
                        dragPositionMs?.let { viewModel.seekTo(it) }
                        dragPositionMs = null
                    },
                    valueRange = 0f..maxValue,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = RadikoTimeUtil.formatDuration(displayPositionMs),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = RadikoTimeUtil.formatDuration(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // 再生/一時停止ボタン
            if (playerState.isLoading) {
                CircularProgressIndicator()
            } else {
                IconButton(
                    onClick = {
                        if (playerState.isPlaying) viewModel.pause() else viewModel.play()
                    },
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        imageVector = if (playerState.isPlaying) {
                            Icons.Filled.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = if (playerState.isPlaying) "一時停止" else "再生",
                        modifier = Modifier.size(56.dp),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

