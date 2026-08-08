package com.radikk.app.data.download

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.downloadDataStore by preferencesDataStore(name = "radikk_downloads")

/**
 * ダウンロード済みタイムフリー番組エントリ。
 *
 * 同一番組の識別は (stationId + ftEpochMillis) で行う。
 */
@Serializable
data class DownloadedProgram(
    val stationId: String,
    val stationName: String,
    val programTitle: String,
    val ftEpochMillis: Long,
    val toEpochMillis: Long,
    val filePath: String,
    val downloadedAtEpochMillis: Long,
    /** 番組ロゴ URL。フルプレイヤーで表示 (無ければ局ロゴにフォールバック)。 */
    val imgUrl: String? = null,
    /** 番組の説明文。フルプレイヤーで表示 (ストリーミングのタイムフリーと同じ)。 */
    val description: String? = null,
    /** 番組の出演者。フルプレイヤーで表示 (ストリーミングのタイムフリーと同じ)。 */
    val performer: String? = null,
)

/**
 * ダウンロード済み番組の永続化 (DataStore Preferences)。
 *
 * エントリ全体を単一の JSON 配列として 1 キー ("downloads") に保存する。
 * 最大 [MAX_ENTRIES] 件まで保持し、超過分は古い順 (downloadedAtEpochMillis 昇順) に捨てる。
 */
class DownloadRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 上限 [MAX_ENTRIES] 超過で捨てられたエントリのコールバック。
     * リポジトリは Context からファイルを直接削除せず、メタデータのみ管理するため、
     * 呼び出し側 (ViewModel 等) でファイル削除を実施する。
     */
    var onEvicted: (List<DownloadedProgram>) -> Unit = {}

    /** ダウンロード済み一覧。ダウンロード日時 (downloadedAtEpochMillis) 降順 (新しい順)。 */
    val downloads: Flow<List<DownloadedProgram>> = context.downloadDataStore.data
        .map { p ->
            val raw = p[stringPreferencesKey(KEY_DOWNLOADS)] ?: return@map emptyList()
            runCatching { json.decodeFromString<List<DownloadedProgram>>(raw) }.getOrDefault(emptyList())
                .sortedByDescending { it.downloadedAtEpochMillis }
        }

    /**
     * ダウンロード済みに追加する。
     * 同一番組 (stationId + ftEpochMillis) が既にあれば置き換え、先頭 (最新) に挿入する。
     * [MAX_ENTRIES] を超えると古い順に捨て、捨てたエントリを [onEvicted] に通知する。
     */
    suspend fun add(entry: DownloadedProgram) {
        context.downloadDataStore.edit { p ->
            val current = runCatching {
                json.decodeFromString<List<DownloadedProgram>>(p[stringPreferencesKey(KEY_DOWNLOADS)] ?: "[]")
            }.getOrDefault(emptyList())
            val deduped = current.filterNot {
                it.stationId == entry.stationId && it.ftEpochMillis == entry.ftEpochMillis
            }
            val updated = listOf(entry) + deduped
            val evicted = if (updated.size > MAX_ENTRIES) updated.subList(MAX_ENTRIES, updated.size) else emptyList()
            val kept = updated.take(MAX_ENTRIES)
            p[stringPreferencesKey(KEY_DOWNLOADS)] = json.encodeToString(kept)
            if (evicted.isNotEmpty()) onEvicted(evicted)
        }
    }

    /** ダウンロード済みから削除する (同一番組 = stationId + ftEpochMillis)。 */
    suspend fun remove(stationId: String, ftEpochMillis: Long) {
        context.downloadDataStore.edit { p ->
            val current = runCatching {
                json.decodeFromString<List<DownloadedProgram>>(p[stringPreferencesKey(KEY_DOWNLOADS)] ?: "[]")
            }.getOrDefault(emptyList())
            val updated = current.filterNot {
                it.stationId == stationId && it.ftEpochMillis == ftEpochMillis
            }
            p[stringPreferencesKey(KEY_DOWNLOADS)] = json.encodeToString(updated)
        }
    }

    /** ダウンロード済みを全削除する。 */
    suspend fun clear() {
        context.downloadDataStore.edit { p ->
            p.remove(stringPreferencesKey(KEY_DOWNLOADS))
        }
    }

    companion object {
        private const val KEY_DOWNLOADS = "downloads"

        /** 保持するダウンロード済みの最大件数。超過分は古い順に捨てる。 */
        const val MAX_ENTRIES = 100
    }
}
