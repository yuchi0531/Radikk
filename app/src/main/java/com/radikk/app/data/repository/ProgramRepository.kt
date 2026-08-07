package com.radikk.app.data.repository

import com.radikk.app.data.api.RadikoApi
import com.radikk.app.data.api.RadikoApiClient
import com.radikk.app.data.model.Program
import com.radikk.app.util.RadikoTimeUtil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * 日別番組表のリポジトリ。
 * api.radiko.jp/program/v4/date/{YYYYMMDD}/station/{stationId}.json から取得し、1時間キャッシュする。
 *
 * 注意: 時間軸は JST 5:00 起点。YYYYMMDD は JST 基準の放送日。
 * キャッシュはメモリ上限 300 エントリでクリアする。
 */
class ProgramRepository(
    private val apiClient: RadikoApiClient,
    private val authTokenProvider: suspend () -> String?,
) {
    companion object {
        private const val TAG = "ProgramRepository"
        private const val CACHE_TTL_MS = 60L * 60 * 1000 // 1時間
        private const val CACHE_MAX_ENTRIES = 300
    }

    data class CacheKey(val stationId: String, val apiDate: String)

    private val cacheMutex = Mutex()
    private val cache = object : LinkedHashMap<CacheKey, CacheEntry>(128, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean {
            return size > CACHE_MAX_ENTRIES
        }
    }

    private class CacheEntry(
        val programs: List<Program>,
        val cachedAt: Instant,
    )

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /**
     * 指定の放送日 (JST 5:00 起点) の番組表を取得する。
     * @param apiDate JST の YYYYMMDD (RadikoTimeUtil.apiDateFor で生成)
     */
    suspend fun getPrograms(stationId: String, apiDate: String, forceRefresh: Boolean = false): List<Program> =
        cacheMutex.withLock {
            val key = CacheKey(stationId, apiDate)
            val entry = cache[key]
            if (!forceRefresh && entry != null &&
                entry.cachedAt.isAfter(Instant.now().minusMillis(CACHE_TTL_MS))
            ) {
                return entry.programs
            }

            val token = authTokenProvider() ?: throw IllegalStateException("認証が必要です")
            val url = RadikoApi.PROGRAM_DATE_URL + apiDate + "/station/" + stationId + ".json"
            val body = apiClient.getString(url, mapOf("X-Radiko-AuthToken" to token))
            val programs = parseProgramJson(body, stationId)

            cache[key] = CacheEntry(programs, Instant.now())
            programs
        }

    /**
     * v4 date JSON をパースする。
     * stations[].programs.program[] から ft/to/title/description/performer/episode_id/img を抽出。
     */
    internal fun parseProgramJson(body: String, stationId: String): List<Program> {
        val root = json.parseToJsonElement(body).jsonObject
        val stations = root["stations"]?.jsonArray ?: return emptyList()
        val programs = mutableListOf<Program>()

        for (station in stations) {
            val obj = station.jsonObject
            val stationPrograms = obj["programs"]?.jsonObject?.get("program")?.jsonArray ?: continue
            for (program in stationPrograms) {
                val p = program.jsonObject
                val ftStr = p["ft"]?.jsonPrimitive?.content ?: continue
                val toStr = p["to"]?.jsonPrimitive?.content ?: continue
                val title = p["title"]?.jsonPrimitive?.content ?: continue
                try {
                    programs.add(
                        Program(
                            stationId = stationId,
                            ft = RadikoTimeUtil.parseJst14ToInstant(ftStr),
                            to = RadikoTimeUtil.parseJst14ToInstant(toStr),
                            title = title,
                            description = normalizeNullable(p["description"]?.jsonPrimitive?.content),
                            performer = normalizeNullable(p["performer"]?.jsonPrimitive?.content),
                            episodeId = normalizeNullable(p["episode_id"]?.jsonPrimitive?.content),
                            imgUrl = normalizeNullable(p["img"]?.jsonPrimitive?.content),
                        )
                    )
                } catch (_: Exception) {
                    // 不正な日時はスキップ
                }
            }
        }
        return programs.sortedBy { it.ft }
    }

    /**
     * API の null 相当値 ("null" 文字列や空白) を null に正規化する。
     * radiko の番組 JSON は performer 等に "null" 文字列を返すことがある。
     */
    private fun normalizeNullable(value: String?): String? =
        if (value.isNullOrBlank() || value == "null") null else value

    /** キャッシュをクリアする (認証キャッシュ削除時など)。 */
    suspend fun clearCache() = cacheMutex.withLock {
        cache.clear()
    }
}
