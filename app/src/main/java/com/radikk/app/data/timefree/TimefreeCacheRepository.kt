package com.radikk.app.data.timefree

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.radikk.app.data.model.Program
import com.radikk.app.util.RadikoTimeUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val Context.timefreeDataStore by preferencesDataStore(name = "radikk_timefree")

/**
 * タイムフリー番組の永続キャッシュエントリ。
 */
@Serializable
data class CachedTimefreeProgram(
    val stationId: String,
    val stationName: String,
    val ftEpochMillis: Long,
    val toEpochMillis: Long,
    val title: String,
    val description: String?,
    val performer: String?,
    val imgUrl: String? = null,
)

/**
 * タイムフリー番組一覧の永続キャッシュ (DataStore Preferences)。
 *
 * - 局IDごとに番組リストを保存 (JSON)
 * - タイムフリーは「現在時刻から過去7日間 (168時間)」のみ再生可能
 * - 期間外 (7日より前、または未来) の番組は読み取り時に破棄する
 *
 * キャッシュされた一覧は検索にも使われるため、局ID + 番組タイトル/パーソナリティで
 * 部分一致検索できる。
 */
class TimefreeCacheRepository(private val context: Context) {

    /** タイムフリー再生可能な最大期間 (過去7日間 = 168時間) */
    companion object {
        private const val PREFIX = "timefree_"
        private const val MAX_AGE_HOURS = 24 * 7L // 7日
        const val MAX_AGE_MILLIS = MAX_AGE_HOURS * 3600 * 1000L

        // 旧実装が書き込んでいた TTL キー。新実装では書き込まないが、
        // 過去に書き込まれたキーを removeStation/clearAll で掃除するために残す。
        private const val PREFIX_TTL = "timefree_ttl_"

        /** 指定時刻 (エポックミリ秒) がタイムフリー期間内か。 */
        fun isWithinTimefree(ftEpochMillis: Long, nowEpochMillis: Long): Boolean {
            val cutoff = nowEpochMillis - MAX_AGE_MILLIS
            return ftEpochMillis >= cutoff && ftEpochMillis <= nowEpochMillis
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 検索ホットパス用のメモリキャッシュ (局ID → 番組リスト)。
     *
     * プリロード (全局×7日) と検索が同一プロセスで動く前提。DataStore のフル再読込・
     * 再デコードを検索のたびに行うと、毎キーストロークで全局分の JSON をデコードしてしまう。
     * DataStore が正になるが、このマップはインメモリの読み取り専用ビューとして扱う。
     *
     * 並列プリロード (約110局) が同時に書き込むため ConcurrentHashMap でスレッドセーフにする。
     * 読み取り時に期間外フィルタは行わない (cachedPrograms フローの読み取り時と同じ
     * セマンティクスを保つため、currentCachedPrograms でフィルタする)。
     */
    private val memoryCache = ConcurrentHashMap<String, List<CachedTimefreeProgram>>()
    @Volatile
    private var memoryLoaded = false

    /**
     * DataStore からメモリキャッシュへ一度だけロードする。
     * 初回アクセス時 (currentCachedPrograms) に DataStore の全件を読み、以降はメモリから返す。
     */
    private suspend fun ensureMemoryLoaded() {
        if (memoryLoaded) return
        // キャッシュのプリロードは一度だけだが、並列コルーチンから同時に呼ばれうる。
        // synchronized 内で suspend (first()) はコンパイルエラーになるため、
        // ロードをロックの外で行い、書き込みだけをロック内で二重チェックする。
        // (同じ内容の冪等な書き込みなので、稀に二重に読んでも問題ない)
        val loaded = cachedPrograms.first()
        synchronized(this) {
            if (memoryLoaded) return
            loaded.forEach { (stationId, programs) ->
                memoryCache[stationId] = programs
            }
            memoryLoaded = true
        }
    }

    /**
     * 局ID → キャッシュ済み番組リストの Flow (期間外は破棄済み)。
     */
    val cachedPrograms: Flow<Map<String, List<CachedTimefreeProgram>>> =
        context.timefreeDataStore.data.map { p ->
            // 毎回評価する (長時間購読しても期間外破棄が正しく機能する)
            val now = Instant.now().toEpochMilli()
            val cutoff = now - MAX_AGE_MILLIS

            p.asMap().entries
                .filter { it.key.name.startsWith(PREFIX) }
                .mapNotNull { (key, value) ->
                    val stationId = key.name.removePrefix(PREFIX)
                    val programs = runCatching {
                        json.decodeFromString<List<CachedTimefreeProgram>>(value.toString())
                    }.getOrNull() ?: return@mapNotNull null
                    // 期間外の番組を破棄し、"null" 文字列の performer を null に正規化する
                    val valid = programs
                        .filter { it.ftEpochMillis >= cutoff && it.ftEpochMillis <= now }
                        .map { it.normalizeNullStrings() }
                    if (valid.isEmpty()) null else stationId to valid
                }
                .toMap()
        }

    /**
     * 現在のキャッシュを取得する (期間外は破棄済み)。
     *
     * メモリキャッシュから返す。メモリ未ロード時は DataStore から一度だけロードする。
     * 書き込み (putStationPrograms) は DataStore とメモリの両方を更新するため、
     * メモリキャッシュは常に DataStore と同じ内容を保持する (期間外フィルタはここで適用)。
     */
    suspend fun currentCachedPrograms(): Map<String, List<CachedTimefreeProgram>> {
        ensureMemoryLoaded()
        val now = Instant.now().toEpochMilli()
        val cutoff = now - MAX_AGE_MILLIS
        return memoryCache.mapValues { (_, programs) ->
            programs.filter { it.ftEpochMillis >= cutoff && it.ftEpochMillis <= now }
                .map { it.normalizeNullStrings() }
        }.filterValues { it.isNotEmpty() }
    }

    /**
     * 指定局の番組をキャッシュに追加/更新する。
     */
    suspend fun putStationPrograms(stationId: String, programs: List<CachedTimefreeProgram>) {
        context.timefreeDataStore.edit { p ->
            p[stringPreferencesKey(PREFIX + stationId)] = json.encodeToString(programs)
        }
        memoryCache[stationId] = programs
    }

    /**
     * 指定局のキャッシュを削除する。
     */
    suspend fun removeStation(stationId: String) {
        context.timefreeDataStore.edit { p ->
            p.remove(stringPreferencesKey(PREFIX + stationId))
            p.remove(stringPreferencesKey(PREFIX_TTL + stationId))
        }
        memoryCache.remove(stationId)
    }

    /**
     * 全キャッシュを削除する。
     */
    suspend fun clearAll() {
        context.timefreeDataStore.edit { p ->
            p.asMap().keys
                .filter { it.name.startsWith(PREFIX) || it.name.startsWith(PREFIX_TTL) }
                .forEach { p.remove(it) }
        }
        memoryCache.clear()
    }

    /**
     * 番組タイトル・パーソナリティで部分一致検索する。
     * @param query 検索キーワード (空なら全件)
     * @return 検索結果 (放送日時の降順)
     */
    suspend fun search(query: String): List<CachedTimefreeProgram> {
        val all = currentCachedPrograms()
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            return all.values.flatten().sortedByDescending { it.ftEpochMillis }
        }
        val lower = normalized.lowercase()
        return all.values.flatten()
            .filter {
                it.title.lowercase().contains(lower) ||
                    it.performer?.lowercase()?.contains(lower) == true ||
                    it.stationName.lowercase().contains(lower)
            }
            .sortedByDescending { it.ftEpochMillis }
    }

    /**
     * キャッシュ済みの全番組 (検索用・期間外は破棄済み)。
     */
    suspend fun allPrograms(): List<CachedTimefreeProgram> {
        return currentCachedPrograms().values.flatten().sortedByDescending { it.ftEpochMillis }
    }

    /** Program を CachedTimefreeProgram に変換する。 */
    fun toCached(program: Program): CachedTimefreeProgram = CachedTimefreeProgram(
        stationId = program.stationId,
        stationName = "", // 局名は呼び出し側で補完
        ftEpochMillis = program.ft.toEpochMilli(),
        toEpochMillis = program.to.toEpochMilli(),
        title = program.title,
        description = program.description,
        performer = program.performer,
        imgUrl = program.imgUrl,
    )

    /** 指定の放送日がタイムフリー再生可能か (現在時刻から7日以内)。 */
    fun isWithinTimefree(ft: Instant, now: Instant = Instant.now()): Boolean =
        isWithinTimefree(ft.toEpochMilli(), now.toEpochMilli())

    /** キャッシュの局情報を補完する (局名を設定)。 */
    fun withStationName(cached: CachedTimefreeProgram, name: String): CachedTimefreeProgram =
        if (cached.stationName.isNotEmpty()) cached
        else cached.copy(stationName = name)

    /** 指定の放送日時がタイムフリー期間内か (現在時刻基準)。 */
    fun isWithinTimefree(ft: Instant): Boolean =
        TimefreeCacheRepository.isWithinTimefree(ft.toEpochMilli(), Instant.now().toEpochMilli())
}

/**
 * API の "null" 文字列 (radiko が performer 等に返す) を null に正規化する。
 * 古いキャッシュデータ (パース時に正規化される前) を救済するための読み取り時処理。
 */
private fun CachedTimefreeProgram.normalizeNullStrings(): CachedTimefreeProgram {
    val p = performer?.takeIf { it != "null" && it.isNotBlank() }
    val d = description?.takeIf { it != "null" && it.isNotBlank() }
    val i = imgUrl?.takeIf { it != "null" && it.isNotBlank() }
    if (p == performer && d == description && i == imgUrl) return this
    return copy(performer = p, description = d, imgUrl = i)
}
