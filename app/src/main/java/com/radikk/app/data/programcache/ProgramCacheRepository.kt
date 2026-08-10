package com.radikk.app.data.programcache

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.radikk.app.data.model.Program
import com.radikk.app.util.RadikoTimeUtil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

private val Context.programCacheDataStore by preferencesDataStore(name = "radikk_program_cache")

/**
 * 永続化される番組表エントリ。
 */
@Serializable
data class CachedProgram(
    val stationId: String,
    val ftEpochMillis: Long,
    val toEpochMillis: Long,
    val title: String,
    val description: String?,
    val performer: String?,
    val episodeId: String?,
    val imgUrl: String?,
)

/**
 * 番組表の永続キャッシュ (DataStore Preferences)。
 *
 * 選択エリアの全局番組表を「放送日ごと」にまとめて保存する。
 * - キー: `{areaId}_{apiDate}_{stationId}` (JSON)
 * - TTL キー: `{areaId}_{apiDate}` → 最後に取得した時刻 (一日一回制御用)
 * - エリア変更時は別エリアのキャッシュに影響しない (エリア分離)
 *
 * 「今日の分のみ」を対象にし、アプリ起動時に選択エリア全局をプリロードする。
 * 他の日付 (昨日/明日等) は画面表示時に個別取得する。
 */
class ProgramCacheRepository(private val context: Context) {

