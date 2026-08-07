package com.radikk.app.data.repository

import com.radikk.app.data.api.RadikoApi
import com.radikk.app.data.api.RadikoApiClient
import com.radikk.app.data.model.Station
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 放送局一覧のリポジトリ。
 * radiko.jp/v3/station/region/full.xml から取得し、1時間キャッシュする。
 */
class StationRepository(private val apiClient: RadikoApiClient) {

    companion object {
        private const val CACHE_TTL_MS = 60L * 60 * 1000 // 1時間

        /**
         * NHK FM (JOAK-FM)。
         * radiko の局一覧 API (region/full.xml) には含まれないため、
         * 全国放送局として固定定義する。全国放送 (areafree) のため全エリアで聴ける。
         */
        val NHK_FM = Station(
            id = "JOAK-FM",
            name = "NHK FM（東京）",
            asciiName = "JOAK-FM",
            areafree = true,
            timefree = true,
            areaIds = emptyList(), // 全国放送: 特定エリアに属さない
            logoUrl = "https://radiko.jp/v2/static/station/logo/JOAK-FM/224x100.png",
        )
    }

    private val cacheMutex = Mutex()
    private var cachedStations: List<Station>? = null
    private var cachedAt: Instant? = null

    /**
     * 全放送局を取得する (1時間キャッシュ)。
     * 一覧 API に含まれない NHK FM を常に追加する。
     * @throws Exception ネットワーク・パースエラー
     */
    suspend fun getStations(forceRefresh: Boolean = false): List<Station> = cacheMutex.withLock {
        val cached = cachedStations
        val cachedTime = cachedAt
        if (!forceRefresh && cached != null && cachedTime != null &&
            cachedTime.isAfter(Instant.now().minusMillis(CACHE_TTL_MS))
        ) {
            return cached
        }

        val xml = apiClient.getString(RadikoApi.STATION_REGION_URL)
        val parsed = parseStationXml(xml)
        // NHK FM を追加 (重複回避)
        val stations = if (parsed.any { it.id == NHK_FM.id }) parsed else parsed + NHK_FM
        cachedStations = stations
        cachedAt = Instant.now()
        stations
    }

    /** full.xml をパースする。 */
    internal fun parseStationXml(xml: String): List<Station> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

        val stationNodes = doc.getElementsByTagName("station")
        val stations = mutableListOf<Station>()
        for (i in 0 until stationNodes.length) {
            val node = stationNodes.item(i)
            val element = node as org.w3c.dom.Element

            val id = element.getElementsByTagName("id").item(0)?.textContent?.trim() ?: continue
            val name = element.getElementsByTagName("name").item(0)?.textContent?.trim() ?: continue
            val asciiName = element.getElementsByTagName("ascii_name").item(0)?.textContent?.trim() ?: ""
            val areafree = element.getElementsByTagName("areafree").item(0)?.textContent?.trim() == "1"
            val timefree = element.getElementsByTagName("timefree").item(0)?.textContent?.trim() == "1"

            // area_id は複数あり得る
            val areaNodes = element.getElementsByTagName("area_id")
            val areaIds = mutableListOf<String>()
            for (j in 0 until areaNodes.length) {
                areaIds.add(areaNodes.item(j).textContent.trim())
            }

            // logo (最初のものを使う)
            val logoNodes = element.getElementsByTagName("logo")
            val logoUrl = if (logoNodes.length > 0) {
                logoNodes.item(0).textContent.trim()
            } else null

            stations.add(
                Station(
                    id = id,
                    name = name,
                    asciiName = asciiName,
                    areafree = areafree,
                    timefree = timefree,
                    areaIds = areaIds,
                    logoUrl = logoUrl,
                )
            )
        }
        return stations
    }

    /**
     * 指定エリアで聴ける局のみにフィルタする。
     * 選択エリアに所属する局 (areaIds に含まれる) のみを返す。
     * それ以外の局 (他エリアの局、全国放送局) は表示しない。
     *
     * 例外として NHK FM (JOAK-FM) は全国放送のため全エリアに表示する。
     */
    fun filterByArea(stations: List<Station>, areaId: String): List<Station> {
        val local = stations.filter { it.areaIds.contains(areaId) }
        // NHK FM (全国放送) を全エリアに含める
        val nhkFm = stations.firstOrNull { it.id == NHK_FM.id }
        val result = if (nhkFm != null && local.none { it.id == nhkFm.id }) {
            local + nhkFm
        } else {
            local
        }
        android.util.Log.d(
            "StationRepository",
            "filterByArea(areaId=$areaId): local=${local.size}, " +
                "localFirst=${local.take(5).map { it.id }}"
        )
        return result
    }
}
