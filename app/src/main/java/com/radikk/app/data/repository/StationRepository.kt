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
    }

    private val cacheMutex = Mutex()
    private var cachedStations: List<Station>? = null
    private var cachedAt: Instant? = null

    /**
     * 全放送局を取得する (1時間キャッシュ)。
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
        val stations = parseStationXml(xml)
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
     */
    fun filterByArea(stations: List<Station>, areaId: String): List<Station> {
        val local = stations.filter { it.areaIds.contains(areaId) }
        android.util.Log.d(
            "StationRepository",
            "filterByArea(areaId=$areaId): local=${local.size}, " +
                "localFirst=${local.take(5).map { it.id }}"
        )
        return local
    }
}
