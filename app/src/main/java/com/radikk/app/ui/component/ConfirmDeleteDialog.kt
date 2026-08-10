package com.radikk.app.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * ダウンロード削除の確認ダイアログ。
 *
 * 削除はファイルも含めて元に戻せず、7日を過ぎた番組は再ダウンロードできないため、
 * 必ず確認を取る。ホーム / タイムフリーの両方のダウンロード一覧で共用する。
 */
@Composable
fun ConfirmDeleteDialog(
    programTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ダウンロードを削除") },
        text = {
            Text("「$programTitle」を削除しますか？\n7日を過ぎた番組は再ダウンロードできません")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("削除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
