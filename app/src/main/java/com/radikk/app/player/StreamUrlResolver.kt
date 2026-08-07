package com.radikk.app.player

import android.util.Log
import com.radikk.app.data.api.RadikoApi
import com.radikk.app.data.api.RadikoApiClient
import com.radikk.app.util.RadikoTimeUtil
import java.io.IOException
import java.time.Instant

/**
 * ストリーム URL 解決。
 *
 * radiko の再生フロー:
 * 1. station stream XML から areafree/timefree に応じた playlist_create_url を選ぶ
 * 2. m3u8 (マスタープレイリスト) を取得 → `#EXT-X-STREAM-INF` 直後の medialist URL を抽出
 * 3. medialist URL を直接 ExoPlayer に渡す (マスタープレイリストを介さない)
 *
 * マスタープレイリストをスキップすることで、Flutter 版で発生した
 * SampleQueueMappingException (audio/mp4a-latm) の Source Error を回避する。
 */
class StreamUrlResolver(
    private val apiClient: RadikoApiClient,
) {
    companion object {
        private const val TAG = "StreamUrlResolver"

        /** 検証済みの放送局 (テスト用) */
        const val TEST_STATION = "TBS"
    }

    /**
     * ライブ再生用の m3u8 リクエスト URL を組み立てる。
     * 認証ヘッダーが必要。
     */
    fun buildLivePlaylistUrl(stationId: String, playlistCreateUrl: String, lsid: String): String =
        "$playlistCreateUrl?station_id=$stationId&l=300&type=b&lsid=$lsid"

    /**
     * タイムフリー再生用の m3u8 リクエスト URL を組み立てる。
     * ft/to は JST 14桁 (YYYYMMDDHHMMSS)。全パラメータ必須。
     */
    fun buildTimefreePlaylistUrl(
        stationId: String,
        playlistCreateUrl: String,
        from: Instant,
        to: Instant,
        lsid: String,
    ): String {
        val ft = RadikoTimeUtil.formatJst14(from)
        val toJst = RadikoTimeUtil.formatJst14(to)
        return "$playlistCreateUrl?station_id=$stationId&ft=$ft&to=$toJst" +
            "&start_at=$ft&end_at=$toJst&type=b&l=300&seek=$ft&lsid=$lsid"
    }

    /**
     * m3u8 の body から `#EXT-X-STREAM-INF` 直後の medialist URL を抽出する。
     * 見つからない場合は IOException。
     */
    fun extractMedialistUrl(m3u8Body: String): String {
        val lines = m3u8Body.lineSequence().map { it.trim() }.toList()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                if (i + 1 < lines.size && lines[i + 1].isNotBlank() && !lines[i + 1].startsWith("#")) {
                    return lines[i + 1]
                }
            }
        }
        // フォールバック: レスポンスがすでに medialist 形式 (#EXTINF を含む) の場合、
        // 最初の http(s) URL をそのまま medialist URL として使う
        lines.firstOrNull { it.startsWith("http") }?.let { return it }
        throw IOException("マスタープレイリストから medialist URL を抽出できませんでした")
    }

    /**
     * station stream XML からライブ用 playlist_create_url を取得する。
     * areafree="1" かつ timefree="0" の URL を選択。
     */
    suspend fun getLivePlaylistUrl(stationId: String): String {
        val xml = apiClient.getString(RadikoApi.STATION_STREAM_URL + stationId + ".xml")
        return extractPlaylistUrl(xml, areafree = true, timefree = false)
    }

    /**
     * station stream XML からタイムフリー用 playlist_create_url を取得する。
     * areafree="0" かつ timefree="1" の URL を選択。
     */
    suspend fun getTimefreePlaylistUrl(stationId: String): String {
        val xml = apiClient.getString(RadikoApi.STATION_STREAM_URL + stationId + ".xml")
        return extractPlaylistUrl(xml, areafree = false, timefree = true)
    }

    /**
     * station stream XML をパースして playlist_create_url を抽出する。
     * <url areafree="0|1" max_delay="60" timefree="0|1"><playlist_create_url>...</...>
     * 属性の順序に依存しない (areafree/timefree を個別に検索)。
     */
    internal fun extractPlaylistUrl(xml: String, areafree: Boolean, timefree: Boolean): String {
        // <url ...> ブロックごとに areafree/timefree 属性と playlist_create_url を抽出
        val urlBlockRegex = Regex("""<url\b([^>]*)>\s*<playlist_create_url>([^<]+)</playlist_create_url>""")
        val targetAreafree = if (areafree) "1" else "0"
        val targetTimefree = if (timefree) "1" else "0"

        for (match in urlBlockRegex.findAll(xml)) {
            val attrs = match.groupValues[1]
            val url = match.groupValues[2]
            val urlAreafree = Regex("""areafree="(\d+)"""").find(attrs)?.groupValues?.get(1)
            val urlTimefree = Regex("""timefree="(\d+)"""").find(attrs)?.groupValues?.get(1)
            if (urlAreafree == targetAreafree && urlTimefree == targetTimefree) {
                return url
            }
        }
        throw IOException("station XML から playlist_create_url を見つけられませんでした (areafree=$areafree, timefree=$timefree)")
    }

    /**
     * ライブ再生用の medialist URL を取得する。
     * @param token 認証トークン (X-Radiko-AuthToken)
     */
    suspend fun resolveLiveMedialistUrl(
        stationId: String,
        token: String,
        lsid: String = RadikoApi.randomHex32(),
    ): String {
        val playlistUrl = getLivePlaylistUrl(stationId)
        val m3u8Url = buildLivePlaylistUrl(stationId, playlistUrl, lsid)
        return resolveMedialist(m3u8Url, token)
    }

    /**
     * タイムフリー再生用の medialist URL を取得する。
     */
    suspend fun resolveTimefreeMedialistUrl(
        stationId: String,
        token: String,
        from: Instant,
        to: Instant,
        lsid: String = RadikoApi.randomHex32(),
    ): String {
        val playlistUrl = getTimefreePlaylistUrl(stationId)
        val m3u8Url = buildTimefreePlaylistUrl(stationId, playlistUrl, from, to, lsid)
        return resolveMedialist(m3u8Url, token)
    }

    private suspend fun resolveMedialist(m3u8Url: String, token: String): String {
        val headers = mapOf("X-Radiko-AuthToken" to token)
        val body = apiClient.getString(m3u8Url, headers)
        Log.d(TAG, "m3u8 (${m3u8Url.take(80)}...): ${body.take(200)}")
        return extractMedialistUrl(body)
    }
}
