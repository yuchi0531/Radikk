package com.radikk.app.data.favorite

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.favoriteDataStore by preferencesDataStore(name = "radikk_favorites")

/**
 * お気に入り番組エントリ。
 *
 * タイムフリー番組をユーザーがお気に入り登録したもの。
 * 同一番組の識別は (stationId + ftEpochMillis) で行う。
 */
@Serializable
data class FavoriteEntry(
    val stationId: String,
    val stationName: String,
    val programTitle: String,
    val ftEpochMillis: Long,
    val toEpochMillis: Long,
    val addedAtEpochMillis: Long,
)

/**
 * お気に入り番組の永続化 (DataStore Preferences)。
 *
 * エントリ全体を単一の JSON 配列として 1 キー ("favorites") に保存する。
 * 最大 [MAX_ENTRIES] 件まで保持し、超過分は古い順 (addedAtEpochMillis 昇順) に捨てる。
 */
class FavoriteRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /** お気に入り一覧。追加日時 (addedAtEpochMillis) 降順 (新しい順)。 */
    val favorites: Flow<List<FavoriteEntry>> = context.favoriteDataStore.data
        .map { p ->
            val raw = p[stringPreferencesKey(KEY_FAVORITES)] ?: return@map emptyList()
            runCatching { json.decodeFromString<List<FavoriteEntry>>(raw) }.getOrDefault(emptyList())
                .sortedByDescending { it.addedAtEpochMillis }
        }

    /**
     * お気に入りに追加する。
     * 同一番組 (stationId + ftEpochMillis) が既にあれば置き換え、先頭 (最新) に挿入する。
     * [MAX_ENTRIES] を超えると古い順に捨てる。
     */
    suspend fun add(entry: FavoriteEntry) {
        context.favoriteDataStore.edit { p ->
            val current = runCatching {
                json.decodeFromString<List<FavoriteEntry>>(p[stringPreferencesKey(KEY_FAVORITES)] ?: "[]")
            }.getOrDefault(emptyList())
            val deduped = current.filterNot {
                it.stationId == entry.stationId && it.ftEpochMillis == entry.ftEpochMillis
            }
            val updated = (listOf(entry) + deduped).take(MAX_ENTRIES)
            p[stringPreferencesKey(KEY_FAVORITES)] = json.encodeToString(updated)
        }
    }

    /** お気に入りから削除する (同一番組 = stationId + ftEpochMillis)。 */
    suspend fun remove(stationId: String, ftEpochMillis: Long) {
        context.favoriteDataStore.edit { p ->
            val current = runCatching {
                json.decodeFromString<List<FavoriteEntry>>(p[stringPreferencesKey(KEY_FAVORITES)] ?: "[]")
            }.getOrDefault(emptyList())
            val updated = current.filterNot {
                it.stationId == stationId && it.ftEpochMillis == ftEpochMillis
            }
            p[stringPreferencesKey(KEY_FAVORITES)] = json.encodeToString(updated)
        }
    }

    /** 指定番組がお気に入り登録済みか (1回だけ読み取る)。 */
    suspend fun isFavorite(stationId: String, ftEpochMillis: Long): Boolean =
        favorites.first().any { it.stationId == stationId && it.ftEpochMillis == ftEpochMillis }

    /** お気に入りを全削除する。 */
    suspend fun clear() {
        context.favoriteDataStore.edit { p ->
            p.remove(stringPreferencesKey(KEY_FAVORITES))
        }
    }

    companion object {
        private const val KEY_FAVORITES = "favorites"

        /** 保持するお気に入りの最大件数。超過分は古い順に捨てる。 */
        const val MAX_ENTRIES = 100
    }
}