    companion object {
        private const val PREFIX = "pc_"
        private const val PREFIX_TTL = "pc_ttl_"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 指定エリア・放送日の全キャッシュ済み番組を返す (番組表の日付順にソート済み)。 */
    suspend fun getPrograms(areaId: String, apiDate: String): Map<String, List<Program>> {
        val prefix = PREFIX + "$areaId|$apiDate|"
        val data = context.programCacheDataStore.data.first()
        val result = mutableMapOf<String, List<Program>>()
        data.asMap().forEach { (key, value) ->
            val name = key.name
            if (name.startsWith(prefix)) {
                val stationId = name.removePrefix(prefix)
                val cached = runCatching {
                    json.decodeFromString<List<CachedProgram>>(value.toString())
                }.getOrNull() ?: return@forEach
                result[stationId] = cached.map { it.toProgram() }.sortedBy { it.ft }
            }
        }
        return result
    }

    /**
     * 指定エリア・放送日の全局番組を保存する。
     *
     * 空リストの局も「取得済み (番組なし)」として記録する。
     * これにより放送休止局があっても、キャッシュが全局をカバーしていると判定される。
     * 1局も番組を取得できなかった場合 (全失敗) は TTL を記録しない。
     */
    suspend fun putPrograms(areaId: String, apiDate: String, programsByStation: Map<String, List<Program>>) {
        context.programCacheDataStore.edit { p ->
            // 同日の既存キャッシュをクリアしてから書き込む
            val prefix = PREFIX + "$areaId|$apiDate|"
            p.asMap().keys.filter { it.name.startsWith(prefix) }.forEach { p.remove(it) }
            // 空リストも含めて全局を記録する (カバレッジ判定用)
            programsByStation.forEach { (stationId, programs) ->
                p[stringPreferencesKey(prefix + stationId)] =
                    json.encodeToString(programs.map { it.toCached() })
            }
            // 1局以上取得できた場合のみ TTL を記録する (全失敗時は次回再取得)
            val hasAnyProgram = programsByStation.values.any { it.isNotEmpty() }
            if (hasAnyProgram) {
                p[stringPreferencesKey(PREFIX_TTL + "$areaId|$apiDate")] =
                    Instant.now().toEpochMilli().toString()
            }
            // 14日より古い日付のキャッシュを削除する (キー無制限成長の防止)
            pruneOldDays(p, areaId)
        }
    }

    /**
     * 指定エリアの 14 日より古い日付 (apiDate YYYYMMDD) のキャッシュを削除する。
     * キー形式 `{PREFIX}{areaId}|{apiDate}|{stationId}` から日付部分を抽出し、
     * 今日の放送日より 14 日以上前のエントリと対応する TTL キーを除去する。
     * apiDate はゼロ埋め 8 桁のため、文字列比較で日付順が保証される。
     */
    private fun pruneOldDays(p: MutablePreferences, areaId: String) {
        val cutoff = RadikoTimeUtil.apiDateFor(RadikoTimeUtil.todayDayStart().minusSeconds(14 * 24 * 3600L))
        val prefix = PREFIX + "$areaId|"
        p.asMap().keys
            .filter { it.name.startsWith(prefix) }
            .mapNotNull { key ->
                // キーの残り部分は `{apiDate}|{stationId}` 形式
                val rest = key.name.removePrefix(prefix)
                val apiDatePart = rest.substringBefore('|')
                apiDatePart.takeIf { it.length == 8 && it.all(Char::isDigit) }?.let { key to it }
            }
            .filter { (_, datePart) -> datePart < cutoff }
            .forEach { (key, datePart) ->
                p.remove(key)
                p.remove(stringPreferencesKey(PREFIX_TTL + "$areaId|$datePart"))
            }
    }

    /**
     * 指定エリア・放送日のキャッシュが今日取得済みか (一日一回制御)。
     *
     * 日付の比較は、呼び出し側 (AppViewModel) が生成する apiDate と同じ基準
     * (JST 5:00 起点の放送日) で行う。深夜 0:00〜4:59 は「実日付の翌日」だが
     * 「放送日としては前日」なので、実日付 (LocalDate.now) との比較は誤り。
     */
    suspend fun isFetchedToday(areaId: String, apiDate: String): Boolean {
        // 今日の放送日 (JST 5:00 起点) を RadikoTimeUtil と同じ基準で計算する
        val todayApiDate = RadikoTimeUtil.apiDateFor(RadikoTimeUtil.todayDayStart())
        if (apiDate != todayApiDate) return false // 今日以外は常に再取得 (表示時個別取得のため)
        val ttlKey = PREFIX_TTL + "$areaId|$apiDate"
        val data = context.programCacheDataStore.data.first()
        val stored = data[stringPreferencesKey(ttlKey)]?.toLongOrNull() ?: return false
        // 今日の放送日 (5:00) 以降に取得したか
        return stored >= RadikoTimeUtil.todayDayStart().toEpochMilli()
    }

    /**
     * 指定エリア・放送日のキャッシュ最終取得時刻 (ミリ秒) を返す。未取得なら null。
     */
    suspend fun lastFetchedAt(areaId: String, apiDate: String): Long? {
        val ttlKey = PREFIX_TTL + "$areaId|$apiDate"
        val data = context.programCacheDataStore.data.first()
        return data[stringPreferencesKey(ttlKey)]?.toLongOrNull()
    }

    /**
     * 指定エリア・放送日のキャッシュを削除する。
     */
    suspend fun removeAreaDay(areaId: String, apiDate: String) {
        context.programCacheDataStore.edit { p ->
            val prefix = PREFIX + "$areaId|$apiDate|"
            p.asMap().keys.filter { it.name.startsWith(prefix) }.forEach { p.remove(it) }
            p.remove(stringPreferencesKey(PREFIX_TTL + "$areaId|$apiDate"))
        }
    }

    /**
     * 全キャッシュを削除する。
     */
    suspend fun clearAll() {
        context.programCacheDataStore.edit { p ->
            p.asMap().keys
                .filter { it.name.startsWith(PREFIX) || it.name.startsWith(PREFIX_TTL) }
                .forEach { p.remove(it) }
        }
    }
}

/**
 * 番組表キャッシュの永続エントリ → Program 変換用拡張。
 */
private fun Program.toCached(): CachedProgram = CachedProgram(
    stationId = stationId,
    ftEpochMillis = ft.toEpochMilli(),
    toEpochMillis = to.toEpochMilli(),
    title = title,
    description = description,
    performer = performer,
    episodeId = episodeId,
    imgUrl = imgUrl,
)

private fun CachedProgram.toProgram(): Program = Program(
    stationId = stationId,
    ft = Instant.ofEpochMilli(ftEpochMillis),
    to = Instant.ofEpochMilli(toEpochMillis),
    title = title,
    description = description,
    performer = performer,
    episodeId = episodeId,
    imgUrl = imgUrl,
)
