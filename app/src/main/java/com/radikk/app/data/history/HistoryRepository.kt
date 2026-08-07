package com.radikk.app.data.history

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.historyDataStore by preferencesDataStore(name = "radikk_history")

/**
 * 聞いた番組の履歴エントリ。
 */
@Serializable
data class HistoryEntry(
    val stationId: String,
    val stationName: String,
    val programTitle: String,
    val isTimefree: Boolean,
    val listenedAtEpochMillis: Long,
)

/**
 * 聞いた番組の履歴の永続化 (DataStore Preferences)。
 *
 * エントリ全体を単一の JSON 配列として 1 キー ("history") に保存する。
 * 最大 [MAX_ENTRIES] 件まで保持し、超過分は古い順に捨てる。
 */
class HistoryRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 履歴一覧。新しい順 (listenedAtEpochMillis 降順)。 */
    val history: Flow<List<HistoryEntry>> = context.historyDataStore.data
        .map { p ->
            val raw = p[stringPreferencesKey(KEY_HISTORY)] ?: return@map emptyList()
            runCatching { json.decodeFromString<List<HistoryEntry>>(raw) }.getOrDefault(emptyList())
                .sortedByDescending { it.listenedAtEpochMillis }
        }

    /** 履歴にエントリを追加する。既存 (同一局 + 番組タイトル + 種別) は先頭に移動し時刻を更新する。 */
    suspend fun add(entry: HistoryEntry) {
        context.historyDataStore.edit { p ->
            val current = runCatching {
                json.decodeFromString<List<HistoryEntry>>(p[stringPreferencesKey(KEY_HISTORY)] ?: "[]")
            }.getOrDefault(emptyList())
            val deduped = current.filterNot {
                it.stationId == entry.stationId &&
                    it.programTitle == entry.programTitle &&
                    it.isTimefree == entry.isTimefree
            }
            val updated = (listOf(entry) + deduped).take(MAX_ENTRIES)
            p[stringPreferencesKey(KEY_HISTORY)] = json.encodeToString(updated)
        }
    }

    /** 履歴を全削除する。 */
    suspend fun clear() {
        context.historyDataStore.edit { p ->
            p.remove(stringPreferencesKey(KEY_HISTORY))
        }
    }

    companion object {
        private const val KEY_HISTORY = "history"

        /** 保持する履歴の最大件数。 */
        const val MAX_ENTRIES = 50
    }
}
