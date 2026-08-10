package com.radikk.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.radikk.app.data.model.Program
import com.radikk.app.data.model.Station
import com.radikk.app.util.RadikoTimeUtil
import com.radikk.app.util.htmlToPlainText

/**
 * 番組詳細ダイアログ。
 *
 * 番組ロゴ (imgUrl)・詳細説明・放送時間・パーソナリティを表示し、
 * 「聞く」(ライブ/タイムフリー再生) と「番組開始通知」ボタンを提供する。
 */
@Composable
fun ProgramDetailDialog(
    station: Station,
    program: Program,
    isOnAir: Boolean,
    /** 放送終了済みか (タイムフリー再生可能)。false なら未来の番組 (未放送) */
    isPast: Boolean,
    isReminderSet: Boolean,
    onListen: () -> Unit,
    onReminderClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(program.title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 長い説明文で画面から溢れる場合はスクロール可能にする
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 番組ロゴ
                if (program.imgUrl != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = program.imgUrl,
                            contentDescription = program.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }

                // 局情報
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (station.logoUrl != null) {
                        AsyncImage(
                            model = station.logoUrl,
                            contentDescription = station.name,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Text(
                        text = station.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // 放送時間
                Text(
                    text = "${RadikoTimeUtil.formatDate(program.ft)} " +
                        "${RadikoTimeUtil.formatTime(program.ft)} - " +
                        RadikoTimeUtil.formatTime(program.to),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // パーソナリティ ("null" 文字列は API の null 相当なので表示しない)
                if (!program.performer.isNullOrBlank() && program.performer != "null") {
                    Text(
                        text = "出演: ${program.performer}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 詳細説明 (HTML タグはプレーンテキストに変換)
                if (!program.description.isNullOrBlank()) {
                    Text(
                        text = htmlToPlainText(program.description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // 開始通知の設定/解除 (本文末尾に配置。ボタン3つを横並びにしないため)
                // 発見しやすいよう、アイコン付きの全幅アウトラインボタンにする
                OutlinedButton(
                    onClick = onReminderClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isReminderSet) "通知を解除" else "開始通知を設定",
                        color = if (isReminderSet) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onListen,
                // 未来の番組 (未放送) は再生できないため無効化する
                enabled = isOnAir || isPast,
            ) {
                Text(
                    when {
                        isOnAir -> "聞く (ライブ)"
                        isPast -> "聞く (タイムフリー)"
                        else -> "未放送"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
    )
}
