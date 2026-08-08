package com.radikk.app.data.download

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * ダウンロードの進行状態を DownloadService ↔ AppViewModel 間で共有するイベントバス。
 * MainActivity の ReminderPlaybackEvents と同じトップレベル object パターン。
 */
object DownloadEvents {
    /** ダウンロード中の番組キー (stationId|ftEpochMillis)。 */
    val activeKeys = MutableStateFlow<Set<String>>(emptySet())
    /** ダウンロード進捗 (0.0〜1.0)。キーは stationId|ftEpochMillis。 */
    val progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** ユーザー向けメッセージ (完了/失敗/キャンセル)。Snackbar 表示用。 */
    val messages = MutableStateFlow<String?>(null)
}
